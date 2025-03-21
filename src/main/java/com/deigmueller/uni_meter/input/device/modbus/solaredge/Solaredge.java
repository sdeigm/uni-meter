/*
 * Copyright (C) 2018-2023 layline.io GmbH <http://www.layline.io>
 */

package com.deigmueller.uni_meter.input.device.modbus.solaredge;

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

public class Solaredge extends Modbus {
  // Class members
  public static final String TYPE = "Solaredge";

  // Instance members
  private final int baseRegisterAddress = getConfig().getInt("base-register-address");
  
  
  public static Behavior<Command> create(@NotNull ActorRef<OutputDevice.Command> outputDevice,
                                         @NotNull Config config) {
    return Behaviors.setup(context -> new Solaredge(context, outputDevice, config));
  }

  protected Solaredge(@NotNull ActorContext<Command> context, 
                      @NotNull ActorRef<OutputDevice.Command> outputDevice, 
                      @NotNull Config config) {
    super(context, 
            outputDevice, 
            config.withFallback(context.getSystem().settings().config().getConfig("uni-meter.input-devices.modbus")));
  }

  @Override
  public @NotNull ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onMessage(ReadMeterDataSucceeded.class, this::onReadMetaDataSucceeded);
  }

  @Override
  protected @NotNull Behavior<Command> onConnectSucceeded(@NotNull NotifyConnectSucceeded message) {
    logger.trace("Solaredge.onConnectSucceeded()");
    super.onConnectSucceeded(message);

    readMeterData();

    return Behaviors.same();
  }

  @Override
  protected @NotNull Behavior<Command> onStartNextPollingCycle(@NotNull StartNextPollingCycle message) {
    logger.trace("Solaredge.onStartNextPollingCycle()");

    readMeterData();

    return Behaviors.same();
  }
  
  protected @NotNull Behavior<Command> onReadMetaDataSucceeded(@NotNull ReadMeterDataSucceeded message) {
    logger.trace("Solaredge.onReadMetaDataSucceeded()");

    try {
      ByteBuffer byteBuffer = ByteBuffer.wrap(message.response.registers());
      
      // 40190 - Current
      short currentSum = byteBuffer.getShort();
      short currentA = byteBuffer.getShort();
      short currentB = byteBuffer.getShort();
      short currentC = byteBuffer.getShort();
      double currentScale = Math.pow(10.0, byteBuffer.getShort());
      
      // 40195 - Line to Neutral Voltage
      short voltageLtN_Average = byteBuffer.getShort();
      short voltageLtN_A = byteBuffer.getShort();
      short voltageLtN_B = byteBuffer.getShort();
      short voltageLtN_C = byteBuffer.getShort();

      // 40199 - Line to Line Voltage
      skip(byteBuffer, 8);
      // short voltageLtL_Average
      // short voltageLtL_A
      // short voltageLtL_B
      // short voltageLtL_C
      
      double voltageScale = Math.pow(10.0, byteBuffer.getShort());

      // 40204 - Frequency
      short frequency = byteBuffer.getShort();
      
      double frequencyScale = Math.pow(10.0, byteBuffer.getShort());
      
      // 40206 - Real Power
      short realPowerSum = byteBuffer.getShort();
      short realPowerA = byteBuffer.getShort();
      short realPowerB = byteBuffer.getShort();
      short realPowerC = byteBuffer.getShort();
      
      double realPowerScale = Math.pow(10.0, byteBuffer.getShort());

      // 40211 - Apparent Power
      short apparentPowerSum = byteBuffer.getShort();
      short apparentPowerA = byteBuffer.getShort();
      short apparentPowerB = byteBuffer.getShort();
      short apparentPowerC = byteBuffer.getShort();
      
      double apparentPowerScale = Math.pow(10.0, byteBuffer.getShort());

      // 40216 - Reactive Power
      skip(byteBuffer, 10);
      // short reactivePowerSum
      // short reactivePowerA
      // short reactivePowerB
      // short reactivePowerC
      // short reactivePowerScale
      
      // 40221 - Power Factor
      short powerFactorAverage = byteBuffer.getShort();
      short powerFactorA = byteBuffer.getShort();
      short powerFactorB = byteBuffer.getShort();
      short powerFactorC = byteBuffer.getShort();
      
      double powerFactorScale = Math.pow(10.0, byteBuffer.getShort());

      // 40226 - Real Energy
      long realEnergyExportedTotal = readUInt32(byteBuffer);
      long realEnergyExportedA = readUInt32(byteBuffer);
      long realEnergyExportedB = readUInt32(byteBuffer);
      long realEnergyExportedC = readUInt32(byteBuffer);
      
      long realEnergyImportedTotal = readUInt32(byteBuffer);
      long realEnergyImportedA = readUInt32(byteBuffer);
      long realEnergyImportedB = readUInt32(byteBuffer);
      long realEnergyImportedC = readUInt32(byteBuffer);

      double realEnergyScale = Math.pow(10.0, byteBuffer.getShort());
      
      if (logger.isDebugEnabled()) {
        logger.debug("current: {}", MathUtils.round(currentSum * currentScale, 2));
        logger.debug("voltage: {}", MathUtils.round(voltageLtN_Average * voltageScale, 2));
        logger.debug("frequency: {}", MathUtils.round(frequency * frequencyScale, 2));
        logger.debug("real power: {}", MathUtils.round(realPowerSum * realPowerScale, 2));
        logger.debug("apparent power: {}", MathUtils.round(apparentPowerSum * apparentPowerScale, 2));
        logger.debug("power factor: {}", MathUtils.round(powerFactorAverage * powerFactorScale, 2));
        logger.debug("real energy exported: {}", MathUtils.round(realEnergyExportedTotal * realEnergyScale, 2));
        logger.debug("real energy imported: {}", MathUtils.round(realEnergyImportedTotal * realEnergyScale, 2));
      }
      
      getOutputDevice().tell(new OutputDevice.NotifyPhasesPowerData(
            getNextMessageId(),
            new OutputDevice.PowerData(
                  MathUtils.round(realPowerA * realPowerScale, 2),
                  MathUtils.round(apparentPowerA * apparentPowerScale, 2),
                  MathUtils.round(Math.abs(powerFactorA * powerFactorScale / 100.0), 2),
                  MathUtils.round(currentA * currentScale, 2),
                  MathUtils.round(voltageLtN_A * voltageScale, 2),
                  MathUtils.round(frequency * frequencyScale, 2)), 
            new OutputDevice.PowerData(
                  MathUtils.round(realPowerB * realPowerScale, 2),
                  MathUtils.round(apparentPowerB * apparentPowerScale, 2),
                  MathUtils.round(Math.abs(powerFactorB * powerFactorScale / 100.0), 2),
                  MathUtils.round(currentB * currentScale, 2),
                  MathUtils.round(voltageLtN_B * voltageScale, 2),
                  MathUtils.round(frequency * frequencyScale, 2)),
            new OutputDevice.PowerData(
                  MathUtils.round(realPowerC * realPowerScale, 2),
                  MathUtils.round(apparentPowerC * apparentPowerScale, 2),
                  MathUtils.round(Math.abs(powerFactorC * powerFactorScale / 100.0), 2),
                  MathUtils.round(currentC * currentScale, 2),
                  MathUtils.round(voltageLtN_C * voltageScale, 2),
                  MathUtils.round(frequency * frequencyScale, 2)),
            getOutputDeviceAckAdapter()));
      
      getOutputDevice().tell(new OutputDevice.NotifyPhasesEnergyData(
            getNextMessageId(),
            new OutputDevice.EnergyData(
                  MathUtils.round(realEnergyExportedA * realEnergyScale / 1000.0, 2),
                  MathUtils.round(realEnergyImportedA * realEnergyScale / 1000.0, 2)),
            new OutputDevice.EnergyData(
                  MathUtils.round(realEnergyExportedB * realEnergyScale / 1000.0, 2),
                  MathUtils.round(realEnergyImportedB * realEnergyScale / 1000.0, 2)),
            new OutputDevice.EnergyData(
                  MathUtils.round(realEnergyExportedC * realEnergyScale / 1000.0, 2),
                  MathUtils.round(realEnergyImportedC * realEnergyScale / 1000.0, 2)),
            getOutputDeviceAckAdapter()));
            
      startNextPollingTimer();
    } catch (Exception exception) {
      logger.error("failed to process meter data", exception);
      startNextPollingTimer();
    }

    return Behaviors.same();
  }

  private void readMeterData() {
    logger.trace("Solaredge.readMeterData()");

    // Read voltage 
    getClient()
          .readHoldingRegistersAsync(getUnitId(), new ReadHoldingRegistersRequest(baseRegisterAddress, 53))
          .whenComplete((response, throwable) -> {
            if (throwable != null) {
              getContext().getSelf().tell(new ReadHoldingRegistersFailed(baseRegisterAddress, 53, throwable));
            } else {
              getContext().getSelf().tell(new ReadMeterDataSucceeded(response));
            }
          });
  }
  
  public record ReadMeterDataSucceeded(
        ReadHoldingRegistersResponse response
  ) implements Command {}
}
