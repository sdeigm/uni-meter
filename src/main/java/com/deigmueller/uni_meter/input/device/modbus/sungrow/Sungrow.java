package com.deigmueller.uni_meter.input.device.modbus.sungrow;

import com.deigmueller.uni_meter.input.device.modbus.Modbus;
import com.deigmueller.uni_meter.input.device.modbus.ksem.Ksem;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.typesafe.config.Config;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.DispatcherSelector;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Executor;

public class Sungrow extends Modbus {
    // Class members
    public static final String TYPE = "Sungrow";

    // Instance members
    private final Executor executor = getContext().getSystem().dispatchers().lookup(DispatcherSelector.blocking());
    private final int baseRegisterAddress = getConfig().getInt("base-register-address");
    private final double powerSign = getConfig().getBoolean("invert-power") ? 1.0 : -1.0;

    public static Behavior<Command> create(@NotNull ActorRef<OutputDevice.Command> outputDevice,
                                           @NotNull Config config) {
        return Behaviors.setup(context -> new Sungrow(context, outputDevice, config));
    }

    protected Sungrow(@NotNull ActorContext<Command> context,
                      @NotNull ActorRef<OutputDevice.Command> outputDevice,
                      @NotNull Config config) {
        super(context,
                outputDevice,
                config.withFallback(context.getSystem().settings().config().getConfig("uni-meter.input-devices.modbus")));
    }

