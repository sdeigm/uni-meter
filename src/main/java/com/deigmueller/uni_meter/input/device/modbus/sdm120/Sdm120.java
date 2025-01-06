package com.deigmueller.uni_meter.input.device.modbus.sdm120;

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

public class Sdm120 extends Modbus {
  // Class members
  public static final String TYPE = "SDM120";
   
  // Instance members
  private float voltage;
  private float current;
  private float activePower;
  private float apparentPower;
  private float powerFactor;
  private float importActiveEnergy;


  public static Behavior<Command> create(@NotNull ActorRef<OutputDevice.Command> outputDevice,
                                         @NotNull Config config) {
    return Behaviors.setup(context -> new Sdm120(context, outputDevice, config));
  }
  
  protected Sdm120(@NotNull ActorContext<Command> context,
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
          .onMessage(ReadActivePowerSucceeded.class, this::onReadActivePowerSucceeded)
          .onMessage(ReadApparentPowerSucceeded.class, this::onReadApparentPowerSucceeded)
          .onMessage(ReadPowerFactorSucceeded.class, this::onReadPowerFactorSucceeded)
          .onMessage(ReadFrequencySucceeded.class, this::onReadFrequencySucceeded)
          .onMessage(ReadImportActiveEnergySucceeded.class, this::onReadImportActiveEnergySucceeded)
          .onMessage(ReadExportActiveEnergySucceeded.class, this::onReadExportActiveEnergySucceeded);
  }

  @Override
  protected @NotNull Behavior<Command> onConnectSucceeded(@NotNull NotifyConnectSucceeded message) {
    logger.trace("Sdm120.onConnectSucceeded()");
    super.onConnectSucceeded(message);
    
    readVoltage();
    
    return Behaviors.same();
  }

  @Override
  protected @NotNull Behavior<Command> onStartNextPollingCycle(@NotNull StartNextPollingCycle message) {
    logger.trace("Sdm120.onStartNextPollingCycle()");

    readVoltage();

    return Behaviors.same();
  }

  protected @NotNull Behavior<Command> onReadVoltageSucceeded(@NotNull ReadVoltageSucceeded message) {
    logger.trace("Sdm120.onReadVoltageSucceeded()");
    
    try {
      voltage = bytesToFloat(message.response().registers());
      logger.debug("voltage: {}", voltage);
      
      readCurrent();
    } catch (Exception exception) {
      logger.error("failed to convert voltage", exception);
      startNextPollingTimer();
    }
    
    return Behaviors.same();
  }

  protected @NotNull Behavior<Command> onReadCurrentSucceeded(@NotNull ReadCurrentSucceeded message) {
    logger.trace("Sdm120.onReadCurrentSucceeded()");

    try {
      current = bytesToFloat(message.response().registers());
      logger.debug("current: {}", current);

      readActivePower();

    } catch (Exception exception) {
      logger.error("failed to convert voltage", exception);
      startNextPollingTimer();
    }

    return Behaviors.same();
  }
  
  protected @NotNull Behavior<Command> onReadActivePowerSucceeded(@NotNull ReadActivePowerSucceeded message) {
    logger.trace("Sdm120.onReadActivePowerSucceeded()");
    
    try {
      activePower = bytesToFloat(message.response().registers());
      logger.debug("active power: {}", activePower);
      
      readApparentPower();
    } catch (Exception exception) {
      logger.error("failed to convert active power", exception);
      startNextPollingTimer();
    }
    
    return Behaviors.same();
  }
  
  protected @NotNull Behavior<Command> onReadApparentPowerSucceeded(@NotNull ReadApparentPowerSucceeded message) {
    logger.trace("Sdm120.onReadApparentPowerSucceeded()");
    
    try {
      apparentPower = bytesToFloat(message.response().registers());
      logger.debug("apparent power: {}", apparentPower);
      
      readPowerFactor();
    } catch (Exception exception) {
      logger.error("failed to convert apparent power", exception);
    }
    
    return Behaviors.same();
  }
  
