package com.deigmueller.uni_meter.input.device.modbus.sungrow;

import com.deigmueller.uni_meter.input.device.modbus.Modbus;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.digitalpetri.modbus.pdu.ReadInputRegistersRequest;
import com.digitalpetri.modbus.pdu.ReadInputRegistersResponse;
import com.typesafe.config.Config;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;

public class Sungrow extends Modbus {
  // Class members
  public static final String TYPE = "Sungrow";

  // Instance members
  private final double powerSign = getConfig().getBoolean("invert-power") ? 1.0 : -1.0;
  private double powerL1 = 0.0;
  private double powerL2 = 0.0;
  private double powerL3 = 0.0;
  private double voltageL1 = 0.0;
  private double voltageL2 = 0.0;
  private double voltageL3 = 0.0;
  private double exportedEnergy = 0.0;
  private double importedEnergy = 0.0;
    

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
          .onMessage(ReadPowerSucceeded.class, this::onReadPowerSucceeded)
          .onMessage(ReadVoltageSucceeded.class, this::onReadVoltageSucceeded)
          .onMessage(ReadExportedEnergySucceeded.class, this::onReadExportedEnergySucceeded)
          .onMessage(ReadImportedEnergySucceeded.class, this::onReadImportedEnergySucceeded);
  }

  @Override
  protected @NotNull Behavior<Command> onConnectSucceeded(@NotNull NotifyConnectSucceeded message) {
    logger.trace("Sungrow.onConnectSucceeded()");
    super.onConnectSucceeded(message);

    readPower();

    return Behaviors.same();
  }
  
  protected @NotNull Behavior<Command> onReadPowerSucceeded(@NotNull Sungrow.ReadPowerSucceeded message) {
    logger.trace("Sungrow.onReadPowerSucceeded()");

    try {
      byte[] rawData = message.response.registers();
      
      swapWords(rawData, 0);
      swapWords(rawData, 4);
      swapWords(rawData, 8);
      
      ByteBuffer byteBuffer = ByteBuffer.wrap(rawData);
      
      powerL1 = readSignedInt32(byteBuffer);
      powerL2 = readSignedInt32(byteBuffer);
      powerL3 = readSignedInt32(byteBuffer);

      readVoltage();
    } catch (Exception exception) {
      logger.error("failed to convert power", exception);
      startNextPollingTimer();
    }

    return Behaviors.same();
  }
  
  protected @NotNull Behavior<Command> onReadVoltageSucceeded(@NotNull Sungrow.ReadVoltageSucceeded message) {
    logger.trace("Sungrow.onReadVoltageSucceeded()");

    try {
      ByteBuffer byteBuffer = ByteBuffer.wrap(message.response.registers());

      voltageL1 = byteBuffer.getShort() * 0.1; 
      voltageL2 = byteBuffer.getShort() * 0.1;
      voltageL3 = byteBuffer.getShort() * 0.1;
      
      readExportedEnergy();
    } catch (Exception exception) {
      logger.error("failed to convert voltage", exception);
      startNextPollingTimer();
    }
    
    return Behaviors.same();
  }
  
  protected @NotNull Behavior<Command> onReadExportedEnergySucceeded(@NotNull ReadExportedEnergySucceeded message) {
    logger.trace("Sungrow.onReadExportedEnergySucceeded()");

    try {
      byte[] rawData = message.response.registers();
      
      swapWords(rawData, 0);
      
      ByteBuffer byteBuffer = ByteBuffer.wrap(rawData);

      exportedEnergy = readUInt32(byteBuffer) * 0.1;

      readImportedEnergy();
    } catch (Exception exception) {
      logger.error("failed to convert exported energy", exception);
      startNextPollingTimer();
    }

    return Behaviors.same();
  }
  
  protected @NotNull Behavior<Command> onReadImportedEnergySucceeded(@NotNull ReadImportedEnergySucceeded message) {
    logger.trace("Sungrow.onReadImportedEnergySucceeded()");

    try {
      byte[] rawData = message.response.registers();

      swapWords(rawData, 0);

      ByteBuffer byteBuffer = ByteBuffer.wrap(rawData);

      importedEnergy = readUInt32(byteBuffer) * 0.1;
      
      notifyOutputDevice();

    } catch (Exception exception) {
      logger.error("failed to convert imported energy", exception);
    }

    startNextPollingTimer();

    return Behaviors.same();
  }
  
  private void notifyOutputDevice() {
    logger.trace("Sungrow.notifyOutputDevice()");

    // Notify the output device with the current values
    getOutputDevice().tell(new OutputDevice.NotifyPhasesPowerData(
          getNextMessageId(),
          new OutputDevice.PowerData(
                powerL1,
                powerL1,
                1.0,
                voltageL1 != 0.0 ? powerL1 / voltageL1 : 0.0,
                voltageL1,
                getDefaultFrequency()),
          new OutputDevice.PowerData(
                powerL2,
                powerL2,
                1.0,
                voltageL2 != 0.0 ? powerL2 / voltageL2 : 0.0,
                voltageL2,
                getDefaultFrequency()),
          new OutputDevice.PowerData(
                powerL3,
                powerL3,
                1.0,
                voltageL3 != 0.0 ? powerL3 / voltageL3 : 0.0,
                voltageL3,
                getDefaultFrequency()),
          getOutputDeviceAckAdapter()));

    getOutputDevice().tell(new OutputDevice.NotifyPhasesEnergyData(
          getNextMessageId(),
          new OutputDevice.EnergyData(importedEnergy, exportedEnergy),
          new OutputDevice.EnergyData(importedEnergy, exportedEnergy),
          new OutputDevice.EnergyData(importedEnergy, exportedEnergy),
          getOutputDeviceAckAdapter()));
  }
  
  @Override
  protected @NotNull Behavior<Command> onStartNextPollingCycle(@NotNull StartNextPollingCycle message) {
    logger.trace("Sungrow.onStartNextPollingCycle()");

    readPower();

    return Behaviors.same();
  }
  
  private void readPower() {
    logger.trace("Sungrow.readPower()");

    // Read voltage 
    getClient()
          .readInputRegistersAsync(getUnitId(), new ReadInputRegistersRequest(5602, 6))
          .whenComplete((response, throwable) -> {
      if (throwable != null) {
        getContext().getSelf().tell(new ReadInputRegistersFailed(5602, 6, throwable));
      } else {
        getContext().getSelf().tell(new ReadPowerSucceeded(response));
      }
    });
  }

  private void readVoltage() {
    logger.trace("Sungrow.readVoltage()");

    // Read voltage 
    getClient()
          .readInputRegistersAsync(getUnitId(), new ReadInputRegistersRequest(5740 , 3))
          .whenComplete((response, throwable) -> {
            if (throwable != null) {
              getContext().getSelf().tell(new ReadInputRegistersFailed(5740, 3, throwable));
            } else {
              getContext().getSelf().tell(new ReadVoltageSucceeded(response));
            }
          });
  }
  
  private void readExportedEnergy() {
    logger.trace("Sungrow.readExportedEnergy()");

    // Read exported energy
    getClient()
          .readInputRegistersAsync(getUnitId(), new ReadInputRegistersRequest(13045 , 2))
          .whenComplete((response, throwable) -> {
            if (throwable != null) {
              getContext().getSelf().tell(new ReadInputRegistersFailed(0x0000, 0x0002, throwable));
            } else {
              getContext().getSelf().tell(new ReadExportedEnergySucceeded(response));
            }
          });
  }

  private void readImportedEnergy() {
    logger.trace("Sungrow.readImportedEnergy()");

    // Read exported energy
    getClient()
          .readInputRegistersAsync(getUnitId(), new ReadInputRegistersRequest(13036, 2))
          .whenComplete((response, throwable) -> {
            if (throwable != null) {
              getContext().getSelf().tell(new ReadInputRegistersFailed(0x0000, 0x0002, throwable));
            } else {
              getContext().getSelf().tell(new ReadImportedEnergySucceeded(response));
            }
          });
  }

  protected static void swapWords(byte@NotNull[] data, int offset) {
    byte temp = data[offset];
    data[offset] = data[offset + 2];
    data[offset + 2] = temp;

    temp = data[offset + 1];
    data[offset + 1] = data[offset + 3];
    data[offset + 3] = temp;
  }

  public record ReadPowerSucceeded(
        @NotNull ReadInputRegistersResponse response
  ) implements Command {}

  public record ReadVoltageSucceeded(
        @NotNull ReadInputRegistersResponse response
  ) implements Command {}

  public record ReadExportedEnergySucceeded(
        @NotNull ReadInputRegistersResponse response
  ) implements Command {}

  public record ReadImportedEnergySucceeded(
        @NotNull ReadInputRegistersResponse response
  ) implements Command {}
}