  @Override
  public @NotNull ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onMessage(Ksem.ReadMeterDataSucceeded.class, this::onReadMetaDataSucceeded);
  }

  @Override
  protected @NotNull Behavior<Command> onConnectSucceeded(@NotNull NotifyConnectSucceeded message) {
    logger.trace("Sungrow.onConnectSucceeded()");
    super.onConnectSucceeded(message);

    readMeterData();

    return Behaviors.same();
  }

  @Override
  protected @NotNull Behavior<Command> onStartNextPollingCycle(@NotNull StartNextPollingCycle message) {
    logger.trace("Sungrow.onStartNextPollingCycle()");

    readMeterData();

    return Behaviors.same();
  }

  protected @NotNull Behavior<Command> onReadMetaDataSucceeded(@NotNull ReadMeterDataSucceeded message) {
    logger.trace("Ksem.onReadMetaDataSucceeded()");

    try {

      long activePowerL1 = activePowerPlusL1 - activePowerMinusL1;
      long apparentPowerL1 = apparentPowerPlusL1 - apparentPowerMinusL1;

      long activePowerL2 = activePowerPlusL2 - activePowerMinusL2;
      long apparentPowerL2 = apparentPowerPlusL2 - apparentPowerMinusL2;

      long activePowerL3 = activePowerPlusL3 - activePowerMinusL3;
      long apparentPowerL3 = apparentPowerPlusL3 - apparentPowerMinusL3;

      getOutputDevice().tell(new OutputDevice.NotifyPhasesPowerData(
            getNextMessageId(),
            new OutputDevice.PowerData(
                  MathUtils.round(activePowerL1 / 10.0, 2),
                  MathUtils.round(apparentPowerL1 / 10.0, 2),
                  MathUtils.round(powerFactorL1 / 1000.0, 2),
                  MathUtils.round(currentL1 / 1000.0, 2),
                  MathUtils.round(voltageL1 / 1000.0, 2),
                  MathUtils.round(supplyFrequency / 1000., 2)),
            new OutputDevice.PowerData(
                  MathUtils.round(activePowerL2 / 10.0, 2),
                  MathUtils.round(apparentPowerL2 / 10.0, 2),
                  MathUtils.round(powerFactorL2 / 1000.0, 2),
                  MathUtils.round(currentL2 / 1000.0, 2),
                  MathUtils.round(voltageL2 / 1000.0, 2),
                  MathUtils.round(supplyFrequency / 1000.0, 2)),
            new OutputDevice.PowerData(
                  MathUtils.round(activePowerL3 / 10.0, 2),
                  MathUtils.round(apparentPowerL3 / 10.0, 2),
                  MathUtils.round(powerFactorL3 / 1000.0, 2),
                  MathUtils.round(currentL3 / 1000.0, 2),
                  MathUtils.round(voltageL3 / 1000.0, 2),
                  MathUtils.round(supplyFrequency / 1000.0, 2)),
            getOutputDeviceAckAdapter()));

      getOutputDevice().tell(new OutputDevice.NotifyPhasesEnergyData(
            getNextMessageId(),
            new OutputDevice.EnergyData(
                  MathUtils.round(activeEnergyPlusL1.longValue() / 10000.0, 2),
                  MathUtils.round(activeEnergyMinusL1.longValue() / 10000.0, 2)),
            new OutputDevice.EnergyData(
                  MathUtils.round(activeEnergyPlusL2.longValue() / 10000.0, 2),
                  MathUtils.round(activeEnergyMinusL2.longValue() / 10000.0, 2)),
            new OutputDevice.EnergyData(
                  MathUtils.round(activeEnergyPlusL3.longValue() / 10000.0, 2),
                  MathUtils.round(activeEnergyMinusL3.longValue() / 10000.0, 2)),
            getOutputDeviceAckAdapter()));

      startNextPollingTimer();
    } catch (Exception exception) {
      logger.error("failed to process meter data", exception);
      startNextPollingTimer();
    }

    return Behaviors.same();
  }

  private void readMeterData() {
    logger.trace("Sungrow.readMeterData()");

    try {
      supplyFrequency = readUnsignedInt32(getClient(), 0x001A);

      activePowerPlusL1 = readUnsignedInt32(getClient(), 0x0028);
      activePowerMinusL1 = readUnsignedInt32(getClient(), 0x002A);
      apparentPowerPlusL1 = readUnsignedInt32(getClient(), 0x0038);
      apparentPowerMinusL1 = readUnsignedInt32(getClient(), 0x003A);
      currentL1 = readUnsignedInt32(getClient(), 0x003C);
      voltageL1 = readUnsignedInt32(getClient(), 0x003E);
      powerFactorL1 = readUnsignedInt32(getClient(), 0x0040);

      activePowerPlusL2 = readUnsignedInt32(getClient(), 0x0050);
      activePowerMinusL2 = readUnsignedInt32(getClient(), 0x0052);
      apparentPowerPlusL2 = readUnsignedInt32(getClient(), 0x0060);
      apparentPowerMinusL2 = readUnsignedInt32(getClient(), 0x0062);
      currentL2 = readUnsignedInt32(getClient(), 0x0064);
      voltageL2 = readUnsignedInt32(getClient(), 0x0066);
      powerFactorL2 = readUnsignedInt32(getClient(), 0x0068);

      activePowerPlusL3 = readUnsignedInt32(getClient(), 0x0078);
      activePowerMinusL3 = readUnsignedInt32(getClient(), 0x007A);
      apparentPowerPlusL3 = readUnsignedInt32(getClient(), 0x0088);
      apparentPowerMinusL3 = readUnsignedInt32(getClient(), 0x008A);
      currentL3 = readUnsignedInt32(getClient(), 0x008C);
      voltageL3 = readUnsignedInt32(getClient(), 0x008E);
      powerFactorL3 = readUnsignedInt32(getClient(), 0x0090);

      activeEnergyPlusL1 = readUnsignedInt64(getClient(), 0x0250);
      activeEnergyMinusL1 = readUnsignedInt64(getClient(), 0x0254);

      activeEnergyPlusL2 = readUnsignedInt64(getClient(), 0x02A0);
      activeEnergyMinusL2 = readUnsignedInt64(getClient(), 0x02A4);

      activeEnergyPlusL3 = readUnsignedInt64(getClient(), 0x02F0);
      activeEnergyMinusL3 = readUnsignedInt64(getClient(), 0x02F4);

      getContext().getSelf().tell(new ReadMeterDataSucceeded());
    } catch (ModbusExecutionException | ModbusResponseException | ModbusTimeoutException e) {
      getContext().getSelf().tell(new ReadHoldingRegistersFailed(0, 100, e));
    }


    public record ReadMeterDataSucceeded(
          ReadHoldingRegistersResponse response
    ) implements Command {}
  }
