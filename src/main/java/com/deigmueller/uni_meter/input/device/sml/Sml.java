package com.deigmueller.uni_meter.input.device.sml;

import com.deigmueller.uni_meter.input.InputDevice;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.typesafe.config.Config;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.jetbrains.annotations.NotNull;

public class Sml extends InputDevice {
  public static Behavior<Command> create(@NotNull ActorRef<OutputDevice.Command> outputDevice,
                                         @NotNull Config config) {
    return Behaviors.setup(context -> new Sml(context, outputDevice, config));
  }
  
  protected Sml(@NotNull ActorContext<Command> context,
                @NotNull ActorRef<OutputDevice.Command> outputDevice,
                @NotNull Config config) {
    super(context, outputDevice, config);
  }
}
