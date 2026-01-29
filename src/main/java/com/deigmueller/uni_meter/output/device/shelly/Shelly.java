package com.deigmueller.uni_meter.output.device.shelly;

import com.deigmueller.uni_meter.application.UdpServer;
import com.deigmueller.uni_meter.application.UniMeter;
import com.deigmueller.uni_meter.application.WebsocketInput;
import com.deigmueller.uni_meter.application.WebsocketOutput;
import com.deigmueller.uni_meter.common.shelly.Rpc;
import com.deigmueller.uni_meter.common.utils.NetUtils;
import com.deigmueller.uni_meter.mdns.MDnsRegistrator;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.deigmueller.uni_meter.output.ClientContext;
import com.typesafe.config.Config;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.apache.pekko.http.javadsl.model.ws.*;
import org.apache.pekko.http.javadsl.server.Route;
import org.apache.pekko.stream.connectors.udp.Datagram;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Getter(AccessLevel.PROTECTED)
@Setter(AccessLevel.PROTECTED)
public abstract class Shelly extends OutputDevice {
  // Instance members
  private final Instant startTime = Instant.now();
  private final String bindInterface = getConfig().getString("interface");
  private final int bindPort = getConfig().getInt("port");
  private final String defaultMac = getDefaultMacAddress(getConfig());
  private final String defaultHostname = getDefaultHostName(getConfig(), defaultMac);

  /**
   * Protected constructor
   * @param context Actor context
   * @param controller Uni-meter controller
   * @param mDnsRegistrator mDNS registration actor
   * @param config The output device configuration
   */
  protected Shelly(@NotNull ActorContext<Command> context,
                   @NotNull ActorRef<UniMeter.Command> controller,
                   @NotNull ActorRef<MDnsRegistrator.Command> mDnsRegistrator,
                   @NotNull Config config) {
    super(context, controller, mDnsRegistrator, config, Shelly::initRemoteContexts);
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
          .onMessage(GetStatus.class, this::onGetStatus);
  }

  /**
   * Handle an HTTP GET request for the Shelly device information
   * @param request Request for the Shelly device information
   * @return Same behavior
   */
  abstract protected Behavior<Command> onShellyGet(ShellyGet request);

  /**
   * Handle an HTTP GET request for the Shelly device settings
   * @param request Request for the Shelly device settings
   * @return Same behavior
   */
  protected Behavior<Command> onSettingsGet(SettingsGet request) {
    logger.trace("Shelly.onSettingsGet()");

    request.replyTo().tell(
          new Settings(
                new Device(
                      getConfig().getString("device.type"),
                      getMac(request.remoteAddress()),
                      getHostname(request.remoteAddress()),
                      getNumOutputs(),
                      getNumMeters()),
                new Login(false, false, null),
                getConfig().getString("mdns.fw"),
                true));

    return Behaviors.same();
  }

  /**
   * Handle an HTTP GET request for the Shelly device status
   * @param request Request for the Shelly device status
   * @return Same behavior
   */
  protected Behavior<Command> onGetStatus(@NotNull Shelly.GetStatus request) {
    logger.trace("Shelly.onGetStatus()");

    try {
      request.replyTo().tell(
            GetStatusOrFailureResponse.createSuccess(
                  new Status(
                        createWiFiStatus(),
                        createCloudStatus(),
                        createMqttStatus(),
                        getTime(),
                        Instant.now().getEpochSecond(),
                        1,
                        false,
                        getMac(request.remoteAddress()),
                        50648,
                        38376,
                        32968,
                        233681,
                        174194,
                        getUptime(),
                        28.08,
                        false,
                        createTempStatus())));
    } catch (Exception e) {
      request.replyTo().tell(GetStatusOrFailureResponse.createFailure(e));
    }

      return Behaviors.same();
  }
  
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

  protected int getNumOutputs() {
    return 0;
  }

  protected abstract int getNumMeters();


  protected Rpc.WiFiStatus createWiFiStatus() {
    return new Rpc.WiFiStatus(getConfig().getConfig("wifi-status"));
  }

  protected Rpc.CloudStatus createCloudStatus() {
    return new Rpc.CloudStatus(getConfig().getConfig(("cloud-status")));
  }

  protected Rpc.MqttStatus createMqttStatus() {
    return new Rpc.MqttStatus(false);
  }

  protected Rpc.SysStatus createSysStatus() {
    return new Rpc.SysStatus(
          getUptime(),
          getConfig().getString("mdns.fw"));
  }

  protected Rpc.TempStatus createTempStatus() {
    return new Rpc.TempStatus(28.08, 82.54, true);
  }

  /**
   * Get the hostname to use it for the specified remote address
   * @param remoteAddress Remote address
   * @return Hostname to use
   */
  protected String getHostname(@NotNull InetAddress remoteAddress) {
    ClientContext clientContext = getClientContexts().get(remoteAddress);
    if (clientContext instanceof ShellyClientContext shellyRemoteContext && shellyRemoteContext.hostname() != null) {
      return shellyRemoteContext.hostname();
    }
    
    return defaultHostname;
  }
  
