package com.deigmueller.uni_meter.mdns;

import com.typesafe.config.Config;
import lombok.AccessLevel;
import lombok.Getter;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

@Getter(AccessLevel.PROTECTED)
public abstract class MDnsKind extends AbstractBehavior<MDnsKind.Command> {
  // Instance members
  private final Config config;

  protected MDnsKind(@NotNull ActorContext<Command> context,
                     @NotNull Config config) {
    super(context);
    this.config = config;
  }

  @Override
  public ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onMessage(RegisterService.class, this::onRegisterService);
  }

  @Override
  public Receive<Command> createReceive() {
    return newReceiveBuilder()
          .build();
  }
  
  protected abstract Behavior<Command> onRegisterService(@NotNull RegisterService registerService);
  
  public interface Command {}

  public record RegisterService(
        @NotNull String type,
        @NotNull String name,
        int port,
        @NotNull Map<String,String> properties,
        @NotNull String server,
        @NotNull String ipAddress
  ) implements Command {}
}