  protected @NotNull Behavior<Command> onReadPowerFactorSucceeded(@NotNull ReadPowerFactorSucceeded message) {
    logger.trace("Sdm120.onReadPowerFactorSucceeded()");
    
    try {
      powerFactor = bytesToFloat(message.response().registers());
      logger.debug("power factor: {}", powerFactor);
      
      readFrequency();
    } catch (Exception exception) {
      logger.error("failed to convert power factor", exception);
    }
    
    return Behaviors.same();
  }
  
  protected @NotNull Behavior<Command> onReadFrequencySucceeded(@NotNull ReadFrequencySucceeded message) {
    logger.trace("Sdm120.onReadFrequencySucceeded()");
    
    try {
      float frequency = bytesToFloat(message.response().registers());
      logger.debug("frequency: {}", frequency);
      
      getOutputDevice().tell(new OutputDevice.NotifyTotalPowerData(
            getNextMessageId(),
            new OutputDevice.PowerData(activePower, apparentPower, powerFactor, current, voltage, frequency),
            getOutputDeviceAckAdapter()));
      
      readImportActiveEnergy();
    } catch (Exception exception) {
      logger.error("failed to convert frequency", exception);
    }
    
    return Behaviors.same();
  }
  
  protected @NotNull Behavior<Command> onReadImportActiveEnergySucceeded(@NotNull ReadImportActiveEnergySucceeded message) {
    logger.trace("Sdm120.onReadImportActiveEnergySucceeded()");
    
    try {
      importActiveEnergy = bytesToFloat(message.response().registers());
      logger.debug("import active energy: {}", importActiveEnergy);
      
      readExportActiveEnergy();
    } catch (Exception exception) {
      logger.error("failed to convert import active energy", exception);
    }
    
    return Behaviors.same();
  }
  
  protected @NotNull Behavior<Command> onReadExportActiveEnergySucceeded(@NotNull ReadExportActiveEnergySucceeded message) {
    logger.trace("Sdm120.onReadExportActiveEnergySucceeded()");
    
    try {
      float exportActiveEnergy = bytesToFloat(message.response().registers());
      logger.debug("export active energy: {}", exportActiveEnergy);
      
      getOutputDevice().tell(new OutputDevice.NotifyTotalEnergyData(
            getNextMessageId(),
            new OutputDevice.EnergyData(importActiveEnergy, exportActiveEnergy),
            getOutputDeviceAckAdapter()));
      
      startNextPollingTimer();
    } catch (Exception exception) {
      logger.error("failed to convert export active energy", exception);
    }
    
    return Behaviors.same();
  }

  private void readVoltage() {
    logger.trace("Sdm120.readVoltage()");
    
    // Read voltage 
    getClient().readInputRegistersAsync(0, new ReadInputRegistersRequest(0x0000, 0x0002)).whenComplete((response, throwable) -> {
      if (throwable != null) {
        getContext().getSelf().tell(new ReadInputRegistersFailed(0x0000, 0x0002, throwable));
      } else {
        getContext().getSelf().tell(new ReadVoltageSucceeded(response));
      }
    });
  }
  
  private void readCurrent() {
    logger.trace("Sdm120.readCurrent()");
    
    // Read current
    getClient().readInputRegistersAsync(0, new ReadInputRegistersRequest(0x0006, 0x0002)).whenComplete((response, throwable) -> {
      if (throwable != null) {
        getContext().getSelf().tell(new ReadInputRegistersFailed(0x0006, 0x0002, throwable));
      } else {
        getContext().getSelf().tell(new ReadCurrentSucceeded(response));
      }
    });
  }
  
