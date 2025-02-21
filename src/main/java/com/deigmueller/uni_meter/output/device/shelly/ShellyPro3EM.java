package com.deigmueller.uni_meter.output.device.shelly;

import com.deigmueller.uni_meter.application.UniMeter;
import com.deigmueller.uni_meter.common.shelly.Rpc;
import com.deigmueller.uni_meter.common.utils.MathUtils;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.typesafe.config.Config;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.pekko.Done;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.apache.pekko.http.javadsl.marshallers.jackson.Jackson;
import org.apache.pekko.http.javadsl.server.AllDirectives;
import org.apache.pekko.http.javadsl.server.Route;

import org.jetbrains.annotations.NotNull;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.TimeZone;

public class ShellyPro3EM extends Shelly {
  // Instance members
  public static Behavior<Command> create(@NotNull ActorRef<UniMeter.Command> controller,
                                         @NotNull Config config) {
    return Behaviors.setup(context -> new ShellyPro3EM(context, controller, config));
  }

  protected ShellyPro3EM(@NotNull ActorContext<Command> context,
                         @NotNull ActorRef<UniMeter.Command> controller,
                         @NotNull Config config) {
    super(context, controller, config);
    
    this.setSettings(new Settings(config));
    
    controller.tell(new UniMeter.RegisterHttpRoute(getBindInterface(), getBindPort(), createRoute()));
  }