  /**
   * Get the MAC address to use it for the specified remote address
   * @param remoteAddress Remote address
   * @return MAC address to use
   */
  protected String getMac(@NotNull InetAddress remoteAddress) {
    ClientContext clientContext = getClientContexts().get(remoteAddress);
    if (clientContext instanceof ShellyClientContext shellyRemoteContext && shellyRemoteContext.mac() != null) {
      return shellyRemoteContext.mac();
    }

    return defaultMac;
  }

  /**
   * Initialize the remote contexts
   * @param logger Logger
   * @param remoteContexts List of remote context configuration
   * @param remoteContextMap Target Map of remote contexts
   */
  protected static void initRemoteContexts(@NotNull Logger logger,
                                           @NotNull List<? extends Config> remoteContexts,
                                           @NotNull Map<InetAddress, ClientContext> remoteContextMap) {
    for (Config remoteContext : remoteContexts) {
      try {
        String address = remoteContext.getString("address");
        String mac = remoteContext.hasPath("mac")
              ? remoteContext.getString("mac").toUpperCase()
              : null;
        String hostname = mac != null
              ? "shellypro3em-" + mac.toLowerCase()
              : null;
        
        Double powerFactor = remoteContext.hasPath("power-factor") 
              ? remoteContext.getDouble("power-factor")
              : null;
        
        remoteContextMap.put(
              InetAddress.getByName(address),
              new ShellyClientContext(mac, hostname, powerFactor));
      } catch (UnknownHostException ignore) {
        logger.debug("unknown host: {}", remoteContext.getString("address"));
      }
    }
  }
  
  protected static String getDefaultMacAddress(@NotNull Config config) {
    if (! StringUtils.isAllBlank(config.getString("device.mac"))) {
      return config.getString("device.mac");
    }
    
    String detected = NetUtils.detectPrimaryMacAddress();
    if (detected != null) {
      return detected;
    }
    
    return "B827EB364242";
  }
  
  protected static String getDefaultHostName(@NotNull Config config, @NotNull String mac) {
    if (! StringUtils.isAllBlank(config.getString("device.hostname"))) {
      return config.getString("device.hostname");
    }
    
    return "shellypro3em-" + mac.toLowerCase();
  }
  
  public record ShellyGet(
        @NotNull InetAddress remoteAddress,
        @NotNull ActorRef<Rpc.GetDeviceInfoResponse> response
  ) implements Command {}
  
  public record HttpRpcRequest(
        @NotNull InetAddress remoteAddress,
        @NotNull Rpc.Request request,
        @NotNull ActorRef<Rpc.ResponseFrame> replyTo
  ) implements Command {}
  
  public record WebsocketOutputOpened(
        @NotNull String connectionId,
        @NotNull InetAddress remoteAddress,
        @NotNull ActorRef<WebsocketOutput.Command> sourceActor
  ) implements Command {}

  public record SettingsGet(
        @NotNull InetAddress remoteAddress,
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

  public record Settings(
        Device device,
        Login login,
        String fw, 
        boolean discoverable
  ) {}

  public record Device(
      String type,
      String mac,
      String hostname,
      int num_outputs,
      int num_meters
  ) {}
  
  public record Login(
        boolean enabled,
        boolean unprotected,
        String username
  ) {}

  public record GetStatus(
        @NotNull InetAddress remoteAddress,
        @NotNull ActorRef<GetStatusOrFailureResponse> replyTo
  ) implements Command {}
  
  public record GetStatusOrFailureResponse(
        @Nullable Exception failure,
        @Nullable Status status
  ) implements Command {
    public static GetStatusOrFailureResponse createSuccess(@NotNull Status status) {
      return new GetStatusOrFailureResponse(null, status);
    }
    public static GetStatusOrFailureResponse createFailure(@NotNull Exception failure) {
      return new GetStatusOrFailureResponse(failure, null);
    }
  }

  @Getter
  @AllArgsConstructor
  public static class Status implements Rpc.Response{
    private final @NotNull Rpc.WiFiStatus wifi_sta;
    private final @NotNull Rpc.CloudStatus cloud;
    private final @NotNull Rpc.MqttStatus mqtt;
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
    private final Double temperature;
    private final boolean overtemperature;
    private final Rpc.TempStatus tmp;
  }
  
  public record ShellyClientContext(
        @Nullable String mac,
        @Nullable String hostname,
        @Nullable Double powerFactor
  ) implements ClientContext {}

  @Getter
  @Setter
  @AllArgsConstructor(staticName = "create")
  protected static class WebsocketContext {
    private final InetAddress remoteAddress;
    private final ActorRef<WebsocketOutput.Command> output;
    private Rpc.Request lastEmGetStatusRequest;
    
    public void handleEmGetStatusRequest(@NotNull Rpc.Request emGetStatusRequest) {
      lastEmGetStatusRequest = emGetStatusRequest;
    }
  }

  @Getter
  @Setter
  @AllArgsConstructor
  protected static class UdpClientContext {
    private final InetSocketAddress remote;
    private Rpc.Request lastEmGetStatusRequest;
    private Instant lastEmGetStatusRequestTime;
    
    public static UdpClientContext of(@NotNull InetSocketAddress remote) {
      return new UdpClientContext(remote, null, Instant.now());
    }

    public void handleEmGetStatusRequest(@NotNull Rpc.Request emGetStatusRequest) {
      lastEmGetStatusRequest = emGetStatusRequest;
      lastEmGetStatusRequestTime = Instant.now();
    }
  }
}
