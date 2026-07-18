package com.deigmueller.uni_meter.output.device.shelly;

import com.deigmueller.uni_meter.application.UniMeter;
import com.deigmueller.uni_meter.common.shelly.Rpc;
import com.deigmueller.uni_meter.common.shelly.RpcError;
import com.deigmueller.uni_meter.common.shelly.RpcException;
import com.deigmueller.uni_meter.mdns.MDnsRegistrator;
import com.deigmueller.uni_meter.output.TemporaryNotAvailableException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.typesafe.config.Config;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.jetbrains.annotations.NotNull;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Emulation of a Shelly Pro EM-50 (SPEM-002CEBEU50), the single phase variant of the Shelly Pro 3EM. The
 * device provides two single phase meters (EM1/EM1Data components). Channel 0 reports the summed up power
 * and energy data of all input phases, channel 1 is reported as unused.
 */
public class ShellyProEM extends ShellyPro3EM {
  // Class members
  public static final String TYPE = "ShellyProEM";

  /**
   * Static setup method
   * @param controller Controller actor reference
   * @param mDnsRegistrator mDNS registration actor
   * @param config Output device configuration
   * @return Behavior of the created actor
   */
  public static Behavior<Command> create(@NotNull ActorRef<UniMeter.Command> controller,
                                         @NotNull ActorRef<MDnsRegistrator.Command> mDnsRegistrator,
                                         @NotNull Config config) {
    return Behaviors.setup(context -> new ShellyProEM(context, controller, mDnsRegistrator, config));
  }

  /**
   * Protected constructor called by the static setup method
   * @param context Actor context
   * @param controller Controller actor reference
   * @param mDnsRegistrator mDNS registration actor
   * @param config Output device configuration
   */
  protected ShellyProEM(@NotNull ActorContext<Command> context,
                        @NotNull ActorRef<UniMeter.Command> controller,
                        @NotNull ActorRef<MDnsRegistrator.Command> mDnsRegistrator,
                        @NotNull Config config) {
    super(context, controller, mDnsRegistrator, config);
  }

