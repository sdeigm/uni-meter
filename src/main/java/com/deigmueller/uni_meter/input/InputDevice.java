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
  
  // Instance member
  private final ActorRef<OutputDevice.Ack> outputDeviceAckAdapter = getContext().messageAdapter(
        OutputDevice.Ack.class, WrappedOutputDeviceAck::new);
  private final ActorRef<OutputDevice.Command> outputDevice;
  private final Config config;
  private int nextMessageId = 1;

  protected   InputDevice(@NotNull ActorContext<Command> context,
                        @NotNull ActorRef<OutputDevice.Command> outputDevice,
                        @NotNull Config config) {
    super(context);
    this.outputDevice = outputDevice;
    this.config = config;
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
