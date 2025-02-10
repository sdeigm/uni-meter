package com.deigmueller.uni_meter.output.device.shelly;

import com.deigmueller.uni_meter.application.UdpServer;
import com.deigmueller.uni_meter.application.UniMeter;
import com.deigmueller.uni_meter.application.WebsocketInput;
import com.deigmueller.uni_meter.application.WebsocketOutput;
import com.deigmueller.uni_meter.common.shelly.Rpc;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.typesafe.config.Config;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Adapter;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.apache.pekko.http.javadsl.model.ws.BinaryMessage;
import org.apache.pekko.http.javadsl.model.ws.Message;
import org.apache.pekko.http.javadsl.model.ws.TextMessage;
import org.apache.pekko.http.javadsl.server.Route;
import org.apache.pekko.japi.Pair;
import org.apache.pekko.stream.KillSwitches;
import org.apache.pekko.stream.OverflowStrategy;
import org.apache.pekko.stream.UniqueKillSwitch;
import org.apache.pekko.stream.connectors.udp.Datagram;
import org.apache.pekko.stream.javadsl.Keep;
import org.apache.pekko.stream.javadsl.Sink;
import org.apache.pekko.stream.javadsl.Source;
import org.apache.pekko.stream.javadsl.SourceQueueWithComplete;
import org.apache.pekko.util.ByteString;

import org.jetbrains.annotations.NotNull;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Getter(AccessLevel.PROTECTED)
@Setter(AccessLevel.PROTECTED)
public abstract class Shelly extends OutputDevice {
  // Instance members
  private final ActorRef<WebsocketInput.Notification> websocketInputNotificationAdapter =
        getContext().messageAdapter(WebsocketInput.Notification.class, WrappedWebsocketInputNotification::new);
  private final ActorRef<UdpServer.Notification> udpServerNotificationAdapter =
        getContext().messageAdapter(UdpServer.Notification.class, WrappedUdpServerNotification::new);
  
  private final Map<String, WebsocketContext> websocketConnections = new HashMap<>();
  private final Map<InetSocketAddress, UdpClientContext> udpClients = new HashMap<>();
  private ActorRef<Datagram> udpOutput = null;

  private final Instant startTime = Instant.now();
  private final String bindInterface = getConfig().getString("interface");
  private final int bindPort = getConfig().getInt("port");
  private final int udpPort = getConfig().getInt("udp-port");
  private final String udpInterface = getConfig().getString("udp-interface");
  private final String mac = getConfig().getString("device.mac");
  private final String hostname = getConfig().getString("device.hostname");
  private final Duration minSamplePeriod = getConfig().getDuration("min-sample-period");
  
  private Settings settings = new Settings(getConfig());
  
  protected Shelly(@NotNull ActorContext<Command> context,
                   @NotNull ActorRef<UniMeter.Command> controller,
                   @NotNull Config config) {
    super(context, controller, config);
    
    startUdpServer();
  }

