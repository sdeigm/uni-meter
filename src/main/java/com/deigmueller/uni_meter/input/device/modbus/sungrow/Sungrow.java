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
    private double voltageL1 = 0.0;
    private double voltageL2 = 0.0;
    private double voltageL3 = 0.0;
    private double currentL1 = 0.0;
    private double currentL2 = 0.0;
    private double currentL3 = 0.0;
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
          .onMessage(ReadVoltageSucceeded.class, this::onReadVoltageSucceeded)
          .onMessage(ReadCurrentSucceeded.class, this::onReadCurrentSucceeded)
          .onMessage(ReadExportedEnergySucceeded.class, this::onReadExportedEnergySucceeded)
          .onMessage(ReadImportedEnergySucceeded.class, this::onReadImportedEnergySucceeded);
  }

  @Override
  protected @NotNull Behavior<Command> onConnectSucceeded(@NotNull NotifyConnectSucceeded message) {
    logger.trace("Sungrow.onConnectSucceeded()");
    super.onConnectSucceeded(message);

    readVoltage();

    return Behaviors.same();
  }

  protected @NotNull Behavior<Command> onReadVoltageSucceeded(@NotNull ReadVoltageSucceeded message) {
    logger.trace("Sungrow.onReadVoltageSucceeded()");

    try {
      ByteBuffer byteBuffer = ByteBuffer.wrap(message.response.registers());
      
      voltageL1 = readUInt16(byteBuffer) * 0.1;
      voltageL2 = readUInt16(byteBuffer) * 0.1;
      voltageL3 = readUInt16(byteBuffer) * 0.1;

      readCurrent();
    } catch (Exception exception) {
      logger.error("failed to convert voltage", exception);
      startNextPollingTimer();
    }

    return Behaviors.same();
  }
  
  protected @NotNull Behavior<Command> onReadCurrentSucceeded(@NotNull ReadCurrentSucceeded message) {
    logger.trace("Sungrow.onReadCurrentSucceeded()");

    try {
      ByteBuffer byteBuffer = ByteBuffer.wrap(message.response.registers());

      currentL1 = byteBuffer.getShort() * 0.1; 
      currentL2 = byteBuffer.getShort() * 0.1;
      currentL3 = byteBuffer.getShort() * 0.1;
      
      readExportedEnergy();
    } catch (Exception exception) {
      logger.error("failed to convert current", exception);
      startNextPollingTimer();
    }
    
    return Behaviors.same();
  }
  
  protected @NotNull Behavior<Command> onReadExportedEnergySucceeded(@NotNull ReadExportedEnergySucceeded message) {
    logger.trace("Sungrow.onReadExportedEnergySucceeded()");

    try {
      ByteBuffer byteBuffer = ByteBuffer.wrap(message.response.registers());

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
      ByteBuffer byteBuffer = ByteBuffer.wrap(message.response.registers());

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
                powerSign * currentL1 * voltageL1,
                powerSign * currentL1 * voltageL1,
                1.0,
                currentL1,
                voltageL1,
                getDefaultFrequency()),
          new OutputDevice.PowerData(
                powerSign * currentL2 * voltageL2,
                powerSign * currentL2 * voltageL2,
                1.0,
                currentL2,
                voltageL2,
                getDefaultFrequency()),
          new OutputDevice.PowerData(
                powerSign * currentL3 * voltageL3,
                powerSign * currentL3 * voltageL3,
                1.0,
                currentL3,
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

    readVoltage();

    return Behaviors.same();
  }
  
  private void readVoltage() {
    logger.trace("Sungrow.readVoltage()");

    // Read voltage 
    getClient()
          .readInputRegistersAsync(getUnitId(), new ReadInputRegistersRequest(5018, 3))
          .whenComplete((response, throwable) -> {
      if (throwable != null) {
        getContext().getSelf().tell(new ReadInputRegistersFailed(0x0000, 0x0002, throwable));
      } else {
        getContext().getSelf().tell(new ReadVoltageSucceeded(response));
      }
    });
  }

  private void readCurrent() {
    logger.trace("Sungrow.readCurrent()");

    // Read voltage 
    getClient()
          .readInputRegistersAsync(getUnitId(), new ReadInputRegistersRequest(13030, 3))
          .whenComplete((response, throwable) -> {
            if (throwable != null) {
              getContext().getSelf().tell(new ReadInputRegistersFailed(0x0000, 0x0002, throwable));
            } else {
              getContext().getSelf().tell(new ReadCurrentSucceeded(response));
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

  public record ReadVoltageSucceeded(
        @NotNull ReadInputRegistersResponse response
  ) implements Command {}

  public record ReadCurrentSucceeded(
        @NotNull ReadInputRegistersResponse response
  ) implements Command {}

  public record ReadExportedEnergySucceeded(
        @NotNull ReadInputRegistersResponse response
  ) implements Command {}

  public record ReadImportedEnergySucceeded(
        @NotNull ReadInputRegistersResponse response
  ) implements Command {}
}
