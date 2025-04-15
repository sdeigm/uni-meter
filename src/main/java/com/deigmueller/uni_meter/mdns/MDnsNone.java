package com.deigmueller.uni_meter.mdns;

import com.typesafe.config.Config;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MDnsNone extends MDnsKind {
  // Class members
  public static final String TYPE = "none";
  private final static Logger LOGGER = LoggerFactory.getLogger("uni-meter.mdns.none");

  public static Behavior<Command> create(@NotNull Config config) {
    return Behaviors.setup(context -> new MDnsNone(context, config));
  }

  protected MDnsNone(@NotNull ActorContext<Command> context,
                      @NotNull Config config) {
    super(context, config);
  }

  @Override
  protected Behavior<Command> onRegisterService(@NotNull RegisterService registerService) {
    LOGGER.trace("MDnsNone.onRegisterService()");
    return Behaviors.same();
  }
}