  /**
   * Create the actor's ReceiveBuilder
   * @return The actor's ReceiveBuilder
   */
  @Override
  public ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onMessage(ShellyGet.class, this::onShellyGet)
          .onMessage(SettingsGet.class, this::onSettingsGet)
          .onMessage(StatusGet.class, this::onStatusGet)
          .onMessage(HttpRpcRequest.class, this::onHttpRpcRequest)
          .onMessage(WebsocketProcessPendingEmGetStatusRequest.class, this::onProcessPendingEmGetStatusRequest)
          .onMessage(WebsocketOutputOpened.class, this::onWebsocketOutputOpened)
          .onMessage(ThrottlingQueueClosed.class, this::onThrottlingQueueClosed)
          .onMessage(UdpClientProcessPendingEmGetStatusRequest.class, this::onUdpClientProcessPendingEmGetStatusRequest)
          .onMessage(WrappedWebsocketInputNotification.class, this::onWrappedWebsocketInputNotification)
          .onMessage(WrappedUdpServerNotification.class, this::onWrappedUdpServerNotification);
  }

  /**
   * Handle an HTTP GET request for the Shelly device information
   * @param request Request for the Shelly device information
   * @return Same behavior
   */
  protected Behavior<Command> onShellyGet(ShellyGet request) {
    logger.trace("Shelly.onShellyGet()");

    request.response.tell(
          new ShellyInfo(
                settings.getDevice().getType(),
                settings.getDevice().getMac(),
                settings.getLogin().isEnabled(),
                settings.getFw(),
                settings.isDiscoverable(),
                1,
                getNumOutputs(),
                getNumMeters()));

    return Behaviors.same();
  }

  /**
   * Handle an HTTP GET request for the Shelly device settings
   * @param request Request for the Shelly device settings
   * @return Same behavior
   */
  protected Behavior<Command> onSettingsGet(SettingsGet request) {
    logger.trace("Shelly.onSettingsGet()");

    request.replyTo().tell(settings);

    return Behaviors.same();
  }

  /**
   * Handle an HTTP GET request for the Shelly device status
   * @param request Request for the Shelly device status
   * @return Same behavior
   */
  protected Behavior<Command> onStatusGet(StatusGet request) {
    logger.trace("Shelly.onStatusGet()");

    request.replyTo().tell(
          new Status(
                createWiFiStatus(),
                createCloudStatus(),
                createMqttStatus(),
                getTime(),
                Instant.now().getEpochSecond(),
                1,
                false,
                getMac(),
                50648,
                38376,
                32968,
                233681,
                174194,
                getUptime(),
                28.08,
                false,
                createTempStatus()));

    return Behaviors.same();
  }
  
  /**
   * Handle an HTTP RPC request
   * @param request HTTP RPC request
   * @return Same behavior
   */
  protected Behavior<Command> onHttpRpcRequest(HttpRpcRequest request) {
    logger.trace("Shelly.onRpcRequest()");
    
    request.replyTo().tell(createRpcResponse(request.request()));

    return Behaviors.same();
  }
  
  protected Behavior<Command> onWebsocketOutputOpened(WebsocketOutputOpened message) {
    logger.trace("Shelly.onWebsocketOutputOpened()");

    logger.debug("outgoing websocket connection {} created", message.connectionId());

    Pair<SourceQueueWithComplete<WebsocketProcessPendingEmGetStatusRequest>,UniqueKillSwitch> queueSwitchPair =
          Source.<WebsocketProcessPendingEmGetStatusRequest>queue(1, OverflowStrategy.dropHead())
                .viaMat(KillSwitches.single(), Keep.both())
                .throttle(1, minSamplePeriod)
                .to(Sink.actorRef(Adapter.toClassic(getContext().getSelf()), ThrottlingQueueClosed.INSTANCE))
                .run(getMaterializer());
    
    websocketConnections.put(
          message.connectionId(), 
          WebsocketContext.create(message.sourceActor(), queueSwitchPair.second(), queueSwitchPair.first(), null));
    
    return Behaviors.same();
  }
  
  protected Behavior<Command> onThrottlingQueueClosed(ThrottlingQueueClosed message) {
    logger.trace("Shelly.onThrottlingQueueClosed()");
    return Behaviors.same();
  }

  /**
   * Handle a wrapped websocket input notification
   * @param message Wrapped websocket input notification
   * @return Same behavior
   */
  protected Behavior<Command> onWrappedWebsocketInputNotification(WrappedWebsocketInputNotification message) {
    logger.trace("Shelly.onWrappedWebsocketInputNotification()");
    
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
    logger.trace("Shelly.onWebsocketInputOpened()");

    logger.debug("incoming websocket connection {} opened", message.connectionId());

    message.replyTo().tell(WebsocketInput.Ack.INSTANCE);
  }

  /**
   * Handle the notification that a websocket input connection was closed
   * @param message Notification of a closed websocket input connection
   */
  protected void onWebsocketInputClosed(WebsocketInput.NotifyClosed message) {
    logger.trace("Shelly.onWebsocketInputClosed()");
    
    logger.debug("incoming websocket connection {} closed", message.connectionId());
    WebsocketContext context = websocketConnections.remove(message.connectionId());
    if (context != null) {
      context.close();
    }
  }

  /**
   * Handle the notification that a websocket input connection failed
   * @param message Notification of a failed websocket input connection
   */
  protected void onWebsocketInputFailed(WebsocketInput.NotifyFailed message) {
    logger.trace("Shelly.onWebsocketInputFailed()");

    logger.error("incoming websocket connection {} failed: {}", message.connectionId(), message.failure().getMessage());
    WebsocketContext context = websocketConnections.remove(message.connectionId());
    if (context != null) {
      context.close();
    }
  }

  /**
   * Handle an incoming websocket message
   * @param message Notification of an incoming websocket message
   */
  protected void onWebsocketMessageReceived(WebsocketInput.NotifyMessageReceived message) {
    logger.trace("Shelly.onWebsocketMessageReceived()");

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
      processRpcRequest(request, wsMessage.isText(), websocketContext.getOutput());
    }
  }

  /**
   * Handle the notification to process a pending EM.GetStatus request
   * @param message Notification to process a pending EM.GetStatus request
   * @return Same behavior
   */
  protected Behavior<Command> onProcessPendingEmGetStatusRequest(WebsocketProcessPendingEmGetStatusRequest message) {
    logger.trace("Shelly.onProcessPendingEmGetStatusRequest()");

    processRpcRequest(
          message.websocketContext().getLastEmGetStatusRequest(),
          message.websocketMessage().isText(),
          message.websocketContext().getOutput());

    return Behaviors.same();
  }

  /**
   * Handle a wrapped UDP server notification
   * @param message Wrapped UDP server notification
   * @return Same behavior
   */
  protected Behavior<Command> onWrappedUdpServerNotification(WrappedUdpServerNotification message) {
    
    try {
      UdpServer.Notification notification = message.notification();
      
      if (notification instanceof UdpServer.NotifyDatagramReceived notifyDatagramReceived) {
        onUdpServerDatagramReceived(notifyDatagramReceived);
      } else if (notification instanceof UdpServer.NotifyOutput notifyOutput) {
        logger.debug("UDP server output {}", notifyOutput.output());
        udpOutput = notifyOutput.output();
      } else if (notification instanceof UdpServer.NotifyInitialized notifyInitialized) {
        logger.debug("UDP server initialized");
        notifyInitialized.replyTo().tell(UdpServer.Ack.INSTANCE);
      } else if (notification instanceof UdpServer.NotifyClosed) {
        logger.error("UDP server closed");
        throw new RuntimeException("UDP server closed");
      } else if (notification instanceof UdpServer.NotifyFailed notifyFailed) {
        onUdpServerFailed(notifyFailed);
      } else if (notification instanceof UdpServer.NotifyBound notifyBound) {
        logger.info("UDP server is listening on {}", notifyBound.address());
      } else {
        logger.error("unknown UDP server notification: {}", notification);
      }
    } catch (Exception e) {
      logger.error("failure while processing UDP server notification: {}", e.getMessage());
    }
    
    return Behaviors.same(); 
  }
  
  protected void onUdpServerDatagramReceived(UdpServer.NotifyDatagramReceived message) {
    logger.trace("Shelly.onUdpServerDatagramReceived()");

    message.replyTo().tell(UdpServer.Ack.INSTANCE);
    
    UdpClientContext udpClientContext = getUdpClientContext(message.datagram().remote());

    String text = message.datagram().data().utf8String();

    logger.trace("received udp datagram from {}: {}", message.datagram().remote(), text);

    Rpc.Request request = Rpc.parseRequest(text);

    if ("EM.GetStatus".equals(request.method())) {
      udpClientContext.handleEmGetStatusRequest(message.datagram(), request);
    } else {
      processUdpRpcRequest(request, message.datagram().remote());
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

  /**
   * Handle the notification that the UDP server failed
   * @param message Notification that the UDP server failed
   */
  protected void onUdpServerFailed(UdpServer.NotifyFailed message) {
    logger.trace("Shelly.onUdpServerFailed()");
    
    logger.error("UDP server failed: {}", message.failure().getMessage());
    
    throw new RuntimeException("UDP server failed", message.failure());
  }

  /**
   * Handle the notification to process a pending EM.GetStatus request
   * @param message Notification to process a pending EM.GetStatus request
   * @return Same behavior
   */
  protected Behavior<Command> onUdpClientProcessPendingEmGetStatusRequest(UdpClientProcessPendingEmGetStatusRequest message) {
    logger.trace("Shelly.onUdpClientProcessPendingEmGetStatusRequest()");
    
    UdpClientContext udpClientContext = message.udpClientContext();

    processUdpRpcRequest(udpClientContext.getLastEmGetStatusRequest(), udpClientContext.getRemote());
          
    return Behaviors.same();
  }

  /**
   * Process the specified RPC request
   * @param request Incoming RPC request to process
   * @param createTextResponse Flag indicating whether to create a text or binary response
   * @param output Actor reference to the websocket output actor
   */
  protected void processRpcRequest(@NotNull Rpc.Request request,
                                   boolean createTextResponse,
                                   @NotNull ActorRef<WebsocketOutput.Command> output) {
    logger.trace("Shelly.processRpcRequest()");
    
    Rpc.ResponseFrame response = createRpcResponse(request);

    Message wsResponse;
    if (createTextResponse) {
      wsResponse = TextMessage.create(Rpc.responseToString(response));
    } else {
      wsResponse = BinaryMessage.create(ByteString.fromArrayUnsafe(Rpc.responseToBytes(response)));
    }

    output.tell(new WebsocketOutput.Send(wsResponse));
  }

  /**
   * Process an RPC request received via UDP
   * @param request Incoming RPC request to process
   */
  protected void processUdpRpcRequest(@NotNull Rpc.Request request,
                                      @NotNull InetSocketAddress remote) {
    logger.trace("Shelly.processUdpRpcRequest()");

    Rpc.ResponseFrame response = createRpcResponse(request);
    
    udpOutput.tell(Datagram.create(ByteString.fromArrayUnsafe(Rpc.responseToBytes(response)), remote));
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
  
  protected abstract Rpc.ResponseFrame createRpcResponse(Rpc.Request request);
  
  private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
  
  protected String getTime() {
    return LocalTime.now().format(TIME_FORMATTER);
  }
  
  protected long getUptime() {
    return Duration.between(startTime, Instant.now()).getSeconds();
  }

  protected Route createRoute() {
    return null;
  }

  protected int getNumOutputs() {
    return 0;
  }

  protected int getNumMeters() {
    return 0;
  }
  
  protected WiFiStatus createWiFiStatus() {
    return new WiFiStatus(getConfig().getConfig("wifi-status"));
  }

  protected CloudStatus createCloudStatus() {
    return new CloudStatus(false, false);
  }

  protected MqttStatus createMqttStatus() {
    return new MqttStatus(false);
  }

  protected TempStatus createTempStatus() {
    return new TempStatus(28.08, 82.54, true);
  }

  protected Route createCommonRoute() {
    HttpRoute commonRoute = new HttpRoute(
          LoggerFactory.getLogger(logger.getName() + ".http"),
          getContext().getSystem(), 
          getMaterializer(),
          getContext().getSelf(),
          websocketInputNotificationAdapter);
    return commonRoute.createRoute();
  }
  
  public record ShellyGet(
        @NotNull ActorRef<ShellyInfo> response
  ) implements Command {}
  
  public record HttpRpcRequest(
        @NotNull Rpc.Request request,
        @NotNull ActorRef<Rpc.ResponseFrame> replyTo
  ) implements Command {}
  
  public record WebsocketOutputOpened(
        @NotNull String connectionId,
        @NotNull ActorRef<WebsocketOutput.Command> sourceActor
  ) implements Command {}

  public record SettingsGet(
        @NotNull ActorRef<Settings> replyTo
  ) implements Command {}
  
  public record WrappedWebsocketInputNotification(
        @NotNull WebsocketInput.Notification notification
  ) implements Command {}
  
  public record WrappedUdpServerNotification(
        @NotNull UdpServer.Notification notification
  ) implements Command {}
  
  public record WebsocketProcessPendingEmGetStatusRequest(
        @NotNull WebsocketContext websocketContext,
        @NotNull Message websocketMessage
  ) implements Command {}

  public record UdpClientProcessPendingEmGetStatusRequest(
        @NotNull UdpClientContext udpClientContext,
        @NotNull Datagram datagram
  ) implements Command {}

  public enum ThrottlingQueueClosed implements Command {
    INSTANCE
  }

  public record ShellyInfo(
    String type,
    String mac,
    boolean auth,
    String fw,
    boolean discoverable,
    int longid,
    int num_outputs,
    int num_meters
  ) {}

  @Getter
  @AllArgsConstructor
  public static class Settings {
    public final Device device;
    public final Login login;
    public final String fw;
    public final boolean discoverable;

    public Settings(@NotNull Config config) {
      this.device = new Device(config.getConfig("device"));
      this.login = new Login(config.getConfig("login"));
      this.fw = config.getString("fw");
      this.discoverable = config.getBoolean("discoverable");
    }

    @Getter
    @AllArgsConstructor
    public static class Device {
      private final String type;
      private final String mac;
      private final String hostname;
      private final int num_outputs = 1;
      private final int num_meters = 3;

      public Device(@NotNull Config config) {
        this.type = config.getString("type");
        this.mac = config.getString("mac").toUpperCase();
        this.hostname = config.getString("hostname").toLowerCase();
      }
    }

    @Getter
    @AllArgsConstructor
    public static class Login {
      public final boolean enabled;
      public final boolean unprotected;
      public final String username;

      public Login(@NotNull Config config) {
        this.enabled = config.getBoolean("enabled");
        this.unprotected = config.getBoolean("unprotected");
        this.username = config.getString("username");
      }
    }
  }

  public record StatusGet(
        @NotNull ActorRef<Status> replyTo
  ) implements Command {}

  @Getter
  @AllArgsConstructor
  public static class Status implements Rpc.Response{
    private final @NotNull WiFiStatus wifi_sta;
    private final @NotNull CloudStatus cloud;
    private final @NotNull MqttStatus mqtt;
    private final @NotNull String time;
    private final long unixtime;
    private final int serial;
    private final boolean has_update;
    private final String mac;
    private final long ram_total;
    private final long ram_free;
    private final long ram_lwm;
    private final long fs_size;
    private final long fs_free;
    private final long uptime;
    private final double temperature;
    private final boolean overtemperature;
    private final TempStatus tmp;
  }

  public record WiFiStatus(
        @JsonProperty("connected") boolean connected,
        @JsonProperty("ssid") String ssid,
        @JsonProperty("ip") String ip,
        @JsonProperty("rssi") int rssi
  ) {
    public WiFiStatus(Config config) {
      this(config.getBoolean("connected"), config.getString("ssid"), config.getString("ip"), config.getInt("rssi"));
    }
  }

  public record CloudStatus(
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("connected") boolean connected
  ) {}

  public record MqttStatus(
        @JsonProperty("connected") boolean connected
  ) {}
  
  public record TempStatus(
        @JsonProperty("tC") double tC,
        @JsonProperty("tF") double tF,
        @JsonProperty("is_valid") boolean isValid
  ) {}

  @Getter
  @Setter
  @AllArgsConstructor(staticName = "create")
  protected static class WebsocketContext {
    private final ActorRef<WebsocketOutput.Command> output;
    private final UniqueKillSwitch killSwitch;
    private final SourceQueueWithComplete<WebsocketProcessPendingEmGetStatusRequest> throttlingQueue;
    private Rpc.Request lastEmGetStatusRequest;
    
    public void close() {
      killSwitch.shutdown();
    }
    
    public void handleEmGetStatusRequest(@NotNull Message wsMessage, @NotNull Rpc.Request emGetStatusRequest) {
      lastEmGetStatusRequest = emGetStatusRequest;
      throttlingQueue.offer(new WebsocketProcessPendingEmGetStatusRequest(this, wsMessage));
    }
  }

  @Getter
  @Setter
  @AllArgsConstructor
  protected static class UdpClientContext {
    private final InetSocketAddress remote;
    private final UniqueKillSwitch killSwitch;
    private final SourceQueueWithComplete<UdpClientProcessPendingEmGetStatusRequest> throttlingQueue;
    private Rpc.Request lastEmGetStatusRequest;
    private Instant lastEmGetStatusRequestTime;
    
    public static UdpClientContext of(@NotNull InetSocketAddress remote,
                                      @NotNull UniqueKillSwitch killSwitch,
                                      @NotNull SourceQueueWithComplete<UdpClientProcessPendingEmGetStatusRequest> throttlingQueue) {
      return new UdpClientContext(remote, killSwitch, throttlingQueue, null, Instant.now());
    }

    public void handleEmGetStatusRequest(@NotNull Datagram datagram, @NotNull Rpc.Request emGetStatusRequest) {
      lastEmGetStatusRequest = emGetStatusRequest;
      lastEmGetStatusRequestTime = Instant.now();
        throttlingQueue.offer(new UdpClientProcessPendingEmGetStatusRequest(this, datagram));
    }
    
    public void close() {
      killSwitch.shutdown();
    }
  }
}
