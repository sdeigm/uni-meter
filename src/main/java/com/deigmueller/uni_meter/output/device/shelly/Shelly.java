package com.deigmueller.uni_meter.output.device.shelly;

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
import org.apache.pekko.actor.Cancellable;
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
import org.apache.pekko.stream.OverflowStrategy;
import org.apache.pekko.stream.javadsl.Sink;
import org.apache.pekko.stream.javadsl.Source;
import org.apache.pekko.stream.javadsl.SourceQueueWithComplete;
import org.apache.pekko.util.ByteString;

import org.jetbrains.annotations.NotNull;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Getter(AccessLevel.PROTECTED)
@Setter(AccessLevel.PROTECTED)
public abstract class Shelly extends OutputDevice {
  // Instance members
  private final ActorRef<WebsocketInput.Notification> websocketInputNotificationAdapter =
        getContext().messageAdapter(WebsocketInput.Notification.class, WrappedWebsocketInputNotification::new);
  protected final Map<String, ConnectionContext> connections = new HashMap<>();
  private final Instant startTime = Instant.now();
  private final int bindPort = getConfig().getInt("port");
  private final String mac = getConfig().getString("device.mac");
  private final String hostname = getConfig().getString("device.hostname");
  private final Duration minSamplePeriod = getConfig().getDuration("min-sample-period");
  
  private Cancellable notificationTimer;
  private Settings settings = new Settings(getConfig());
  
  protected Shelly(@NotNull ActorContext<Command> context,
                   @NotNull ActorRef<UniMeter.Command> controller,
                   @NotNull Config config) {
    super(context, controller, config);
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
          .onMessage(ProcessPendingEmGetStatusRequest.class, this::onProcessPendingEmGetStatusRequest)
          .onMessage(WebsocketOutputOpened.class, this::onWebsocketOutputOpened)
          .onMessage(ThrottlingQueueClosed.class, this::onThrottlingQueueClosed)
          .onMessage(WrappedWebsocketInputNotification.class, this::onWrappedWebsocketInputNotification);
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
                getUptime()));

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

    SourceQueueWithComplete<ProcessPendingEmGetStatusRequest> throttlingQueue =
          Source.<ProcessPendingEmGetStatusRequest>queue(1, OverflowStrategy.dropHead())
                .throttle(1, minSamplePeriod)
                .to(Sink.actorRef(Adapter.toClassic(getContext().getSelf()), ThrottlingQueueClosed.INSTANCE))
                .run(getMaterializer());
    
    connections.put(message.connectionId(), ConnectionContext.create(message.sourceActor(), throttlingQueue, null));
    
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
    ConnectionContext connectionContext = connections.remove(message.connectionId());
    if (connectionContext != null) {
      connectionContext.close();
    }
  }

  /**
   * Handle the notification that a websocket input connection failed
   * @param message Notification of a failed websocket input connection
   */
  protected void onWebsocketInputFailed(WebsocketInput.NotifyFailed message) {
    logger.trace("Shelly.onWebsocketInputFailed()");

    logger.error("incoming websocket connection {} failed: {}", message.connectionId(), message.failure().getMessage());
    ConnectionContext connectionContext = connections.remove(message.connectionId());
    if (connectionContext != null) {
      connectionContext.close();
    }
  }

  /**
   * Handle an incoming websocket message
   * @param message Notification of an incoming websocket message
   */
  protected void onWebsocketMessageReceived(WebsocketInput.NotifyMessageReceived message) {
    logger.trace("Shelly.onWebsocketMessageReceived()");

    message.replyTo().tell(WebsocketInput.Ack.INSTANCE);
    
    ConnectionContext connectionContext = connections.get(message.connectionId());
    if (connectionContext == null) {
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
      connectionContext.handleEmGetStatusRequest(wsMessage, request);
    } else {
      processRpcRequest(request, wsMessage.isText(), connectionContext.getOutput());
    }
  }

  /**
   * Handle the notification to process a pending EM.GetStatus request
   * @param message Notification to process a pending EM.GetStatus request
   * @return Same behavior
   */
  protected Behavior<Command> onProcessPendingEmGetStatusRequest(ProcessPendingEmGetStatusRequest message) {
    logger.trace("Shelly.onProcessPendingEmGetStatusRequest()");

    processRpcRequest(
          message.connectionContext().getLastEmGetStatusRequest(),
          message.websocketMessage().isText(),
          message.connectionContext().getOutput());

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
  
  protected abstract Rpc.ResponseFrame createRpcResponse(Rpc.Request request);
  
  private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
  
  protected String getTime() {
    return LocalTime.now().format(TIME_FORMATTER);
  }
  
  protected long getUptime() {
    return Duration.between(startTime, Instant.now()).getSeconds();
  }

  @Override
  protected Route createRoute() {
    return null;
  }

  @Override
  protected int getNumOutputs() {
    return 0;
  }

  @Override
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

  protected Route createCommonRoute() {
    HttpRoute commonRoute = new HttpRoute(
          logger,
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
  
  public record ProcessPendingEmGetStatusRequest(
        @NotNull ConnectionContext connectionContext,
        @NotNull Message websocketMessage
  ) implements Command {}
  
  public enum ThrottlingQueueClosed implements Command {
    INSTANCE
  }

  @Getter
  @AllArgsConstructor
  public static class ShellyInfo {
    private final String type;
    private final String mac;
    private final boolean auth;
    private final String fw;
    private final boolean discoverable;
    private final int longid;
    private final int num_outputs;
    private final int num_meters;
  }

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
      public final String type;
      public final String mac;
      public final String hostname;

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
  public static class Status {
    private final @NotNull WiFiStatus wifi_sta;
    private final @NotNull CloudStatus cloud;
    private final @NotNull MqttStatus mqtt;
    private final @NotNull String time;
    private final long unixtime;
    private final int serial;
    private final boolean has_update;
    private final String max;
    private final long ram_total;
    private final long ram_free;
    private final long ram_lwm;
    private final long fs_size;
    private final long fs_free;
    private final long uptime;
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

  @Getter
  @Setter
  @AllArgsConstructor(staticName = "create")
  protected static class ConnectionContext {
    private final ActorRef<WebsocketOutput.Command> output;
    private final SourceQueueWithComplete<ProcessPendingEmGetStatusRequest> throttlingQueue;
    private Rpc.Request lastEmGetStatusRequest;
    
    public void close() {
      output.tell(WebsocketOutput.Close.INSTANCE);
    }
    
    public void handleEmGetStatusRequest(@NotNull Message wsMessage, @NotNull Rpc.Request emGetStatusRequest) {
      lastEmGetStatusRequest = emGetStatusRequest;
      throttlingQueue.offer(new ProcessPendingEmGetStatusRequest(this, wsMessage));
    }
  }

}
