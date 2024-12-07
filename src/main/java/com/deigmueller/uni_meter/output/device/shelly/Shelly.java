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
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.apache.pekko.http.javadsl.model.ws.BinaryMessage;
import org.apache.pekko.http.javadsl.model.ws.Message;
import org.apache.pekko.http.javadsl.model.ws.TextMessage;
import org.apache.pekko.http.javadsl.server.Route;
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
  protected final Map<String, ActorRef<WebsocketOutput.Command>> websocketOutputs = new HashMap<>();
  protected final Map<String, String> statusSubscriber = new HashMap<>();
  protected final Map<String, Rpc.Request> rpcRequests = new HashMap<>();
  private final Instant startTime = Instant.now();
  private final int bindPort = getConfig().getInt("port");
  private final String mac = getConfig().getString("device.mac");
  private final String hostname = getConfig().getString("device.hostname");
  
  private Cancellable notificationTimer;
  private Settings settings = new Settings(getConfig());
  
  protected Shelly(@NotNull ActorContext<Command> context,
                   @NotNull ActorRef<UniMeter.Command> controller,
                   @NotNull Config config) {
    super(context, controller, config);
  }

  @Override
  public ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onMessage(ShellyGet.class, this::onShellyGet)
          .onMessage(SettingsGet.class, this::onSettingsGet)
          .onMessage(StatusGet.class, this::onStatusGet)
          .onMessage(RpcRequest.class, this::onRpcRequest)
          .onMessage(WebsocketOutputOpened.class, this::onWebsocketOutputOpened)
          .onMessage(NotifySubscribers.class, this::onNotifySubscribers)
          .onMessage(WrappedWebsocketInputNotification.class, this::onWrappedWebsocketInputNotification);
  }
  
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

  protected Behavior<Command> onSettingsGet(SettingsGet request) {
    logger.trace("Shelly.onSettingsGet()");

    request.replyTo().tell(settings);

    return Behaviors.same();
  }

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
  
  protected Behavior<Command> onRpcRequest(RpcRequest request) {
    logger.trace("Shelly.onRpcRequest()");
    
    request.replyTo().tell(createRpcResponse(request.request()));

    return Behaviors.same();
  }
  
  protected Behavior<Command> onWebsocketOutputOpened(WebsocketOutputOpened message) {
    logger.trace("Shelly.onWebsocketOutputOpened()");

    logger.debug("outgoing websocket connection {} created", message.connectionId());
    websocketOutputs.put(message.connectionId(), message.sourceActor());
    
    return Behaviors.same();
  }
  
  protected Behavior<Command> onNotifySubscribers(NotifySubscribers message) {
    logger.trace("Shelly.onNotifySubscribers()");
    return Behaviors.same();
  }
  
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
  
  protected void onWebsocketInputOpened(WebsocketInput.NotifyOpened message) {
    logger.trace("Shelly.onWebsocketInputOpened()");

    logger.debug("incoming websocket connection {} opened", message.connectionId());

    message.replyTo().tell(WebsocketInput.Ack.INSTANCE);
  }
  
  protected void onWebsocketInputClosed(WebsocketInput.NotifyClosed message) {
    logger.trace("Shelly.onWebsocketInputClosed()");
    
    logger.debug("incoming websocket connection {} closed", message.connectionId());
    ActorRef<WebsocketOutput.Command> output = websocketOutputs.remove(message.connectionId());
    if (output != null) {
      output.tell(WebsocketOutput.Close.INSTANCE);
    }
    rpcRequests.remove(message.connectionId());
    statusSubscriber.remove(message.connectionId());
  }
  
  protected void onWebsocketInputFailed(WebsocketInput.NotifyFailed message) {
    logger.trace("Shelly.onWebsocketInputFailed()");

    logger.error("incoming websocket connection {} failed: {}", message.connectionId(), message.failure().getMessage());
    ActorRef<WebsocketOutput.Command> output = websocketOutputs.remove(message.connectionId());
    if (output != null) {
      output.tell(WebsocketOutput.Close.INSTANCE);
    }
  }
  
  protected void onWebsocketMessageReceived(WebsocketInput.NotifyMessageReceived message) {
    logger.trace("Shelly.onWebsocketMessageReceived()");

    message.replyTo().tell(WebsocketInput.Ack.INSTANCE);
    
    ActorRef<WebsocketOutput.Command> output = websocketOutputs.get(message.connectionId());
    if (output == null) {
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
    
    if (request.src() != null) {
      if (! statusSubscriber.containsKey(message.connectionId())) {
        rpcRequests.put(message.connectionId(), request);
        statusSubscriber.put(message.connectionId(), request.src());
      }
    }
    
    Rpc.ResponseFrame response = createRpcResponse(request);

    Message wsResponse;
    if (wsMessage.isText()) {
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
  
  public record RpcRequest(
        @NotNull Rpc.Request request,
        @NotNull ActorRef<Rpc.ResponseFrame> replyTo
  ) implements Command {}
  
  public enum NotifySubscribers implements Command {
    INSTANCE
  }

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
        this.mac = config.getString("mac");
        this.hostname = config.getString("hostname");
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

}
