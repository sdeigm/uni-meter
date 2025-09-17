package com.deigmueller.uni_meter.input.device.modbus.huawei;

import com.deigmueller.uni_meter.common.utils.MathUtils;
import com.deigmueller.uni_meter.input.device.modbus.Modbus;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.digitalpetri.modbus.pdu.ReadHoldingRegistersRequest;
import com.digitalpetri.modbus.pdu.ReadHoldingRegistersResponse;
import com.typesafe.config.Config;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.time.Duration;

public class HuaweiInverter extends Modbus {
  // Class members
  public static final String TYPE = "HuaweiInverter";

  // Instance members
  private final int baseRegisterAddress = getConfig().getInt("base-register-address");
  private final double powerSign = getConfig().getBoolean("invert-power") ? -1.0 : 1.0;
  private final Duration connectDelay = getConfig().getDuration("connect-delay");

  public static Behavior<Command> create(@NotNull ActorRef<OutputDevice.Command> outputDevice,
                                         @NotNull Config config) {
    return Behaviors.setup(context -> new HuaweiInverter(context, outputDevice, config));
  }

  protected HuaweiInverter(@NotNull ActorContext<Command> context,
                           @NotNull ActorRef<OutputDevice.Command> outputDevice,
                           @NotNull Config config) {
    super(context,
          outputDevice,
          config.withFallback(context.getSystem().settings().config().getConfig("uni-meter.input-devices.modbus")));
  }

