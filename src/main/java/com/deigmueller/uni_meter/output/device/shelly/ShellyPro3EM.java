package com.deigmueller.uni_meter.output.device.shelly;

import com.deigmueller.uni_meter.application.UdpServer;
import com.deigmueller.uni_meter.application.UniMeter;
import com.deigmueller.uni_meter.application.WebsocketInput;
import com.deigmueller.uni_meter.application.WebsocketOutput;
import com.deigmueller.uni_meter.common.shelly.Rpc;
import com.deigmueller.uni_meter.common.shelly.RpcError;
import com.deigmueller.uni_meter.common.shelly.RpcException;
import com.deigmueller.uni_meter.common.utils.MathUtils;
import com.deigmueller.uni_meter.mdns.MDnsRegistrator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.typesafe.config.Config;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.apache.pekko.Done;
import org.apache.pekko.NotUsed;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.PostStop;
import org.apache.pekko.actor.typed.javadsl.*;
import org.apache.pekko.http.javadsl.Http;
import org.apache.pekko.http.javadsl.model.StatusCodes;
import org.apache.pekko.http.javadsl.model.ws.*;
import org.apache.pekko.http.javadsl.server.Route;

import org.apache.pekko.japi.Pair;
import org.apache.pekko.stream.KillSwitches;
import org.apache.pekko.stream.OverflowStrategy;
import org.apache.pekko.stream.UniqueKillSwitch;
import org.apache.pekko.stream.connectors.udp.Datagram;
import org.apache.pekko.stream.javadsl.*;
import org.apache.pekko.util.ByteString;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletionStage;

@Getter(AccessLevel.PROTECTED)
@Setter(AccessLevel.PROTECTED)
public class ShellyPro3EM extends Shelly {
  // Instance members
  private final ActorRef<WebsocketInput.Notification> websocketInputNotificationAdapter =
        getContext().messageAdapter(WebsocketInput.Notification.class, WrappedWebsocketInputNotification::new);
  private final ActorRef<UdpServer.Notification> udpServerNotificationAdapter =
        getContext().messageAdapter(UdpServer.Notification.class, WrappedUdpServerNotification::new);

  private final Map<String, WebsocketContext> websocketConnections = new HashMap<>();
  private final Map<InetSocketAddress, UdpClientContext> udpClients = new HashMap<>();

  private final int udpPort = getConfig().getInt("udp-port");
  private final String udpInterface = getConfig().getString("udp-interface");
  private final Duration udpRestartBackoff = getConfig().getDuration("udp-restart-backoff");
  private final Duration minSamplePeriod = getConfig().getDuration("min-sample-period");

  private ActorRef<Datagram> udpOutput = null;
  
  private Rpc.CloudGetConfigResponse cloudConfig = new Rpc.CloudGetConfigResponse(true, Rpc.RpcStringOrNull.of(null));
  private Rpc.WsGetConfigResponse wsConfig = new Rpc.WsGetConfigResponse(getConfig().getConfig("ws"));
  private InetAddress outboundWebsocketAddress = null;
  private String outboundWebsocketConnectionId = null;
  private boolean outboundWebsocketFailure = false;

  /**
   * Static setup method
   * @param controller Controller actor reference
   * @param config Output device configuration
   * @return Behavior of the created actor
   */
  public static Behavior<Command> create(@NotNull ActorRef<UniMeter.Command> controller,
                                         @NotNull ActorRef<MDnsRegistrator.Command> mDnsRegistrator,
                                         @NotNull Config config) {
    return Behaviors.setup(context -> new ShellyPro3EM(context, controller, mDnsRegistrator, config));
  }

  /**
   * Protected constructor called by the static setup method
   * @param context Actor context
   * @param controller Controller actor reference
   * @param mDnsRegistrator mDNS registration actor
   * @param config Output device configuration
   */
  protected ShellyPro3EM(@NotNull ActorContext<Command> context,
                         @NotNull ActorRef<UniMeter.Command> controller,
                         @NotNull ActorRef<MDnsRegistrator.Command> mDnsRegistrator,
                         @NotNull Config config) {
    super(context, controller, mDnsRegistrator, config);
    
    controller.tell(new UniMeter.RegisterHttpRoute(getBindInterface(), getBindPort(), createRoute()));

    registerMDns();
    
    startUdpServer();

    startOutboundWebsocketConnection();
  }

  @Override
  public @NotNull ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onMessage(CloudGetConfig.class, this::onCloudGetConfig)
          .onMessage(CloudSetConfig.class, this::onCloudSetConfig)
          .onMessage(EmGetConfig.class, this::onEmGetConfig)
          .onMessage(EmGetStatus.class, this::onEmGetStatus)
          .onMessage(EmDataGetStatus.class, this::onEmDataGetStatus)
          .onMessage(ResetData.class, this::onResetData)
          .onMessage(ShellyGetComponents.class, this::onShellyGetComponents)
          .onMessage(ShellyGetConfig.class, this::onShellyGetConfig)
          .onMessage(ShellyGetDeviceInfo.class, this::onShellyGetDeviceInfo)
          .onMessage(ShellyGetStatus.class, this::onShellyGetStatus)
          .onMessage(ShellyReboot.class, this::onShellyReboot)
          .onMessage(SysGetConfig.class, this::onSysGetConfig)
          .onMessage(WsGetConfig.class, this::onWsGetConfig)
          .onMessage(WsSetConfig.class, this::onWsSetConfig)
          .onMessage(HttpRpcRequest.class, this::onHttpRpcRequest)
          
          .onMessage(WebsocketOutputOpened.class, this::onWebsocketOutputOpened)
          .onMessage(WebsocketProcessPendingEmGetStatusRequest.class, this::onProcessPendingEmGetStatusRequest)
          .onMessage(WrappedWebsocketInputNotification.class, this::onWrappedWebsocketInputNotification)
          
          .onMessage(OutboundWebsocketConnectionOpened.class, this::onOutboundWebsocketConnectionOpened)
          .onMessage(OutboundWebsocketConnectionFailed.class, this::onOutboundWebsocketConnectionFailed)
          .onMessage(RetryOpenOutboundWebsocketConnection.class, this::onRetryOpenOutboundWebsocketConnection)
          
          .onMessage(WrappedUdpServerNotification.class, this::onWrappedUdpServerNotification)
          .onMessage(RetryStartUdpServer.class, this::onRetryStartUdpServer)
          .onMessage(UdpClientProcessPendingEmGetStatusRequest.class, this::onUdpClientProcessPendingEmGetStatusRequest)
          