  private void readActivePower() {
    logger.trace("Sdm120.readActivePower()");
    
    // Read active power
    getClient().readInputRegistersAsync(0, new ReadInputRegistersRequest(0x000C, 0x0002)).whenComplete((response, throwable) -> {
      if (throwable != null) {
        getContext().getSelf().tell(new ReadInputRegistersFailed(0x000C, 0x0002, throwable));
      } else {
        getContext().getSelf().tell(new ReadActivePowerSucceeded(response));
      }
    });
  }
  
  private void readApparentPower() {
    logger.trace("Sdm120.readApparentPower()");
    
    // Read apparent power
    getClient().readInputRegistersAsync(0, new ReadInputRegistersRequest(0x0012, 0x0002)).whenComplete((response, throwable) -> {
      if (throwable != null) {
        getContext().getSelf().tell(new ReadInputRegistersFailed(0x0012, 0x0002, throwable));
      } else {
        getContext().getSelf().tell(new ReadApparentPowerSucceeded(response));
      }
    });
  }
  
  private void readPowerFactor() {
    logger.trace("Sdm120.readPowerFactor()");
    
    // Read power factor
    getClient().readInputRegistersAsync(0, new ReadInputRegistersRequest(0x0036, 0x0002)).whenComplete((response, throwable) -> {
      if (throwable != null) {
        getContext().getSelf().tell(new ReadInputRegistersFailed(0x0036, 0x0002, throwable));
      } else {
        getContext().getSelf().tell(new ReadPowerFactorSucceeded(response));
      }
    });
  }
  
  private void readFrequency() {
    logger.trace("Sdm120.readFrequency()");
    
    // Read frequency
    getClient().readInputRegistersAsync(0, new ReadInputRegistersRequest(0x0046, 0x0002)).whenComplete((response, throwable) -> {
      if (throwable != null) {
        getContext().getSelf().tell(new ReadInputRegistersFailed(0x0046, 0x0002, throwable));
      } else {
        getContext().getSelf().tell(new ReadFrequencySucceeded(response));
      }
    });
  }
  
  private void readImportActiveEnergy() {
    logger.trace("Sdm120.readImportActiveEnergy()");
    
    // Read import active energy
    getClient().readInputRegistersAsync(0, new ReadInputRegistersRequest(0x0048, 0x0002)).whenComplete((response, throwable) -> {
      if (throwable != null) {
        getContext().getSelf().tell(new ReadInputRegistersFailed(0x0048, 0x0002, throwable));
      } else {
        getContext().getSelf().tell(new ReadImportActiveEnergySucceeded(response));
      }
    });
  }
  
  private void readExportActiveEnergy() {
    logger.trace("Sdm120.readExportActiveEnergy()");
    
    // Read export active energy
    getClient().readInputRegistersAsync(0, new ReadInputRegistersRequest(0x004A, 0x0002)).whenComplete((response, throwable) -> {
      if (throwable != null) {
        getContext().getSelf().tell(new ReadInputRegistersFailed(0x004A, 0x0002, throwable));
      } else {
        getContext().getSelf().tell(new ReadExportActiveEnergySucceeded(response));
      }
    });
  }
  
  public record ReadVoltageSucceeded(
        ReadInputRegistersResponse response
  ) implements Command {}
  
  public record ReadCurrentSucceeded(
        ReadInputRegistersResponse response
  ) implements Command {}
  
  public record ReadActivePowerSucceeded(
        ReadInputRegistersResponse response
  ) implements Command {}

  public record ReadApparentPowerSucceeded(
        ReadInputRegistersResponse response
  ) implements Command {}
  
  public record ReadPowerFactorSucceeded(
        ReadInputRegistersResponse response
  ) implements Command {}
  
  public record ReadFrequencySucceeded(
        ReadInputRegistersResponse response
  ) implements Command {}
  
  public record ReadImportActiveEnergySucceeded(
        ReadInputRegistersResponse response
  ) implements Command {}
  
  public record ReadExportActiveEnergySucceeded(
        ReadInputRegistersResponse response
  ) implements Command {}
}