  @Override
  public @NotNull ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onMessage(ConnectDelayElapsed.class, this::onConnectDelayElapsed)
          .onMessage(ReadMeterDataSucceeded.class, this::onReadMetaDataSucceeded);
  }

  @Override
  protected @NotNull Behavior<Command> onConnectSucceeded(@NotNull NotifyConnectSucceeded message) {
    logger.trace("Huawei.onConnectSucceeded()");
    super.onConnectSucceeded(message);

    getContext().getSystem().scheduler().scheduleOnce(
          connectDelay,
          () -> getContext().getSelf().tell(ConnectDelayElapsed.INSTANCE),
          getContext().getExecutionContext());

    return Behaviors.same();
  }

  @Override
  protected @NotNull Behavior<Command> onStartNextPollingCycle(@NotNull StartNextPollingCycle message) {
    logger.trace("Huawei.onStartNextPollingCycle()");

    readMeterData();

    return Behaviors.same();
  }
  
  private @NotNull Behavior<Command> onConnectDelayElapsed(@NotNull ConnectDelayElapsed message) {
    logger.trace("Huawei.onConnectDelayElapsed()");

    readMeterData();

    return Behaviors.same();
  }

  protected @NotNull Behavior<Command> onReadMetaDataSucceeded(@NotNull ReadMeterDataSucceeded message) {
    logger.trace("Huawei.onReadMetaDataSucceeded()");

    try {
      ByteBuffer byteBuffer = ByteBuffer.wrap(message.response.registers());
      
      if (byteBuffer.get() == 0) {
        logger.debug("the connected smart meter is offline");
      } else {
        double voltageA = byteBuffer.getInt() / 10.0;
        double voltageB = byteBuffer.getInt() / 10.0;
        double voltageC = byteBuffer.getInt() / 10.0;
        double currentA = byteBuffer.getInt() / 100.0;
        double currentB = byteBuffer.getInt() / 100.0;
        double currentC = byteBuffer.getInt() / 100.0;
        
        double activePower = powerSign * byteBuffer.getInt();
        double reactivePower = powerSign * byteBuffer.getInt();
        double powerFactor = byteBuffer.getShort() / 1000.0;
        double gridFrequency = byteBuffer.getShort() / 100.0;

        double energyExported = byteBuffer.getInt() / 100.0;
        double energyImported = byteBuffer.getInt() / 100.0;

        double accumulatedReactivePower = byteBuffer.getInt() / 100.0;

        short meterType = byteBuffer.getShort();
        double abVoltage = byteBuffer.getInt() / 10.0;
        double bcVoltage = byteBuffer.getInt() / 10.0;
        double caVoltage = byteBuffer.getInt() / 10.0;

        double activePowerA =  powerSign * byteBuffer.getInt();
        double activePowerB =  powerSign * byteBuffer.getInt();
        double activePowerC =  powerSign * byteBuffer.getInt();

        short meterModelDetectionResult = byteBuffer.getShort();

        if (logger.isDebugEnabled()) {
          logger.debug("voltage A: {}", MathUtils.round(voltageA, 2));
          logger.debug("voltage B: {}", MathUtils.round(voltageB, 2));
          logger.debug("voltage C: {}", MathUtils.round(voltageC, 2));
          logger.debug("current A: {}", MathUtils.round(currentA, 2));
          logger.debug("current B: {}", MathUtils.round(currentB, 2));
          logger.debug("current C: {}", MathUtils.round(currentC, 2));
          logger.debug("active power: {}", MathUtils.round(activePower, 2));
          logger.debug("reactive power: {}", MathUtils.round(reactivePower, 2));
          logger.debug("power factor: {}", MathUtils.round(powerFactor, 4));
          logger.debug("grid frequency: {}", MathUtils.round(gridFrequency, 2));
          logger.debug("energy exported: {}", MathUtils.round(energyExported, 2));
          logger.debug("energy imported: {}", MathUtils.round(energyImported, 2));
          logger.debug("accumulated reactive power: {}", MathUtils.round(accumulatedReactivePower, 2));
          logger.debug("meter type: {}", meterType);
          logger.debug("AB voltage: {}", MathUtils.round(abVoltage, 2));
          logger.debug("BC voltage: {}", MathUtils.round(bcVoltage, 2));
          logger.debug("CA voltage: {}", MathUtils.round(caVoltage, 2));
          logger.debug("active power A: {}", MathUtils.round(activePowerA, 2));
          logger.debug("active power B: {}", MathUtils.round(activePowerB, 2));
          logger.debug("active power C: {}", MathUtils.round(activePowerC, 2));
          logger.debug("meter model detection result: {}", meterModelDetectionResult);
        }
        
        getOutputDevice().tell(new OutputDevice.NotifyPhasesPowerData(
              getNextMessageId(),
              new OutputDevice.PowerData(
                    activePowerA,
                    activePowerA,
                    powerFactor,
                    currentA,
                    voltageA,
                    gridFrequency),
              new OutputDevice.PowerData(
                    activePowerB,
                    activePowerB,
                    powerFactor,
                    currentB,
                    voltageB,
                    gridFrequency),
              new OutputDevice.PowerData(
                    activePowerC,
                    activePowerC,
                    powerFactor,
                    currentC,
                    voltageC,
                    gridFrequency),
              getOutputDeviceAckAdapter()));

        getOutputDevice().tell(new OutputDevice.NotifyPhasesEnergyData(
              getNextMessageId(),
              new OutputDevice.EnergyData(
                    energyExported / 3.0,
                    energyImported / 3.0),
              new OutputDevice.EnergyData(
                    energyExported / 3.0,
                    energyImported / 3.0),
              new OutputDevice.EnergyData(
                    energyExported / 3.0,
                    energyImported / 3.0),
              getOutputDeviceAckAdapter()));
      }
    } catch (Exception exception) {
      logger.error("failed to process meter data", exception);
    }

    startNextPollingTimer();

    return Behaviors.same();
  }

  private void readMeterData() {
    logger.trace("Huawei.readMeterData()");

    // Read voltage 
    getClient()
          .readHoldingRegistersAsync(getUnitId(), new ReadHoldingRegistersRequest(baseRegisterAddress, 39))
          .whenComplete((response, throwable) -> {
            if (throwable != null) {
              getContext().getSelf().tell(new ReadHoldingRegistersFailed(baseRegisterAddress, 39, throwable));
            } else {
              getContext().getSelf().tell(new ReadMeterDataSucceeded(response));
            }
          });
  }
  
  public enum ConnectDelayElapsed implements Command {
    INSTANCE
  }

  public record ReadMeterDataSucceeded(
        ReadHoldingRegistersResponse response
  ) implements Command {}
}
