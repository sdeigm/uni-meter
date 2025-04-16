package com.deigmueller.uni_meter.input;

import com.deigmueller.uni_meter.output.OutputDevice;
import com.typesafe.config.Config;
import lombok.AccessLevel;
import lombok.Getter;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.NotNull;

@Getter(AccessLevel.PROTECTED)
public abstract class InputDevice extends AbstractBehavior<InputDevice.Command> {
  // Class members
  public static final String PHASE_MODE_MONO = "mono-phase";
  public static final String PHASE_MODE_TRI = "tri-phase";

  // Instance members
  protected final Logger logger = LoggerFactory.getLogger("uni-meter.input");
  private final ActorRef<OutputDevice.Ack> outputDeviceAckAdapter = getContext().messageAdapter(
        OutputDevice.Ack.class, WrappedOutputDeviceAck::new);
  private final ActorRef<OutputDevice.Command> outputDevice;
  private final Config config;
  private final double defaultVoltage;
  private final double defaultFrequency;
  private final OutputDevice.PowerData nullPowerData;
  private final OutputDevice.EnergyData nullEnergyData;
  private int nextMessageId = 1;

  protected InputDevice(@NotNull ActorContext<Command> context,
                        @NotNull ActorRef<OutputDevice.Command> outputDevice,
                        @NotNull Config config) {
    super(context);
    this.outputDevice = outputDevice;
    this.config = config;
    this.defaultVoltage = config.hasPath("default-voltage") ? getConfig().getDouble("default-voltage") : 230.0;
    this.defaultFrequency = config.hasPath("default-frequency") ? config.getDouble("default-frequency") : 50.0;
    this.nullPowerData = new OutputDevice.PowerData(0.0, 0.0, 1.0, 0.0, defaultVoltage, defaultFrequency);
    this.nullEnergyData = new OutputDevice.EnergyData(0, 0);
  }
  
  @Override
  public Receive<Command> createReceive() {
    return newReceiveBuilder()
          .onMessage(WrappedOutputDeviceAck.class, this::onWrappedOutputDeviceAck)
          .build();
  }
  
  protected @NotNull Behavior<Command> onWrappedOutputDeviceAck(@NotNull WrappedOutputDeviceAck wrappedOutputDeviceAck) {
    logger.trace("InputDevice.onWrappedOutputDeviceAck()");
    return Behaviors.same();
  }

  protected void notifyPowerData(@NotNull PhaseMode powerPhaseMode,
                                 @NotNull String powerPhase,
                                 double power) {
    logger.trace("Pulse.notifyPowerData()");

    OutputDevice.PowerData powerData = new OutputDevice.PowerData(
          power,  //act_power
          power,  //aprt_power
          1.0,
          power/defaultVoltage,   //act_current
          defaultVoltage,
          defaultFrequency);

    if (powerPhaseMode == PhaseMode.TRI) {
      getOutputDevice().tell(
            new OutputDevice.NotifyTotalPowerData(
                  getNextMessageId(),
                  powerData,
                  getOutputDeviceAckAdapter()));
    } else {
      getOutputDevice().tell(new OutputDevice.NotifyPhasesPowerData(
            getNextMessageId(),
            powerPhase.equals("l1") ? powerData : nullPowerData,
            powerPhase.equals("l2") ? powerData : nullPowerData,
            powerPhase.equals("l3") ? powerData : nullPowerData,
            getOutputDeviceAckAdapter()));
    }
  }

  protected void notifyEnergyData(@NotNull PhaseMode energyPhaseMode, 
                                  @NotNull String energyPhase,
                                  double energyImport, 
                                  double energyExport) {
    logger.trace("Pulse.notifyEnergyData()");

    OutputDevice.EnergyData energyData = new OutputDevice.EnergyData(
          energyImport,
          energyExport);

    if (energyPhaseMode == PhaseMode.TRI) {
      getOutputDevice().tell(
            new OutputDevice.NotifyTotalEnergyData(
                  getNextMessageId(),
                  energyData,
                  getOutputDeviceAckAdapter()));
    } else {
      getOutputDevice().tell(
            new OutputDevice.NotifyPhasesEnergyData(
                  getNextMessageId(),
                  energyPhase.equals("l1") ? energyData : nullEnergyData,
                  energyPhase.equals("l2") ? energyData : nullEnergyData,
                  energyPhase.equals("l3") ? energyData : nullEnergyData,
                  getOutputDeviceAckAdapter()));
    }
  }

  protected @NotNull PhaseMode getPhaseMode(@NotNull String key) {
    String value = getConfig().getString(key);

    if (PHASE_MODE_MONO.compareToIgnoreCase(value) == 0) {
      return PhaseMode.MONO;
    } else if (PHASE_MODE_TRI.compareToIgnoreCase(value) == 0) {
      return PhaseMode.TRI;
    } else {
      throw new IllegalArgumentException("unknown phase mode: " + value);
    }
  }
  
  protected int getNextMessageId() {
    return nextMessageId++;
  }
  
  public interface Command {}
  
  public record WrappedOutputDeviceAck(
    @NotNull OutputDevice.Ack ack
  ) implements Command {}

  public enum PhaseMode {
    MONO,
    TRI
  }
}