          .onMessage(ThrottlingQueueClosed.class, this::onThrottlingQueueClosed);
  }

  /**
   * Handle the PostStop signal
   * @param message Post stop signal
   * @return Same behavior
   */
  @Override
  protected @NotNull Behavior<Command> onPostStop(@NotNull PostStop message) {
    return super.onPostStop(message);
  }

  /**
   * Handle an HTTP GET request for the Shelly device information
   * @param request Request for the Shelly device information
   * @return Same behavior
   */
  @Override
  protected Behavior<Command> onShellyGet(ShellyGet request) {
    logger.trace("ShellyPro3EM.onShellyGet()");

    request.response().tell(rpcGetDeviceInfo(request.remoteAddress()));

    return Behaviors.same();
  }

  /**
   * Handle the request to get the device's status
   * @param request Request for the Shelly device status
   * @return Same behavior
   */
  @Override
  protected @NotNull Behavior<Command> onStatusGet(@NotNull StatusGet request) {
    logger.trace("ShellyPro3EM.onStatusGet()");
    
    request.replyTo().tell(createStatus(request.remoteAddress()));
    
    return Behaviors.same();
  }

  /**
   * Handle the EM.GetStatus HTTP request
   * @param request Request to get the EM status
   * @return Same behavior
   */
  protected @NotNull Behavior<Command> onEmGetStatus(@NotNull EmGetStatus request) {
    logger.trace("ShellyPro3EM.onEmGetStatus()");
    
    if (request.id() == 0) {
      try {
        request.replyTo().tell(
              new EmGetStatusOrFailureResponse(
                    null, 
                    rpcEmGetStatus(getPowerFactorForRemoteAddress(request.remoteAddress()))));
      } catch (Exception e) {
        request.replyTo().tell(new EmGetStatusOrFailureResponse(e, null));
      }
    } else  {
      request.replyTo().tell(
            new EmGetStatusOrFailureResponse(
                  new NoSuchElementException("unknown EM with id " + request.id()),
                  null));
    } 
    
    return Behaviors.same();
  }

  /**
   * Handle the EM.GetConfig HTTP request
   * @param request Request to get the EM configuration
   * @return Same behavior
   */
  protected @NotNull Behavior<Command> onEmGetConfig(@NotNull EmGetConfig request) {
    logger.trace("ShellyPro3EM.onEmGetConfig()");

    if (request.id() == 0) {
      request.replyTo().tell(
            new EmGetConfigOrFailureResponse(
                  null,
                  rpcEmGetConfig()));
    } else  {
      request.replyTo().tell(
            new EmGetConfigOrFailureResponse(
                  new NoSuchElementException("unknown EM with id " + request.id()),
                  null));
    }

    return Behaviors.same();
  }

  /**
   * Handle the EMData.GetStatus HTTP request
   * @param request Request to get the EM data status
   * @return Same behavior
   */
  protected @NotNull Behavior<Command> onEmDataGetStatus(@NotNull EmDataGetStatus request) {
    logger.trace("ShellyPro3EM.onEmDataGetStatus()");

    if (request.id() == 0) {
      request.replyTo().tell(
            new EmDataGetStatusOrFailureResponse(
                  null,
                  rpcEmDataGetStatus()));
    } else  {
      request.replyTo().tell(
            new EmDataGetStatusOrFailureResponse(
                  new NoSuchElementException("unknown EM with id " + request.id()),
                  null));
    }

    return Behaviors.same();
  }

  /**
   * Handle the request to reset the device's data
   * @param request Request to reset the device's data
   * @return Same behavior
   */
  protected @NotNull Behavior<Command> onResetData(@NotNull ResetData request) {
    logger.trace("ShellyPro3EM.onResetData()");
    
    request.replyTo.tell(Done.getInstance());
    
    return Behaviors.same();
  }

  /**
   * Handle the "Script.List" request
   */
  protected @NotNull Behavior<Command> onScriptList(@NotNull ScriptList request) {
    logger.trace("ShellyPro3EM.onScriptList()");

    request.replyTo().tell(rpcScriptList(request.remoteAddress()));

    return Behaviors.same();
  }

  /**
   * Handle the Shelly.GetComponents request
   */
  protected @NotNull Behavior<Command> onShellyGetComponents(@NotNull ShellyGetComponents request) {
    logger.trace("ShellyPro3EM.onShellyGetComponents()");

    request.replyTo().tell(rpcShellyGetComponents(request.remoteAddress()));

    return Behaviors.same();
  }

  /**
   * Handle the Shelly.GetConfig request
   */
  protected @NotNull Behavior<Command> onShellyGetConfig(@NotNull ShellyGetConfig request) {
    logger.trace("ShellyPro3EM.onShellyGetConfig()");

    request.replyTo().tell(rpcShellyGetConfig(request.remoteAddress()));

    return Behaviors.same();
  }

  /**
   * Handle the 
   */
  protected @NotNull Behavior<Command> onShellyGetDeviceInfo(@NotNull ShellyGetDeviceInfo request) {
    logger.trace("ShellyPro3EM.onShellyGetDeviceInfo()");
    
    request.replyTo().tell(rpcGetDeviceInfo(request.remoteAddress()));
    
    return Behaviors.same();
  }

  /**
   * Handle the Shelly.GetStatus HTTP request
   * @param request Request to get the device's status
   * @return Same behavior               
   */
  protected @NotNull Behavior<Command> onShellyGetStatus(@NotNull ShellyGetStatus request) {
    logger.trace("ShellyPro3EM.onShellyGetStatus()");
    
    request.replyTo().tell(createStatus(request.remoteAddress()));
    
    return Behaviors.same();
  }

  /**
   * Handle the Shelly.Reboot HTTP request
   * @param request Request to get the device's status
   * @return Same behavior               
   */
  protected @NotNull Behavior<Command> onShellyReboot(@NotNull ShellyReboot request) {
    logger.trace("ShellyPro3EM.onShellyReboot()");

    request.replyTo().tell(rpcReboot(request.remoteAddress()));

    return Behaviors.same();
  }

  /**
   * Handle the Sys.GetConfig HTTP request
   * @param request Request to get the device's configuration
   * @return Same behavior
   */
  protected @NotNull Behavior<Command> onSysGetConfig(@NotNull SysGetConfig request) {
    logger.trace("ShellyPro3EM.onSysGetConfig()");
    
    request.replyTo().tell(rpcSysGetConfig(request.remoteAddress()));
    
    return Behaviors.same();
  }

  /**
   * Handle the Cloud.GetConfig HTTP request
   * @param request Request to get the device's configuration
   * @return Same behavior
   */
  protected @NotNull Behavior<Command> onCloudGetConfig(@NotNull CloudGetConfig request) {
    logger.trace("ShellyPro3EM.onCloudGetConfig()");

    request.replyTo().tell(rpcCloudGetConfig());

    return Behaviors.same();
  }

  /**
   * Handle the Cloud.SetConfig HTTP request
   * @param request Request to get the device's configuration
   * @return Same behavior
   */
  protected @NotNull Behavior<Command> onCloudSetConfig(@NotNull CloudSetConfig request) {
    logger.trace("ShellyPro3EM.onCloudSetConfig()");

    cloudSetConfig(request.config());

    request.replyTo().tell(new Rpc.CloudSetConfigResponse(true));

    return Behaviors.same();
  }
  
  /**
   * Handle the Ws.GetConfig HTTP request
   * @param request Request to get the device's configuration
   * @return Same behavior
   */
  protected @NotNull Behavior<Command> onWsGetConfig(@NotNull WsGetConfig request) {
    logger.trace("ShellyPro3EM.onWsGetConfig()");

    request.replyTo().tell(rpcWsGetConfig());

    return Behaviors.same();
  }

  /**
   * Handle the Ws.SetConfig HTTP request
   * @param request Request to get the device's configuration
   * @return Same behavior
   */
  protected @NotNull Behavior<Command> onWsSetConfig(@NotNull WsSetConfig request) {
    logger.trace("ShellyPro3EM.onWsSetConfig()");
    
    wsSetConfig(request.config());
    
    request.replyTo().tell(new Rpc.WsSetConfigResponse(true));
    
    return Behaviors.same();
  }

  /**
   * Handle an HTTP RPC request
   * @param request HTTP RPC request
   * @return Same behavior
   */
  protected Behavior<Command> onHttpRpcRequest(HttpRpcRequest request) {
    logger.trace("ShellyPro3EM.onRpcRequest()");

    request.replyTo().tell(
          createRpcResponse(
                request.remoteAddress(),
                request.request()));

    return Behaviors.same();
  }

  /**
   * Handle the notification that a websocket output connection was opened
   * @param message Notification of an opened websocket output connection
   * @return Same behavior
   */
  protected Behavior<Command> onWebsocketOutputOpened(WebsocketOutputOpened message) {
    logger.trace("ShellyPro3EM.onWebsocketOutputOpened()");

    logger.debug("outgoing websocket connection {} to {} created", message.connectionId(), message.remoteAddress());

    Pair<SourceQueueWithComplete<WebsocketProcessPendingEmGetStatusRequest>, UniqueKillSwitch> queueSwitchPair =
          Source.<WebsocketProcessPendingEmGetStatusRequest>queue(1, OverflowStrategy.dropHead())
                .viaMat(KillSwitches.single(), Keep.both())
                .throttle(1, minSamplePeriod)
                .to(Sink.actorRef(Adapter.toClassic(getContext().getSelf()), ThrottlingQueueClosed.INSTANCE))
                .run(getMaterializer());

    websocketConnections.put(
          message.connectionId(),
          WebsocketContext.create(
                message.remoteAddress(),
                message.sourceActor(),
                queueSwitchPair.second(),
                queueSwitchPair.first(),
                null));

    return Behaviors.same();
  }

  /**
   * Handle the notification that the throttling queue was closed
   * @param message Notification that the throttling queue was closed
   * @return Same behavior
   */
  protected Behavior<Command> onThrottlingQueueClosed(ThrottlingQueueClosed message) {
    logger.trace("ShellyPro3EM.onThrottlingQueueClosed()");
    return Behaviors.same();
  }

  /**
   * Handle a wrapped websocket input notification
   * @param message Wrapped websocket input notification
   * @return Same behavior
   */
  protected Behavior<Command> onWrappedWebsocketInputNotification(WrappedWebsocketInputNotification message) {
    logger.trace("ShellyPro3EM.onWrappedWebsocketInputNotification()");

    try {
      WebsocketInput.Notification notification = message.notification();

      if (notification instanceof WebsocketInput.NotifyMessageReceived messageReceived) {
        onWebsocketMessageReceived(messageReceived);
      } else if (notification instanceof WebsocketInput.NotifyOpened notifyOpened) {
        onWebsocketInputOpened(notifyOpened);
      } else if (notification instanceof WebsocketInput.NotifyClosed notifyClosed) {
        onWebsocketInputClosed(notifyClosed);
      } else if (notification instanceof WebsocketInput.NotifyFailed notifyFailed) {
        onWebsocketInputFailed(notifyFailed);
      } else {
        logger.error("unknown websocket input notification: {}", notification);
      }
    } catch (JsonProcessingException e) {
      logger.debug("websocket notification contains invalid JSON: {}", e.getMessage());
    } catch (IllegalArgumentException e) {
      logger.debug("illegal argument in websocket notification: {}", e.getMessage());
    } catch (Exception e) {
      logger.error("failure while processing websocket notification: {}", e.getMessage());
    }

    return Behaviors.same();
  }

  /**
   * Handle the notification that a websocket input connection was opened
   * @param message Notification of an opened websocket input connection
   */
  protected void onWebsocketInputOpened(WebsocketInput.NotifyOpened message) {
    logger.trace("ShellyPro3EM.onWebsocketInputOpened()");

    logger.debug("incoming websocket connection {} opened", message.connectionId());

    message.replyTo().tell(WebsocketInput.Ack.INSTANCE);
  }

  /**
   * Handle the notification that a websocket input connection was closed
   * @param message Notification of a closed websocket input connection
   */
  protected void onWebsocketInputClosed(WebsocketInput.NotifyClosed message) {
    logger.trace("ShellyPro3EM.onWebsocketInputClosed()");

    logger.debug("incoming websocket connection {} closed", message.connectionId());
    
    handleWebsocketClosed(message.connectionId());
  }

  /**
   * Handle the notification that a websocket input connection failed
   * @param message Notification of a failed websocket input connection
   */
  protected void onWebsocketInputFailed(WebsocketInput.NotifyFailed message) {
    logger.trace("ShellyPro3EM.onWebsocketInputFailed()");

    logger.debug("incoming websocket connection {} failed: {}", message.connectionId(), message.failure().getMessage());
    
    handleWebsocketClosed(message.connectionId());
  }
  
  protected void handleWebsocketClosed(@NotNull String connectionId) {
    WebsocketContext context = websocketConnections.remove(connectionId);
    if (context != null) {
      context.close();
    }

    if (Objects.equals(connectionId, outboundWebsocketConnectionId)) {
      outboundWebsocketConnectionId = null;
      outboundWebsocketAddress = null;

      if (outboundWebsocketConnectionIsEnabled()) {
        getContext().getSystem().scheduler().scheduleOnce(
              Duration.ofSeconds(5),
              () -> getContext().getSelf().tell(RetryOpenOutboundWebsocketConnection.INSTANCE),
              getContext().getExecutionContext());
      }
    }
  }

  /**
   * Handle an incoming websocket message
   * @param message Notification of an incoming websocket message
   */
  protected void onWebsocketMessageReceived(WebsocketInput.NotifyMessageReceived message) throws JsonProcessingException {
    logger.trace("ShellyPro3EM.onWebsocketMessageReceived()");

    message.replyTo().tell(WebsocketInput.Ack.INSTANCE);

    WebsocketContext websocketContext = websocketConnections.get(message.connectionId());
    if (websocketContext == null) {
      return;
    }

    String text;
    Message wsMessage = message.message();
    if (wsMessage.isText()) {
      text = wsMessage.asTextMessage().getStrictText();
    } else {
      text = wsMessage.asBinaryMessage().getStrictData().utf8String();
    }

    logger.trace("incoming websocket connection {} received message: {}", message.connectionId(), text);

    Rpc.Request request = Rpc.parseRequest(text);

    if ("EM.GetStatus".equals(request.method())) {
      websocketContext.handleEmGetStatusRequest(wsMessage, request);
    } else {
      processRpcRequest(message.remoteAddress(), request, wsMessage.isText(), websocketContext.getOutput());
    }
  }

  /**
   * Process the specified RPC request
   * @param request Incoming RPC request to process
   * @param createTextResponse Flag indicating whether to create a text or binary response
   * @param output Actor reference to the websocket output actor
   */
  protected void processRpcRequest(@NotNull InetAddress remoteAddress,
                                   @NotNull Rpc.Request request,
                                   boolean createTextResponse,
                                   @NotNull ActorRef<WebsocketOutput.Command> output) {
    logger.trace("Shelly.processRpcRequest()");

    Rpc.ResponseFrame response = createRpcResponse(remoteAddress, request);

    Message wsResponse;
    if (createTextResponse) {
      wsResponse = TextMessage.create(Rpc.responseToString(response));
    } else {
      wsResponse = BinaryMessage.create(ByteString.fromArrayUnsafe(Rpc.responseToBytes(response)));
    }

    output.tell(new WebsocketOutput.Send(wsResponse));
  }
  
  /**
   * Handle the notification of a successful outbound websocket connection
   * @param message Notification of a successful outbound websocket connection
   * @return Same behavior
   */
  protected Behavior<Command> onOutboundWebsocketConnectionOpened(OutboundWebsocketConnectionOpened message) {
    logger.trace("ShellyPro3EM.onOutboundWebsocketConnectionOpened()");

    logger.info("outbound websocket connection opened to {}", message.url());
    
    outboundWebsocketAddress = message.remoteAddress();
    outboundWebsocketConnectionId = message.connectionId();
    outboundWebsocketFailure = false;

    return Behaviors.same();
  }
  
  /**
   * Handle the notification of a failed outbound websocket connection
   * @param message Notification of a failed outbound websocket connection
   * @return Same behavior
   */
  protected Behavior<Command> onOutboundWebsocketConnectionFailed(OutboundWebsocketConnectionFailed message) {
    logger.trace("ShellyPro3EM.onOutboundWebsocketConnectionFailed()");

    if (!outboundWebsocketFailure) {
      logger.error("outbound websocket connection to {} failed: {}", message.url(), message.failure().getMessage());
      outboundWebsocketFailure = true;
    } else {
      logger.debug("outbound websocket connection to {} failed: {}", message.url(), message.failure().getMessage());
    }
    

    outboundWebsocketAddress = null;
    outboundWebsocketConnectionId = null;
    
    getContext().getSystem().scheduler().scheduleOnce(
          Duration.ofSeconds(15),
          () -> getContext().getSelf().tell(RetryOpenOutboundWebsocketConnection.INSTANCE),
          getContext().getExecutionContext());

    return Behaviors.same();
  }
  
  /**
   * Handle the notification to retry opening an outbound websocket connection
   * @param message Notification to retry opening an outbound websocket connection
   * @return Same behavior
   */
  protected Behavior<Command> onRetryOpenOutboundWebsocketConnection(RetryOpenOutboundWebsocketConnection message) {
    logger.trace("ShellyPro3EM.onRetryOpenOutboundWebsocketConnection()");

    startOutboundWebsocketConnection();
    
    return Behaviors.same();
  }
  
  protected Behavior<Command> onRetryStartUdpServer(RetryStartUdpServer message) {
    logger.trace("ShellyPro3EM.onRetryStartUdpServer()");
    
    startUdpServer();
    
    return Behaviors.same();
  }
  
  /**
   * Handle a wrapped UDP server notification
   * @param message Wrapped UDP server notification
   * @return Same behavior
   */
  protected Behavior<Command> onWrappedUdpServerNotification(WrappedUdpServerNotification message) {
    logger.trace("ShellyPro3EM.onWrappedUdpServerNotification()");

    try {
      UdpServer.Notification notification = message.notification();

      if (notification instanceof UdpServer.DatagramReceived datagramReceived) {
        datagramReceived.replyTo().tell(UdpServer.Ack.INSTANCE);
        onUdpServerDatagramReceived(datagramReceived);
      } else if (notification instanceof UdpServer.NotifyBindSucceeded notifyBindSucceeded) {
        logger.info("UDP server is listening on {}", notifyBindSucceeded.address());
      } else if (notification instanceof UdpServer.SourceInitialized sourceInitialized) {
        logger.debug("UDP server output {}", sourceInitialized.output());
        udpOutput = sourceInitialized.output();
      } else if (notification instanceof UdpServer.SinkInitialized sinkInitialized) {
        logger.debug("UDP server initialized");
        sinkInitialized.replyTo().tell(UdpServer.Ack.INSTANCE);
      } else if (notification instanceof UdpServer.SinkClosed) {
        logger.debug("UDP server sink closed");
        handleUdpServerClosed();
      } else if (notification instanceof UdpServer.SinkFailed sinkFailed) {
        logger.error("UDP server failed: {}", sinkFailed.failure().getMessage());
        handleUdpServerClosed();
      } else {
        logger.error("unknown UDP server notification: {}", notification);
        throw new RuntimeException("unknown UDP server notification: " + notification.getClass());
      }
    } catch (JsonProcessingException e) {
      logger.debug("UDP notification contains invalid JSON: {}", e.getMessage());
    } catch (IllegalArgumentException e) {
      logger.debug("illegal argument in UDP notification: {}", e.getMessage());
    } catch (Exception e) {
      logger.error("failure while processing UDP notification: {}", e.getMessage());
    }

    return Behaviors.same();
  }

  /**
   * Handle the event that the UDP server stream is closed 
   */
  private void handleUdpServerClosed() {
    udpOutput = null;
    
    logger.info("restarting UDP server in {} seconds ...", udpRestartBackoff);
    
    getContext().getSystem().scheduler().scheduleOnce(
          udpRestartBackoff,
          () -> getContext().getSelf().tell(RetryStartUdpServer.INSTANCE),
          getContext().getExecutionContext()
    );
  }

  /**
   * Handle the notification, that a UDP datagram was received
   * @param message Notification of a received UDP datagram
   */
  protected void onUdpServerDatagramReceived(UdpServer.DatagramReceived message) throws JsonProcessingException {
    logger.trace("ShellyPro3EM.onUdpServerDatagramReceived()");

    UdpClientContext udpClientContext = getUdpClientContext(message.datagram().remote());

    String text = message.datagram().data().utf8String();

    logger.trace("received udp datagram from {}: {}", message.datagram().remote(), text);

    Rpc.Request request = Rpc.parseRequest(text);

    if ("EM.GetStatus".equals(request.method())) {
      udpClientContext.handleEmGetStatusRequest(message.datagram(), request);
    } else {
      processUdpRpcRequest(message.datagram().remote(), request);
    }
  }

  /**
   * Handle the notification to process a pending EM.GetStatus request
   * @param message Notification to process a pending EM.GetStatus request
   * @return Same behavior
   */
  protected Behavior<Command> onUdpClientProcessPendingEmGetStatusRequest(UdpClientProcessPendingEmGetStatusRequest message) {
    logger.trace("ShellyPro3EM.onUdpClientProcessPendingEmGetStatusRequest()");

    UdpClientContext udpClientContext = message.udpClientContext();

    processUdpRpcRequest(udpClientContext.getRemote(), udpClientContext.getLastEmGetStatusRequest());

    return Behaviors.same();
  }

  /**
   * Process an RPC request received via UDP
   * @param remote Remote IP address
   * @param request Incoming RPC request to process
   */
  protected void processUdpRpcRequest(@NotNull InetSocketAddress remote,
                                      @NotNull Rpc.Request request) {
    logger.trace("ShellyPro3EM.processUdpRpcRequest()");
    
    if (udpOutput != null) {
      Rpc.ResponseFrame response = createRpcResponse(remote.getAddress(), request);
      udpOutput.tell(Datagram.create(ByteString.fromArrayUnsafe(Rpc.responseToBytes(response)), remote));
    }
  }


  /**
   * Handle the notification to process a pending EM.GetStatus request
   * @param message Notification to process a pending EM.GetStatus request
   * @return Same behavior
   */
  protected Behavior<Command> onProcessPendingEmGetStatusRequest(WebsocketProcessPendingEmGetStatusRequest message) {
    logger.trace("ShellyPro3EM.onProcessPendingEmGetStatusRequest()");

    processRpcRequest(
          message.websocketContext().getRemoteAddress(),
          message.websocketContext().getLastEmGetStatusRequest(),
          message.websocketMessage().isText(),
          message.websocketContext().getOutput());

    return Behaviors.same();
  }

  /**
   * Handle the event, that the power data has changed
   */
  @Override
  protected void eventPowerDataChanged() {
    if (outboundWebsocketConnectionIsOpen()) {
      WebsocketContext outboundWebsocketContext = websocketConnections.get(outboundWebsocketConnectionId);
      if (outboundWebsocketContext != null) {
        try {
          Rpc.NotificationFrame notification = new Rpc.NotificationFrame(
                getHostname(outboundWebsocketAddress),
                null,
                "NotifyStatus",
                new Rpc.EmGetStatusNotification(
                      MathUtils.round(Instant.now().toEpochMilli() / 1000.0, 2),
                      rpcEmGetStatus(1.0)
                )
          );

          outboundWebsocketContext.getOutput().tell(
                new WebsocketOutput.Send(TextMessage.create(Rpc.notificationToString(notification))));
        } catch (RpcException e) {
          logger.debug("RPC error: {}", e.getMessage());
        } catch (Exception e) {
          logger.error("unhandled exception: {}", e.getMessage());
        }
      }
    }
  }

  /**
   * Create the HTTP route of the device
   * @return HTTP route of the device
   */
  @Override
  protected Route createRoute() {
    HttpRoute httpRoute = new HttpRoute(
            LoggerFactory.getLogger(logger.getName() + ".http"),
            getContext().getSystem(),
            getMaterializer(),
            getContext().getSelf(),
            websocketInputNotificationAdapter);
    
    return httpRoute.createRoute();
  }

  @Override
  protected int getNumMeters() {
    return 3;
  }
  
  protected @NotNull Rpc.ResponseFrame createRpcResponse(@NotNull InetAddress remoteAddress,
                                                         @NotNull Rpc.Request request) {
    try {
      return new Rpc.ResponseFrame(
            request.id(), 
            getHostname(remoteAddress), 
            request.src(),
            createRpcResult(remoteAddress, request),
            null);
    } catch (RpcException rpcException) {
      return new Rpc.ResponseFrame(
            request.id(),
            getHostname(remoteAddress),
            request.src(),
            null,
            new Rpc.Error(rpcException.getCode(), rpcException.getMessage()));
    } catch (Exception e) {
      return new Rpc.ResponseFrame(
            request.id(),
            getHostname(remoteAddress),
            request.src(),
            null,
            new Rpc.Error(-32603, e.getMessage()));
    }
  }
   
  protected Rpc.Response createRpcResult(@NotNull InetAddress remoteAddress,
                                         @NotNull Rpc.Request request) throws RpcException {
    return switch (request.method().toLowerCase()) {
      case "cloud.getconfig" -> rpcCloudGetConfig();
      case "cloud.setconfig" -> rpcCloudSetConfig((Rpc.CloudSetConfig) request);
      case "em.getconfig" -> rpcEmGetConfig();
      case "em.getstatus" -> rpcEmGetStatus(getPowerFactorForRemoteAddress(remoteAddress));
      case "emdata.getstatus" -> rpcEmDataGetStatus();
      case "script.list" -> rpcScriptList(remoteAddress);
      case "shelly.getcomponents" -> rpcShellyGetComponents(remoteAddress);
      case "shelly.getconfig" -> rpcShellyGetConfig(remoteAddress);
      case "shelly.getstatus" -> rpcShellyGetStatus(remoteAddress);
      case "shelly.getdeviceinfo" -> rpcGetDeviceInfo(remoteAddress);
      case "shelly.reboot" -> rpcReboot(remoteAddress);
      case "sys.getconfig" -> rpcSysGetConfig(remoteAddress);
      case "ws.getconfig" -> rpcWsGetConfig();
      case "ws.setconfig" -> rpcWsSetConfig((Rpc.WsSetConfig) request);
      default -> rpcUnknownMethod(request);
    };
  }

  /**
   * Handle the Ws.SetConfig RPC request
   * @param request Request to set the websocket configuration
   * @return Response to the request
   */
  private Rpc.CloudSetConfigResponse rpcCloudSetConfig(@NotNull Rpc.CloudSetConfig request) {
    logger.trace("ShellyPro3EM.rpcCloudSetConfig()");
    
    logger.debug("setting cloud config {}", request);

    if (request.params() != null) {
      cloudSetConfig(request.params());
    }

    return new Rpc.CloudSetConfigResponse(false);
  }

  private Rpc.ScriptListResponse rpcScriptList(@NotNull InetAddress remoteAddress) {
    logger.trace("Shelly.rpcScriptList()");
    return new Rpc.ScriptListResponse(Collections.emptyList());
  }

  private Rpc.ShellyGetComponentsResponse rpcShellyGetComponents(@NotNull InetAddress remoteAddress) {
    logger.trace("Shelly.rpcShellyGetComponents()");
    return createComponents(remoteAddress);
  }

  private ShellyConfig rpcShellyGetConfig(@NotNull InetAddress remoteAddress) {
    logger.trace("Shelly.rpcShellyGetConfig()");
    return createConfig(remoteAddress);
  }

  private Rpc.Response rpcShellyGetStatus(@NotNull InetAddress remoteAddress) {
    logger.trace("Shelly.rpcShellyGetStatus()");
    
    Rpc.ShellyGetStatusResponse response = new Rpc.ShellyGetStatusResponse(
          createWiFiStatus(),
          createCloudStatus(),
          createMqttStatus(),
          createSysStatus(),
          createTempStatus(),
          createEMeterStatus(remoteAddress)
    );
    
    logger.trace("ShellyPro3EM.rpcShellyGetStatus(): {}", response);

    return response;
  }

  private Rpc.GetDeviceInfoResponse rpcGetDeviceInfo(@NotNull InetAddress remoteAddress) {
    logger.trace("Shelly.rpcGetDeviceInfo()");

    Rpc.GetDeviceInfoResponse response = new Rpc.GetDeviceInfoResponse(
          null,
          getHostname(remoteAddress),
          getMac(remoteAddress),
          1,
          "SPEM-003CEBEU",
          2,
          "20241011-114455/1.4.4-g6d2a586",
          "1.4.4",
          "Pro3EM",
          false,
          Rpc.RpcStringOrNull.of(null),
          "triphase"
    );
    
    logger.trace("ShellyPro3EM.rpcGetDeviceInfo(): {}", response);
    
    return response;
  }
  
  private Rpc.ShellyRebootResponse rpcReboot(@NotNull InetAddress ignore) {
    logger.trace("ShellyPro3EM.rpcReboot()");
    return new Rpc.ShellyRebootResponse();
  }

  /**
   * Get the cloud configuration
   * @return Cloud configuration
   */
  private Rpc.CloudGetConfigResponse rpcCloudGetConfig() {
    logger.trace("ShellyPro3EM.rpcCloudGetConfig()");
    return cloudConfig;
  }
  
  
  private Rpc.EmGetConfigResponse rpcEmGetConfig() {
    logger.trace("ShellyPro3EM.rpcEmGetConfig()");
    
    return new Rpc.EmGetConfigResponse(
          0,
          new Rpc.RpcStringOrNull(null),
          "active_energy",
          "a",
          true,
          new Rpc.ReverseConfig(null, null, null),
          "120A"
    );
  }
  

  private Rpc.EmGetStatusResponse rpcEmGetStatus(double factor) throws RpcException {
    logger.trace("ShellyPro3EM.rpcEmGetStatus()");
    
    PowerData powerPhase0 = getPowerPhase0();
    PowerData powerPhase1 = getPowerPhase1();
    PowerData powerPhase2 = getPowerPhase2();
    
    if (powerPhase0 == null && powerPhase1 == null && powerPhase2 == null) {
      throw new RpcException(RpcError.ERROR_NO_POWER_DATA, RpcError.ERROR_NO_POWER_DATA_MSG);
    }
    
    if (powerPhase0 == null) {
      powerPhase0 = getPowerPhase0OrDefault();
    }
    if (powerPhase1 == null) {
      powerPhase1 = getPowerPhase1OrDefault();
    }
    if (powerPhase2 == null) {
      powerPhase2 = getPowerPhase2OrDefault();
    }
    
    return new Rpc.EmGetStatusResponse(
          0,
          MathUtils.round(powerPhase0.current() * factor, 2),
          MathUtils.round(powerPhase0.voltage(), 2),
          MathUtils.round(powerPhase0.power() * factor, 2),
          MathUtils.round(powerPhase0.apparentPower() * factor, 2),
          powerPhase0.powerFactor(),
          powerPhase0.frequency(),
          null,
          MathUtils.round(powerPhase1.current() * factor, 2),
          MathUtils.round(powerPhase1.voltage(), 2),
          MathUtils.round(powerPhase1.power() * factor, 2),
          MathUtils.round(powerPhase1.apparentPower() * factor, 2),
          powerPhase1.powerFactor(),
          powerPhase1.frequency(),
          null,
          MathUtils.round(powerPhase2.current() * factor, 2),
          MathUtils.round(powerPhase2.voltage(), 2),
          MathUtils.round(powerPhase2.power() * factor, 2),
          MathUtils.round(powerPhase2.apparentPower() * factor, 2),
          powerPhase2.powerFactor(),
          powerPhase2.frequency(),
          null,
          null,
          null,
          MathUtils.round(
                (powerPhase0.current() + powerPhase1.current() + powerPhase2.current()) * factor, 
                2),
          MathUtils.round(
                (powerPhase0.power() + powerPhase1.power() + powerPhase2.power()) * factor, 
                2),
          MathUtils.round(
                (powerPhase0.apparentPower() + powerPhase1.apparentPower() + powerPhase2.apparentPower()) * factor, 
                2),
          null,
          null
    );
  }

  private Rpc.EmDataGetStatusResponse rpcEmDataGetStatus() {
    logger.trace("ShellyPro3EM.rpcEmDataGetStatus()");

    return new Rpc.EmDataGetStatusResponse(
          0,
          getEnergyPhase0().totalConsumption(),
          getEnergyPhase0().totalProduction(),
          getEnergyPhase1().totalConsumption(),
          getEnergyPhase1().totalProduction(),
          getEnergyPhase2().totalConsumption(),
          getEnergyPhase2().totalProduction(),
          MathUtils.round(getEnergyPhase0().totalConsumption() + getEnergyPhase1().totalConsumption() + getEnergyPhase2().totalConsumption(), 2),
          MathUtils.round(getEnergyPhase0().totalProduction() + getEnergyPhase1().totalProduction() + getEnergyPhase2().totalProduction(), 2),
          null);
  }
  
  private Rpc.SysGetConfigResponse rpcSysGetConfig(@NotNull InetAddress remoteAddress) {
    logger.trace("ShellyPro3EM.rpcSysGetConfig()");
    
    return new Rpc.SysGetConfigResponse(
          new Rpc.Device(
                getHostname(remoteAddress),
                getMac(remoteAddress),
                getConfig().getString("fw"),
                false,
                "",
                false),
          new Rpc.Location(
                TimeZone.getDefault().getID(),
                54.306f,
                9.663f),
          new Rpc.Debug(
                new Rpc.Mqtt(false),
                new Rpc.Websocket(false),
                new Rpc.Udp(new Rpc.RpcNull())
          ),
          new Rpc.UiData(),
          new Rpc.RpcUdp(
                null,
                getUdpPort() > 0 ? getUdpPort() : null),
          new Rpc.Sntp(
                "pool.ntp.org"
          ),
          10
    );
  }

  /**
   * Get the outgoing websocket configuration
   * @return Outgoing websocket configuration
   */
  private Rpc.WsGetConfigResponse rpcWsGetConfig() {
    logger.trace("ShellyPro3EM.rpcWsGetConfig()");
    return wsConfig;
  }

  /**
   * Handle the Ws.SetConfig RPC request
   * @param request Request to set the websocket configuration
   * @return Response to the request
   */
  private Rpc.WsSetConfigResponse rpcWsSetConfig(@NotNull Rpc.WsSetConfig request) {
    logger.trace("ShellyPro3EM.rpcWsSetConfig()");
    
    if (request.params() != null) {
      wsSetConfig(request.params());
    }
    
    return new Rpc.WsSetConfigResponse(true);
  }

  /**
   * Set the websocket configuration
   * @param params Configuration to set
   */
  protected void cloudSetConfig(@NotNull Rpc.CloudSetConfigParams params) {
    logger.trace("ShellyPro3EM.cloudSetConfig()");

    Rpc.CloudSetConfigParamsValues config = params.config();
    if (config != null) {
      if (config.enable() != null) {
        cloudConfig = cloudConfig.withEnable(config.enable());
      }
      cloudConfig = cloudConfig.withServer(config.server());
    }
  }
  
  /**
   * Set the websocket configuration
   * @param config Configuration to set
   */
  protected void wsSetConfig(@NotNull Rpc.WsSetConfigParams config) {
    logger.trace("ShellyPro3EM.wsSetConfig()");
    
    if (config.enable() != null) {
      wsConfig = wsConfig.withEnable(config.enable());
    }
    wsConfig = wsConfig.withServer(config.server());
    wsConfig = wsConfig.withSslCa(config.ssl_ca());
    
    startOutboundWebsocketConnection();
  }
  
  protected boolean outboundWebsocketConnectionIsEnabled() {
    return wsConfig.enable() != null 
          && wsConfig.enable() 
          && wsConfig.server().value() != null
          && !StringUtils.isAllBlank(wsConfig.server().value());
  }
  
  protected boolean outboundWebsocketConnectionIsClosed() {
    return outboundWebsocketConnectionId == null;
  }

  protected boolean outboundWebsocketConnectionIsOpen() {
    return outboundWebsocketConnectionId != null;
  }

  /**
   * Create the device's components list
   * @return Device's components list
   */
  private Rpc.ShellyGetComponentsResponse createComponents(@NotNull InetAddress remoteAddress) {
    return new Rpc.ShellyGetComponentsResponse(
          List.of(
                new Rpc.Component("em:0", null, null),
                new Rpc.Component("emdata:0", null, null)
          ),
          1,
          0,
          2
    );
  }

  /**
   * Create the device's status
   * @return Device's status
   */
  private ShellyConfig createConfig(@NotNull InetAddress remoteAddress) {
    return new ShellyConfig(
          new Rpc.BleGetConfigResponse(
                false, 
                new Rpc.BleGetConfigResponseRpc(true),
                new Rpc.BleGetConfigResponseObserver(false)),
          rpcCloudGetConfig(),
          rpcEmGetConfig(),
          rpcSysGetConfig(remoteAddress),
          createWiFiStatus(),
          rpcWsGetConfig()
    );
  }

  /**
   * Create the device's status
   * @return Device's status
   */
  private Status createStatus(@NotNull InetAddress remoteAddress) {
    double clientPowerFactor = getPowerFactorForRemoteAddress(remoteAddress);
    
    PowerData powerPhase0 = getPowerPhase0OrDefault();
    PowerData powerPhase1 = getPowerPhase1OrDefault();
    PowerData powerPhase2 = getPowerPhase2OrDefault();
    
    return new Status(
          createWiFiStatus(),
          createCloudStatus(),
          createMqttStatus(),
          getTime(),
          Instant.now().getEpochSecond(),
          1,
          false,
          getMac(remoteAddress),
          50648,
          38376,
          32968,
          233681,
          174194,
          getUptime(),
          28.08,
          false,
          createTempStatus(),
          createEMeterStatus(remoteAddress),
          (powerPhase0.power() + powerPhase1.power() + powerPhase2.power()) * clientPowerFactor,
          true);
  }
  
  private List<Rpc.EMeterStatus> createEMeterStatus(@NotNull InetAddress remoteAddress) {
    double clientPowerFactor = getPowerFactorForRemoteAddress(remoteAddress);

    PowerData powerPhase0 = getPowerPhase0OrDefault();
    PowerData powerPhase1 = getPowerPhase1OrDefault();
    PowerData powerPhase2 = getPowerPhase2OrDefault();
    
    return List.of(
          Rpc.EMeterStatus.of(powerPhase0, clientPowerFactor, getEnergyPhase0()),
          Rpc.EMeterStatus.of(powerPhase1, clientPowerFactor, getEnergyPhase1()),
          Rpc.EMeterStatus.of(powerPhase2, clientPowerFactor, getEnergyPhase2()));
  }
  
  private Rpc.Response rpcUnknownMethod(Rpc.Request request) {
    logger.error("ShellyPro3EM.rpcUnknownMethod()");
    throw new IllegalArgumentException("unhandled RPC method '" + request.method() + "'");
  }

  /**
   * Start the output websocket connection
   */
  protected void startOutboundWebsocketConnection() {
    logger.trace("ShellyPro3EM.startOutboundWebsocketConnection()");
    
      if (outboundWebsocketConnectionIsEnabled() && outboundWebsocketConnectionIsClosed()) {
        String remoteUrl =  wsConfig.server().value();
        try {
          String connectionId = UUID.randomUUID().toString();
          
          Logger logger = LoggerFactory.getLogger("uni-meter.output.outbound-websocket");
      
          URI uri = URI.create(remoteUrl);
      
          InetAddress remoteAddress;
          try {
            remoteAddress = InetAddress.getByName(uri.getHost());
          } catch (Exception e) {
            getContext().getSelf().tell(new OutboundWebsocketConnectionFailed(remoteUrl, e));
            return;
          }
          
          final InetAddress finalRemoteAddress = remoteAddress;
      
          Source<Message, NotUsed> websocketSource = WebsocketOutput.createSource(
                logger,
                getMaterializer(),
                (sourceActor) -> getContext().getSelf().tell(
                      new Shelly.WebsocketOutputOpened(connectionId, finalRemoteAddress, sourceActor))
          );
      
          Sink<Message,NotUsed> websocketSink = WebsocketInput.createSink(
                logger,
                connectionId,
                remoteAddress,
                getMaterializer(),
                websocketInputNotificationAdapter
          );
      
          final Flow<Message, Message, NotUsed> flow =
                Flow.fromSinkAndSourceMat(websocketSink, websocketSource, Keep.left());
      
          final Pair<CompletionStage<WebSocketUpgradeResponse>, NotUsed> pair =
                Http.get(getContext().getSystem()).singleWebSocketRequest(
                      WebSocketRequest.create(remoteUrl), flow, getMaterializer());
      
          pair.first().whenComplete((upgrade, failure) -> {
            if (failure != null) {
              getContext().getSelf().tell(new OutboundWebsocketConnectionFailed(remoteUrl, failure));
            } else {
              if (upgrade.response().status().equals(StatusCodes.SWITCHING_PROTOCOLS)) {
                getContext().getSelf().tell(new OutboundWebsocketConnectionOpened(remoteUrl, remoteAddress, connectionId));
              } else {
                getContext().getSelf().tell(new OutboundWebsocketConnectionFailed(
                      remoteUrl,
                      new IllegalStateException("unexpected response status: " + upgrade.response().status())));
              }
            }
          });
        } catch (Exception e) {
          getContext().getSelf().tell(
                new OutboundWebsocketConnectionFailed(remoteUrl != null ? remoteUrl : "<unknown>", e));
        }
      }
  }

  /**
   * Register the Shelly in mDNS
   */
  protected void registerMDns() {
    logger.trace("Shelly.registerMDns()");
    
    getMdnsRegistrator().tell(
          new MDnsRegistrator.RegisterService(
                "_http",
                getDefaultHostname(),
                getBindPort(),
                Map.of(
                    "gen", "2",
                    "id", getDefaultHostname(),
                    "arch", "esp8266",
                    "fw_id", getConfig().getString("fw")
                )
          )
    );
  }

  /**
   * Start the UDP server if configured
   */
  protected void startUdpServer() {
    logger.trace("Shelly.startUdpServer()");

    if (udpPort > 0) {
      final InetSocketAddress bindAddress = new InetSocketAddress(udpInterface, udpPort);

      UdpServer.createServer(
            LoggerFactory.getLogger(logger.getName() + ".udp-server"),
            getContext().getSystem(),
            getMaterializer(),
            bindAddress,
            udpServerNotificationAdapter);
    }
  }

  /**
   * Lookup the UDP client context for the specified remote address
   * @param remote Remote address
   * @return UDP client context
   */
  protected @NotNull UdpClientContext getUdpClientContext(@NotNull InetSocketAddress remote) {
    logger.trace("Shelly.getUdpClientContext()");

    UdpClientContext udpClientContext = udpClients.get(remote);
    if (udpClientContext != null) {
      return udpClientContext;
    }

    Pair<SourceQueueWithComplete<UdpClientProcessPendingEmGetStatusRequest>, UniqueKillSwitch> queueSwitchPair =
          Source.<UdpClientProcessPendingEmGetStatusRequest>queue(1, OverflowStrategy.dropHead())
                .viaMat(KillSwitches.single(), Keep.both())
                .throttle(1, minSamplePeriod)
                .to(Sink.actorRef(Adapter.toClassic(getContext().getSelf()), ThrottlingQueueClosed.INSTANCE))
                .run(getMaterializer());

    udpClientContext = UdpClientContext.of(remote, queueSwitchPair.second(), queueSwitchPair.first());

    udpClients.put(remote, udpClientContext);

    // Cleanup unused UDP client contexts
    Set<InetSocketAddress> toToRemove = new HashSet<>();
    for (Map.Entry<InetSocketAddress, UdpClientContext> entry : udpClients.entrySet()) {
      if (Duration.between(entry.getValue().getLastEmGetStatusRequestTime(), Instant.now()).getSeconds() > 60) {
        toToRemove.add(entry.getKey());
      }
    }

    for (InetSocketAddress key : toToRemove) {
      UdpClientContext context = udpClients.remove(key);
      if (context != null) {
        context.close();
      }
    }

    return udpClientContext;
  }
  
  public record ScriptList(
        @JsonProperty("remoteAddress") InetAddress remoteAddress,
        @JsonProperty("replyTo") ActorRef<Rpc.ScriptListResponse> replyTo
  ) implements Command {}

  public record ShellyGetComponents(
        @JsonProperty("remoteAddress") InetAddress remoteAddress,
        @JsonProperty("replyTo") ActorRef<Rpc.ShellyGetComponentsResponse> replyTo
  ) implements Command {}

  public record ShellyGetConfig(
        @JsonProperty("remoteAddress") InetAddress remoteAddress,
        @JsonProperty("replyTo") ActorRef<ShellyPro3EM.ShellyConfig> replyTo
  ) implements Command {}

  public record ShellyGetDeviceInfo(
        @JsonProperty("remoteAddress") InetAddress remoteAddress,
        @JsonProperty("replyTo") ActorRef<Rpc.GetDeviceInfoResponse> replyTo
  ) implements Command {}

  public record ShellyGetStatus(
        @JsonProperty("remoteAddress") InetAddress remoteAddress,
        @JsonProperty("replyTo") ActorRef<Shelly.Status> replyTo
  ) implements Command {}

  public record ShellyReboot(
        @JsonProperty("remoteAddress") InetAddress remoteAddress,
        @JsonProperty("delayMs") int delayMs,
        @JsonProperty("replyTo") ActorRef<Rpc.ShellyRebootResponse> replyTo
  ) implements Command {}

  public record SysGetConfig(
        @JsonProperty("remoteAddress") InetAddress remoteAddress,
        @JsonProperty("replyTo") ActorRef<Rpc.SysGetConfigResponse> replyTo
  ) implements Command {}

  public record CloudGetConfig(
        @JsonProperty("replyTo") ActorRef<Rpc.CloudGetConfigResponse> replyTo
  ) implements Command {}

  public record CloudSetConfig(
        @JsonProperty("config") Rpc.CloudSetConfigParams config,
        @JsonProperty("replyTo") ActorRef<Rpc.CloudSetConfigResponse> replyTo
  ) implements Command {}
  
  public record EmGetStatus(
        @JsonProperty("remoteAddress") InetAddress remoteAddress,
        @JsonProperty("id") int id,
        @JsonProperty("replyTo") ActorRef<EmGetStatusOrFailureResponse> replyTo
  ) implements Command {}
  
  public record EmGetStatusOrFailureResponse(
        @JsonProperty("failure") Exception failure,
        @JsonProperty("status") Rpc.EmGetStatusResponse status
  ) {}

  public record EmGetConfig(
        @JsonProperty("id") int id,
        @JsonProperty("replyTo") ActorRef<EmGetConfigOrFailureResponse> replyTo
  ) implements Command {}

  public record EmGetConfigOrFailureResponse(
        @JsonProperty("failure") RuntimeException failure,
        @JsonProperty("status") Rpc.EmGetConfigResponse status
  ) {}

  public record EmDataGetStatus(
        @JsonProperty("remoteAddress") InetAddress remoteAddress,
        @JsonProperty("id") int id,
        @JsonProperty("replyTo") ActorRef<EmDataGetStatusOrFailureResponse> replyTo
  ) implements Command {}
  
  public record EmDataGetStatusOrFailureResponse(
        @JsonProperty("failure") RuntimeException failure,
        @JsonProperty("status") Rpc.EmDataGetStatusResponse status
  ) {}

  public record WsGetConfig(
        @JsonProperty("replyTo") ActorRef<Rpc.WsGetConfigResponse> replyTo
  ) implements Command {}
  
  public record WsSetConfig(
        @JsonProperty("config") Rpc.WsSetConfigParams config,
        @JsonProperty("replyTo") ActorRef<Rpc.WsSetConfigResponse> replyTo
  ) implements Command {}

  public record ResetData(
        @NotNull ActorRef<Done> replyTo
  ) implements Command {}
  
  public record OutboundWebsocketConnectionFailed(
        @NotNull String url,
        @NotNull Throwable failure
  ) implements Command {}

  public record OutboundWebsocketConnectionOpened(
        @NotNull String url,
        @NotNull InetAddress remoteAddress,
        @NotNull String connectionId
  ) implements Command {}
  
  public enum RetryOpenOutboundWebsocketConnection implements Command {
    INSTANCE
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ShellyComponents(
        @JsonProperty("components") List<Rpc.Component> component
  ) implements Rpc.Response {}
  
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ShellyConfig(
        @JsonProperty("ble") Rpc.BleGetConfigResponse ble,
        @JsonProperty("cloud") Rpc.CloudGetConfigResponse cloud,
        @JsonProperty("em:0") Rpc.EmGetConfigResponse em0,
        @JsonProperty("sys") Rpc.SysGetConfigResponse sys,
        @JsonProperty("wifi_sta") Rpc.WiFiStatus wifi_sta,
        @JsonProperty("ws") Rpc.WsGetConfigResponse ws
  ) implements Rpc.Response {}
  
  public enum RetryStartUdpServer implements Command {
    INSTANCE
  }

  @Getter
  public static class Status extends Shelly.Status {
    private final List<Rpc.EMeterStatus> emeters;
    private final double total_power;
    private final boolean fs_mounted;
    
    public Status(@NotNull Rpc.WiFiStatus wifi_sta,
                  @NotNull Rpc.CloudStatus cloud,
                  @NotNull Rpc.MqttStatus mqtt,
                  @NotNull String time,
                  long unixtime,
                  int serial,
                  boolean has_update,
                  String mac,
                  int ram_total,
                  int ram_free,
                  int ram_lwm,
                  int fs_size,
                  int fs_free,
                  long uptime,
                  double temperature,
                  boolean overtemperature,
                  @NotNull Rpc.TempStatus temp,
                  @NotNull List<Rpc.EMeterStatus> emeters,
                  double total_power,
                  boolean fs_mounted) {
      super(wifi_sta, cloud, mqtt, time, unixtime, serial, has_update, mac, ram_total, ram_free, ram_lwm, fs_size, 
            fs_free, uptime, temperature, overtemperature, temp);
      this.emeters = emeters;
      this.total_power = total_power;
      this.fs_mounted = fs_mounted;
    }
  }

}