  @Override
  public @NotNull ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onMessage(EmGetConfig.class, this::onEmGetConfig)
          .onMessage(EmGetStatus.class, this::onEmGetStatus)
          .onMessage(EmDataGetStatus.class, this::onEmDataGetStatus)
          .onMessage(ResetData.class, this::onResetData)
          .onMessage(ShellyGetStatus.class, this::onShellyGetStatus)
          .onMessage(SysGetConfig.class, this::onSysGetConfig);
  }
  
  @Override
  protected @NotNull Behavior<Command> onStatusGet(@NotNull StatusGet request) {
    logger.trace("ShellyPro3EM.onStatusGet()");
    
    request.replyTo().tell(createStatus());
    
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
      request.replyTo().tell(
            new EmGetStatusOrFailureResponse(
                  null, 
                  rpcEmGetStatus()));
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
   * Handle the Shelly.GetStatus HTTP request
   * @param request Request to get the device's status
   * @return Same behavior               
   */
  protected @NotNull Behavior<Command> onShellyGetStatus(@NotNull ShellyGetStatus request) {
    logger.trace("ShellyPro3EM.onShellyGetStatus()");
    
    request.replyTo().tell(createStatus());
    
    return Behaviors.same();
  }

  /**
   * Handle the Sys.GetConfig HTTP request
   * @param request Request to get the device's configuration
   * @return Same behavior
   */
  protected @NotNull Behavior<Command> onSysGetConfig(@NotNull SysGetConfig request) {
    logger.trace("ShellyPro3EM.onSysGetConfig()");
    
    request.replyTo().tell(rpcSysGetConfig());
    
    return Behaviors.same();
  }

  /**
   * Create the HTTP route of the device
   * @return HTTP route of the device
   */
  @Override
  protected Route createRoute() {
    return createCommonRoute()
          .orElse(Shelly3EMRoute.of(getContext().getSystem(), getContext().getSelf()).createRoute());
  }

  @Override
  protected int getNumMeters() {
    return 3;
  }
  
  @Override
  protected Rpc.ResponseFrame createRpcResponse(Rpc.Request request) {
    return new Rpc.ResponseFrame(
          request.id(), 
          getHostname(), 
          request.src(),
          createRpcResult(request));
  }
   
  protected Rpc.Response createRpcResult(Rpc.Request request) {
    return switch (request.method()) {
      case "EM.GetConfig" -> rpcEmGetConfig();
      case "EM.GetStatus" -> rpcEmGetStatus();
      case "EMData.GetStatus" -> rpcEmDataGetStatus();
      case "Shelly.GetStatus" -> rpcShellyGetStatus();
      case "Shelly.GetDeviceInfo" -> rpcGetDeviceInfo();
      case "Sys.GetConfig" -> rpcSysGetConfig();
      default -> rpcUnknownMethod(request);
    };
  }

  private Rpc.Response rpcShellyGetStatus() {
    logger.trace("Shelly.rpcShellyGetStatus()");
    return createStatus();
  }

  private Rpc.GetDeviceInfoResponse rpcGetDeviceInfo() {
    logger.trace("Shelly.rpcGetDeviceInfo()");

    Rpc.GetDeviceInfoResponse response = new Rpc.GetDeviceInfoResponse(
          getHostname(),
          getHostname(),
          getMac(),
          1,
          "SPEM-003CEBEU",
          2,
          "20241011-114455/1.4.4-g6d2a586",
          "1.4.4",
          "Pro3EM",
          false,
          null,
          "triphase"
    );
    
    logger.trace("ShellyPro3EM.rpcGetDeviceInfo(): {}", response);
    
    return response;
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
  

  private Rpc.EmGetStatusResponse rpcEmGetStatus() {
    logger.trace("ShellyPro3EM.rpcEmGetStatus()");
    
    return new Rpc.EmGetStatusResponse(
          0,
          getPowerPhase0().current(),
          getPowerPhase0().voltage(),
          getPowerPhase0().power(),
          getPowerPhase0().apparentPower(),
          getPowerPhase0().powerFactor(),
          getPowerPhase0().frequency(),
          null, //Collections.emptyList(),
          getPowerPhase1().current(),
          getPowerPhase1().voltage(),
          getPowerPhase1().power(),
          getPowerPhase1().apparentPower(),
          getPowerPhase1().powerFactor(),
          getPowerPhase1().frequency(),
          null,
          getPowerPhase2().current(),
          getPowerPhase2().voltage(),
          getPowerPhase2().power(),
          getPowerPhase2().apparentPower(),
          getPowerPhase2().powerFactor(),
          getPowerPhase2().frequency(),
          null, //Collections.emptyList(),
          null, // 0.0,
          null, //Collections.emptyList(),
          MathUtils.round(getPowerPhase0().current() + getPowerPhase1().current() + getPowerPhase2().current(), 2),
          MathUtils.round(getPowerPhase0().power() + getPowerPhase1().power() + getPowerPhase2().power(), 2),
          MathUtils.round(getPowerPhase0().apparentPower() + getPowerPhase1().apparentPower() + getPowerPhase2().apparentPower(), 2),
          null, //Collections.emptyList()
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
  
  private Rpc.SysGetConfigResponse rpcSysGetConfig() {
    logger.trace("ShellyPro3EM.rpcSysGetConfig()");
    
    return new Rpc.SysGetConfigResponse(
          new Rpc.Device(
                getHostname(),
                getMac(),
                getSettings().getFw(),
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
   * Create the device's status
   * @return Device's status
   */
  private Status createStatus() {
    return new Status(
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
          createTempStatus(),
          List.of(
                EMeterStatus.of(getPowerPhase0(), getEnergyPhase0()),
                EMeterStatus.of(getPowerPhase1(), getEnergyPhase1()),
                EMeterStatus.of(getPowerPhase2(), getEnergyPhase2())),
          getPowerPhase0().power() + getPowerPhase1().power() + getPowerPhase2().power(),
          true);
  }
  
  private Rpc.Response rpcUnknownMethod(Rpc.Request request) {
    logger.error("ShellyPro3EM.rpcUnknownMethod()");
    throw new IllegalArgumentException("Unknown method: " + request.method());
  }

  public record ShellyGetStatus(
        @JsonProperty("replyTo") ActorRef<Shelly.Status> replyTo
  ) implements Command {}
  
  public record SysGetConfig(
        @JsonProperty("replyTo") ActorRef<Rpc.SysGetConfigResponse> replyTo
  ) implements Command {}

  public record EmGetStatus(
        @JsonProperty("id") int id,
        @JsonProperty("replyTo") ActorRef<EmGetStatusOrFailureResponse> replyTo
  ) implements Command {}
  
  public record EmGetStatusOrFailureResponse(
        @JsonProperty("failure") RuntimeException failure,
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
        @JsonProperty("id") int id,
        @JsonProperty("replyTo") ActorRef<EmDataGetStatusOrFailureResponse> replyTo
  ) implements Command {}
  
  public record EmDataGetStatusOrFailureResponse(
        @JsonProperty("failure") RuntimeException failure,
        @JsonProperty("status") Rpc.EmDataGetStatusResponse status
  ) {}
  
  public record ResetData(
        @NotNull ActorRef<Done> replyTo
  ) implements Command {}
  
  @Getter
  public static class Settings extends Shelly.Settings {
    public Settings(@NotNull Config config) {
      super(config);
    }
  }
  
  @Getter
  public static class Status extends Shelly.Status {
    private final List<EMeterStatus> emeters;
    private final double total_power;
    private final boolean fs_mounted;
    
    public Status(@NotNull WiFiStatus wifi_sta,
                  @NotNull CloudStatus cloud,
                  @NotNull MqttStatus mqtt,
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
                  @NotNull TempStatus temp,
                  @NotNull List<EMeterStatus> emeters,
                  double total_power,
                  boolean fs_mounted) {
      super(wifi_sta, cloud, mqtt, time, unixtime, serial, has_update, mac, ram_total, ram_free, ram_lwm, fs_size, 
            fs_free, uptime, temperature, overtemperature, temp);
      this.emeters = emeters;
      this.total_power = total_power;
      this.fs_mounted = fs_mounted;
    }
  }

  public record EMeterStatus(
        @JsonProperty("power") double power,
        @JsonProperty("pf") double pf,
        @JsonProperty("current") double current,
        @JsonProperty("voltage") double voltage,
        @JsonProperty("is_valid") boolean is_valid,
        @JsonProperty("total") double total,
        @JsonProperty("total_returned") double total_returned
  ) {
    public static EMeterStatus of(PowerData data, EnergyData energyData) {
      return new EMeterStatus(
            data.power(), 
            data.powerFactor(), 
            data.current(), 
            data.voltage(), 
            true, 
            energyData.totalConsumption(),
            energyData.totalProduction());
    }
  }

  @AllArgsConstructor(staticName = "of")
  private static class Shelly3EMRoute extends AllDirectives {
    private final ActorSystem<?> system;
    private final ActorRef<Command> device;
    private final Duration timeout = Duration.ofSeconds(5);

    public Route createRoute() {
      return concat(
            path("reset_data", this::onResetData)
      );
    }

    private Route onResetData() {
      return completeOKWithFuture(
            AskPattern.ask(
                  device,
                  ResetData::new,
                  timeout,
                  system.scheduler()
            ),
            Jackson.marshaller()
      );
    }
  }
}