  @Override
  public @NotNull ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onMessage(Em1GetConfig.class, this::onEm1GetConfig)
          .onMessage(Em1GetStatus.class, this::onEm1GetStatus)
          .onMessage(Em1DataGetConfig.class, this::onEm1DataGetConfig)
          .onMessage(Em1DataGetStatus.class, this::onEm1DataGetStatus);
  }

  @Override
  protected boolean supportsEm1() {
    return true;
  }

  @Override
  protected boolean isThrottledStatusRequest(@NotNull String method) {
    return super.isThrottledStatusRequest(method) || "EM1.GetStatus".equals(method);
  }

  @Override
  protected int getNumMeters() {
    return 2;
  }

  /**
   * Handle the EM1.GetStatus HTTP request
   * @param request Request to get the EM1 status
   * @return Same behavior
   */
  protected @NotNull Behavior<Command> onEm1GetStatus(@NotNull Em1GetStatus request) {
    logger.trace("ShellyProEM.onEm1GetStatus()");

    if (isEm1Channel(request.id())) {
      try {
        request.replyTo().tell(
              new Em1GetStatusOrFailureResponse(
                    null,
                    rpcEm1GetStatus(request.id(), getPowerFactorForRemoteAddress(request.remoteAddress()))));
      } catch (Exception e) {
        request.replyTo().tell(new Em1GetStatusOrFailureResponse(e, null));
      }
    } else {
      request.replyTo().tell(
            new Em1GetStatusOrFailureResponse(
                  new NoSuchElementException("unknown EM1 with id " + request.id()),
                  null));
    }

    return Behaviors.same();
  }

  /**
   * Handle the EM1.GetConfig HTTP request
   * @param request Request to get the EM1 configuration
   * @return Same behavior
   */
  protected @NotNull Behavior<Command> onEm1GetConfig(@NotNull Em1GetConfig request) {
    logger.trace("ShellyProEM.onEm1GetConfig()");

    if (isEm1Channel(request.id())) {
      request.replyTo().tell(
            new Em1GetConfigOrFailureResponse(
                  null,
                  rpcEm1GetConfig(request.id())));
    } else {
      request.replyTo().tell(
            new Em1GetConfigOrFailureResponse(
                  new NoSuchElementException("unknown EM1 with id " + request.id()),
                  null));
    }

    return Behaviors.same();
  }

  /**
   * Handle the EM1Data.GetConfig HTTP request
   * @param request Request to get the EM1 data config
   * @return Same behavior
   */
  protected @NotNull Behavior<Command> onEm1DataGetConfig(@NotNull Em1DataGetConfig request) {
    logger.trace("ShellyProEM.onEm1DataGetConfig()");

    if (isEm1Channel(request.id())) {
      request.replyTo().tell(
            new Em1DataGetConfigOrFailureResponse(null, rpcEm1DataGetConfig(request.id())));
    } else {
      request.replyTo().tell(
            new Em1DataGetConfigOrFailureResponse(
                  new NoSuchElementException("unknown EM1Data with id " + request.id()),
                  null));
    }

    return Behaviors.same();
  }

  /**
   * Handle the EM1Data.GetStatus HTTP request
   * @param request Request to get the EM1 data status
   * @return Same behavior
   */
  protected @NotNull Behavior<Command> onEm1DataGetStatus(@NotNull Em1DataGetStatus request) {
    logger.trace("ShellyProEM.onEm1DataGetStatus()");

    if (isEm1Channel(request.id())) {
      try {
        request.replyTo().tell(
              new Em1DataGetStatusOrFailureResponse(
                    null,
                    rpcEm1DataGetStatus(request.id())));
      } catch (RuntimeException e) {
        request.replyTo().tell(
              new Em1DataGetStatusOrFailureResponse(e, null));
      } catch (Exception e) {
        request.replyTo().tell(
              new Em1DataGetStatusOrFailureResponse(new RuntimeException(e), null));
      }
    } else {
      request.replyTo().tell(
            new Em1DataGetStatusOrFailureResponse(
                  new NoSuchElementException("unknown EM1Data with id " + request.id()),
                  null));
    }

    return Behaviors.same();
  }

  @Override
  protected Rpc.Response createRpcResult(@NotNull InetAddress remoteAddress,
                                         @NotNull Rpc.Request request) throws RpcException {
    return switch (request.method().toLowerCase()) {
      case "em1.getconfig" -> rpcEm1GetConfig(checkEm1Channel(em1RequestId(request)));
      case "em1.getstatus" -> rpcEm1GetStatus(checkEm1Channel(em1RequestId(request)), getPowerFactorForRemoteAddress(remoteAddress));
      case "em1data.getconfig" -> rpcEm1DataGetConfig(checkEm1Channel(em1RequestId(request)));
      case "em1data.getstatus" -> rpcEm1DataGetStatus(checkEm1Channel(em1RequestId(request)));
      default -> super.createRpcResult(remoteAddress, request);
    };
  }

  @Override
  protected Rpc.GetDeviceInfoResponse rpcGetDeviceInfo(@NotNull InetAddress remoteAddress) {
    logger.trace("ShellyProEM.rpcGetDeviceInfo()");

    Rpc.GetDeviceInfoResponse response = new Rpc.GetDeviceInfoResponse(
          null,
          getHostname(remoteAddress),
          getMac(remoteAddress),
          1,
          "SPEM-002CEBEU50",
          2,
          getConfig().getString("fw"),
          "1.4.4",
          "ProEM50",
          false,
          Rpc.RpcStringOrNull.of(null),
          null
    );

    logger.trace("ShellyProEM.rpcGetDeviceInfo(): {}", response);

    return response;
  }

  @Override
  protected Rpc.Response rpcShellyGetStatus(@NotNull InetAddress remoteAddress) {
    logger.trace("ShellyProEM.rpcShellyGetStatus()");

    double factor = getPowerFactorForRemoteAddress(remoteAddress);

    ShellyProEMStatus response = new ShellyProEMStatus(
          rpcBleGetStatus(),
          rpcBtHomeGetStatus(),
          rpcCloudGetStatus(),
          rpcEm1GetStatus(0, factor),
          rpcEm1GetStatus(1, factor),
          rpcEm1DataGetStatus(0),
          rpcEm1DataGetStatus(1),
          rpcEthGetStatus(),
          rpcModbusGetStatus(),
          rpcMqttGetStatus(),
          rpcSysGetStatus(remoteAddress),
          rpcTemperatureGetStatus(),
          rpcWifiGetStatus(),
          rpcWsGetStatus()
    );

    logger.trace("ShellyProEM.rpcShellyGetStatus(): {}", response);

    return response;
  }

  /**
   * Handle the EM1.GetStatus RPC request
   * @param id EM1 channel id (0 or 1)
   * @param factor Power factor to apply for the requesting client
   * @return Status of the EM1 component
   */
  protected Rpc.Em1GetStatusResponse rpcEm1GetStatus(int id, double factor) throws RpcException {
    logger.trace("ShellyProEM.rpcEm1GetStatus()");

    if (isSwitchedOff()) {
      throw new TemporaryNotAvailableException("device is not available until " + getOffUntil());
    }

    if (id == 1) {
      // the second channel of the emulated device is unused
      return new Rpc.Em1GetStatusResponse(
            1,
            0.0,
            getDefaultVoltage(),
            0.0,
            0.0,
            1.0,
            getDefaultFrequency(),
            "factory",
            null,
            null);
    }

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

    double totalPower = (powerPhase0.power() + powerPhase1.power() + powerPhase2.power()) * factor;

    if (checkUsageConstraint(totalPower)) {
      throw new RpcException(RpcError.ERROR_USAGE_CONSTRAINT, RpcError.ERROR_USAGE_CONSTRAINT_MSG);
    }

    double totalApparentPower =
          (powerPhase0.apparentPower() + powerPhase1.apparentPower() + powerPhase2.apparentPower()) * factor;

    double powerFactor = totalApparentPower != 0.0
          ? totalPower / totalApparentPower
          : 1.0;

    return new Rpc.Em1GetStatusResponse(
          0,
          (powerPhase0.current() + powerPhase1.current() + powerPhase2.current()) * factor,
          powerPhase0.voltage(),
          totalPower,
          totalApparentPower,
          powerFactor,
          powerPhase0.frequency(),
          "factory",
          null,
          null);
  }

  /**
   * Handle the EM1.GetConfig RPC request
   * @param id EM1 channel id (0 or 1)
   * @return Configuration of the EM1 component
   */
  protected Rpc.Em1GetConfigResponse rpcEm1GetConfig(int id) {
    logger.trace("ShellyProEM.rpcEm1GetConfig()");

    return new Rpc.Em1GetConfigResponse(
          id,
          new Rpc.RpcStringOrNull(null),
          false,
          "50A"
    );
  }

  /**
   * Handle the EM1Data.GetConfig RPC request
   * @param id EM1 channel id (0 or 1)
   * @return Configuration of the EM1Data component
   */
  protected Rpc.Em1DataGetConfigResponse rpcEm1DataGetConfig(int id) {
    logger.trace("ShellyProEM.rpcEm1DataGetConfig()");
    return new Rpc.Em1DataGetConfigResponse();
  }

  /**
   * Handle the EM1Data.GetStatus RPC request
   * @param id EM1 channel id (0 or 1)
   * @return Status of the EM1Data component
   */
  protected Rpc.Em1DataGetStatusResponse rpcEm1DataGetStatus(int id) throws RpcException {
    logger.trace("ShellyProEM.rpcEm1DataGetStatus()");

    if (isSwitchedOff()) {
      throw new TemporaryNotAvailableException("device is not available until " + getOffUntil());
    }

    if (id == 1) {
      // the second channel of the emulated device is unused
      return new Rpc.Em1DataGetStatusResponse(1, 0.0, 0.0, null);
    }

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

    double totalPower = (powerPhase0.power() + powerPhase1.power() + powerPhase2.power());

    if (checkUsageConstraint(totalPower)) {
      throw new RpcException(RpcError.ERROR_USAGE_CONSTRAINT, RpcError.ERROR_USAGE_CONSTRAINT_MSG);
    }

    return new Rpc.Em1DataGetStatusResponse(
          0,
          getEnergyPhase0().totalConsumption() + getEnergyPhase1().totalConsumption() + getEnergyPhase2().totalConsumption(),
          getEnergyPhase0().totalProduction() + getEnergyPhase1().totalProduction() + getEnergyPhase2().totalProduction(),
          null);
  }

  @Override
  protected Rpc.NotificationParam createNotifyStatusParam() throws RpcException {
    return new Rpc.Em1GetStatusNotification(
          Instant.now().toEpochMilli() / 1000.0,
          rpcEm1GetStatus(0, 1.0)
    );
  }

  @Override
  protected Rpc.ShellyGetComponentsResponse createComponents(@NotNull InetAddress remoteAddress) {
    double factor = getPowerFactorForRemoteAddress(remoteAddress);

    return new Rpc.ShellyGetComponentsResponse(
          List.of(
                new Rpc.Component("ble", rpcBleGetStatus(), rpcBleGetConfig()),
                new Rpc.Component("em1:0", rpcEm1GetStatus(0, factor), rpcEm1GetConfig(0)),
                new Rpc.Component("em1:1", rpcEm1GetStatus(1, factor), rpcEm1GetConfig(1)),
                new Rpc.Component("em1data:0", rpcEm1DataGetStatus(0), rpcEm1DataGetConfig(0)),
                new Rpc.Component("em1data:1", rpcEm1DataGetStatus(1), rpcEm1DataGetConfig(1))
          ),
          1,
          0,
          4
    );
  }

  @Override
  protected Rpc.Response createConfig(@NotNull InetAddress remoteAddress) {
    return new ShellyProEMConfig(
          new Rpc.BleGetConfigResponse(
                false,
                new Rpc.BleGetConfigResponseRpc(true),
                new Rpc.BleGetConfigResponseObserver(false)),
          rpcCloudGetConfig(),
          rpcEm1GetConfig(0),
          rpcEm1GetConfig(1),
          rpcEm1DataGetConfig(0),
          rpcEm1DataGetConfig(1),
          rpcSysGetConfig(remoteAddress),
          rpcTemperatureGetConfig(),
          rpcWifiGetConfig(),
          rpcWsGetConfig()
    );
  }

  /**
   * Check whether the specified id is a valid EM1 channel
   * @param id Channel id to check
   * @return True if the id is a valid EM1 channel
   */
  private static boolean isEm1Channel(int id) {
    return id == 0 || id == 1;
  }

  /**
   * Check that the specified id is a valid EM1 channel
   * @param id Channel id to check
   * @return The checked channel id
   */
  private static int checkEm1Channel(int id) {
    if (! isEm1Channel(id)) {
      throw new IllegalArgumentException("unknown EM1 with id " + id);
    }
    return id;
  }

  /**
   * Extract the requested EM1 channel id from an RPC request
   * @param request RPC request to extract the channel id from
   * @return Requested channel id (0 if not specified)
   */
  private static int em1RequestId(@NotNull Rpc.Request request) {
    if (request instanceof Rpc.Em1GetStatus em1GetStatus && em1GetStatus.params() != null) {
      return em1GetStatus.params().id();
    }
    if (request instanceof Rpc.Em1GetConfig em1GetConfig && em1GetConfig.params() != null) {
      return em1GetConfig.params().id();
    }
    if (request instanceof Rpc.Em1DataGetStatus em1DataGetStatus && em1DataGetStatus.params() != null) {
      return em1DataGetStatus.params().id();
    }
    if (request instanceof Rpc.Em1DataGetConfig em1DataGetConfig && em1DataGetConfig.params() != null) {
      return em1DataGetConfig.params().id();
    }
    return 0;
  }

  public record Em1GetStatus(
        @JsonProperty("remoteAddress") InetAddress remoteAddress,
        @JsonProperty("id") int id,
        @JsonProperty("replyTo") ActorRef<Em1GetStatusOrFailureResponse> replyTo
  ) implements Command {}

  public record Em1GetStatusOrFailureResponse(
        @JsonProperty("failure") Exception failure,
        @JsonProperty("status") Rpc.Em1GetStatusResponse status
  ) {}

  public record Em1GetConfig(
        @JsonProperty("id") int id,
        @JsonProperty("replyTo") ActorRef<Em1GetConfigOrFailureResponse> replyTo
  ) implements Command {}

  public record Em1GetConfigOrFailureResponse(
        @JsonProperty("failure") RuntimeException failure,
        @JsonProperty("status") Rpc.Em1GetConfigResponse status
  ) {}

  public record Em1DataGetConfig(
        @JsonProperty("id") int id,
        @JsonProperty("replyTo") ActorRef<Em1DataGetConfigOrFailureResponse> replyTo
  ) implements Command {}

  public record Em1DataGetConfigOrFailureResponse(
        @JsonProperty("failure") RuntimeException failure,
        @JsonProperty("status") Rpc.Em1DataGetConfigResponse status
  ) {}

  public record Em1DataGetStatus(
        @JsonProperty("remoteAddress") InetAddress remoteAddress,
        @JsonProperty("id") int id,
        @JsonProperty("replyTo") ActorRef<Em1DataGetStatusOrFailureResponse> replyTo
  ) implements Command {}

  public record Em1DataGetStatusOrFailureResponse(
        @JsonProperty("failure") RuntimeException failure,
        @JsonProperty("status") Rpc.Em1DataGetStatusResponse status
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ShellyProEMStatus(
        @JsonProperty("ble") Rpc.BleGetStatusResponse ble,
        @JsonProperty("bthome") Rpc.BtHomeGetStatusResponse bthome,
        @JsonProperty("cloud") Rpc.CloudGetStatusResponse cloud,
        @JsonProperty("em1:0") Rpc.Em1GetStatusResponse em10,
        @JsonProperty("em1:1") Rpc.Em1GetStatusResponse em11,
        @JsonProperty("em1data:0") Rpc.Em1DataGetStatusResponse em1data0,
        @JsonProperty("em1data:1") Rpc.Em1DataGetStatusResponse em1data1,
        @JsonProperty("eth") Rpc.EthGetStatusResponse eth,
        @JsonProperty("modbus") Rpc.ModbusGetStatusResponse modbus,
        @JsonProperty("mqtt") Rpc.MqttGetStatusResponse mqtt,
        @JsonProperty("sys") Rpc.SysGetStatusResponse sys,
        @JsonProperty("temperature:0") Rpc.TemperatureGetStatusResponse temperature0,
        @JsonProperty("wifi") Rpc.WifiGetStatusResponse wifi,
        @JsonProperty("ws") Rpc.WsGetStatusResponse ws
  ) implements Rpc.Response {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ShellyProEMConfig(
        @JsonProperty("ble") Rpc.BleGetConfigResponse ble,
        @JsonProperty("cloud") Rpc.CloudGetConfigResponse cloud,
        @JsonProperty("em1:0") Rpc.Em1GetConfigResponse em10,
        @JsonProperty("em1:1") Rpc.Em1GetConfigResponse em11,
        @JsonProperty("em1data:0") Rpc.Em1DataGetConfigResponse em1data0,
        @JsonProperty("em1data:1") Rpc.Em1DataGetConfigResponse em1data1,
        @JsonProperty("sys") Rpc.SysGetConfigResponse sys,
        @JsonProperty("temperature:0") Rpc.TemperatureGetConfigResponse temperature0,
        @JsonProperty("wifi") Rpc.WifiGetConfigResponse wifi,
        @JsonProperty("ws") Rpc.WsGetConfigResponse ws
  ) implements Rpc.Response {}
}
