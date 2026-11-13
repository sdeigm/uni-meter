package com.deigmueller.uni_meter.input;

import com.deigmueller.uni_meter.input.device.failover.Failover;
import com.deigmueller.uni_meter.input.device.generic_http.GenericHttp;
import com.deigmueller.uni_meter.input.device.home_assistant.HomeAssistant;
import com.deigmueller.uni_meter.input.device.modbus.fronius.FroniusSmartMeter;
import com.deigmueller.uni_meter.input.device.modbus.huawei.HuaweiInverter;
import com.deigmueller.uni_meter.input.device.modbus.ksem.Ksem;
import com.deigmueller.uni_meter.input.device.modbus.sdm120.Sdm120;
import com.deigmueller.uni_meter.input.device.modbus.solaredge.Solaredge;
import com.deigmueller.uni_meter.input.device.modbus.sungrow.Sungrow;
import com.deigmueller.uni_meter.input.device.mqtt.Mqtt;
import com.deigmueller.uni_meter.input.device.poweropti.Poweropti;
import com.deigmueller.uni_meter.input.device.refoss.em06p.RefossEm06p;
import com.deigmueller.uni_meter.input.device.shelly._3em.Shelly3EM;
import com.deigmueller.uni_meter.input.device.shrdzm.ShrDzm;
import com.deigmueller.uni_meter.input.device.sma.energy_meter.EnergyMeter;
import com.deigmueller.uni_meter.input.device.tasmota.Tasmota;
import com.deigmueller.uni_meter.input.device.tibber.pulse.Pulse;
import com.deigmueller.uni_meter.input.device.vzlogger.VzLogger;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.typesafe.config.Config;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.SupervisorStrategy;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;

/**
 * Factory creating the behavior of an input device from its configuration path.
 */
public final class InputDeviceFactory {
  private InputDeviceFactory() {}

  /**
   * Create the supervised behavior of the input device configured at the given path
   * @param output Output device the input device notifies
   * @param rootConfig Root configuration
   * @param inputDeviceConfigPath Configuration path of the input device (e.g. "uni-meter.input-devices.solaredge")
   * @return Supervised behavior of the input device
   */
  public static @NotNull Behavior<InputDevice.Command> createSupervised(@NotNull ActorRef<OutputDevice.Command> output,
                                                                       @NotNull Config rootConfig,
                                                                       @NotNull String inputDeviceConfigPath) {
    Duration minBackoff = rootConfig.getDuration("uni-meter.input-supervision.min-backoff");
    Duration maxBackoff = rootConfig.getDuration("uni-meter.input-supervision.max-backoff");
    double jitter = rootConfig.getDouble("uni-meter.input-supervision.jitter");

    return Behaviors.supervise(create(output, rootConfig, inputDeviceConfigPath))
          .onFailure(SupervisorStrategy.restartWithBackoff(minBackoff, maxBackoff, jitter));
  }

  /**
   * Get the type of the input device configured at the given path
   */
  public static @NotNull String getType(@NotNull Config rootConfig, @NotNull String inputDeviceConfigPath) {
    return rootConfig.getString(inputDeviceConfigPath + ".type");
  }

  private static @NotNull Behavior<InputDevice.Command> create(@NotNull ActorRef<OutputDevice.Command> output,
                                                               @NotNull Config rootConfig,
                                                               @NotNull String inputDeviceConfigPath) {
    String type = getType(rootConfig, inputDeviceConfigPath);
    Config config = rootConfig.getConfig(inputDeviceConfigPath);

    return switch (type) {
      case VzLogger.TYPE -> VzLogger.create(output, config);
      case EnergyMeter.TYPE -> EnergyMeter.create(output, config);
      case HomeAssistant.TYPE -> HomeAssistant.create(output, config);
      case com.deigmueller.uni_meter.input.device.shelly.pro3em.ShellyPro3EM.TYPE ->
            com.deigmueller.uni_meter.input.device.shelly.pro3em.ShellyPro3EM.create(output, config);
      case Shelly3EM.TYPE -> Shelly3EM.create(output, config);
      case Sdm120.TYPE -> Sdm120.create(output, config);
      case ShrDzm.TYPE -> ShrDzm.create(output, config);
      case Solaredge.TYPE -> Solaredge.create(output, config);
      case FroniusSmartMeter.TYPE -> FroniusSmartMeter.create(output, config);
      case Ksem.TYPE -> Ksem.create(output, config);
      case Pulse.TYPE -> Pulse.create(output, config);
      case Mqtt.TYPE -> Mqtt.create(output, config);
      case Tasmota.TYPE -> Tasmota.create(output, config);
      case GenericHttp.TYPE -> GenericHttp.create(output, config);
      case Sungrow.TYPE -> Sungrow.create(output, config);
      case HuaweiInverter.TYPE -> HuaweiInverter.create(output, config);
      case Poweropti.TYPE -> Poweropti.create(output, config);
      case RefossEm06p.TYPE -> RefossEm06p.create(output, config);
      case Failover.TYPE -> Failover.create(output, config);
      default -> throw new IllegalArgumentException("unknown input device type: " + type);
    };
  }
}
