package com.deigmueller.uni_meter.mdns;

import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.PostStop;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

public class MDnsRegistrator extends AbstractBehavior<MDnsRegistrator.Command> {
  // Class members
  private final static Logger LOGGER = LoggerFactory.getLogger("uni-meter.mdns");
  
  // Instance members
  private final MDnsKind mdnsKind;
  private MDnsHandle mDnsHandle;
  
  public static Behavior<Command> create() {
    return Behaviors.setup(MDnsRegistrator::new);
  }
  
  public MDnsRegistrator(@NotNull ActorContext<Command> context) {
    super(context);
    
    switch (getContext().getSystem().settings().config().getString("uni-meter.mdns.type").toLowerCase()) {
      case "auto": 
        mdnsKind = autoDetectMdns();
        break;
      case "avahi": 
        mdnsKind = new MDnsAvahi(); 
        break;
      case "homeassistant":
        mdnsKind = new MDnsHomeAssistant(
              getContext().getSystem(), 
              getContext().getSystem().settings().config().getConfig("uni-meter.mdns"));
        break;
      default:
        mdnsKind = new MDnsNone();
    }
    
  }

  @Override
  public Receive<Command> createReceive() {
    return newReceiveBuilder()
          .onSignal(PostStop.class, this::onPostStop)
          .onMessage(RegisterService.class, this::onRegisterService)
          .onMessage(RegistrationSucceeded.class, this::onRegistrationSucceeded)
          .build();
  }
  
  private Behavior<Command> onPostStop(PostStop postStop) {
    if (mDnsHandle != null) {
      mdnsKind.unregister(mDnsHandle);
    }
    return Behaviors.same();
  }
  
  private Behavior<Command> onRegisterService(@NotNull RegisterService message) {
    try {
      mdnsKind
            .register(message.type(), message.name(), message.port(), message.properties())
            .thenAccept(mDnsHandle -> 
                  getContext().getSelf().tell(new RegistrationSucceeded(mDnsHandle)));
    } catch (Exception e) {
      LOGGER.error("mdns registration failed: {}", e.getMessage());
    }
    
    return Behaviors.same();
  }

  /**
   * Handle a successful mDNS registration
   * @param message Registration message
   * @return Same behavior
   */
  private Behavior<Command> onRegistrationSucceeded(@NotNull RegistrationSucceeded message) {
    mDnsHandle = message.handle();
    return Behaviors.same();
  }

  /**
   * Try to auto-detect the mDNS announcement to use
   * @return mDNS announcement to use
   */
  private @NotNull MDnsKind autoDetectMdns() {
    if (System.getenv("UNI_HA_URL") != null && System.getenv("UNI_HA_ACCESS_TOKEN") != null) {
      return new MDnsHomeAssistant(
            getContext().getSystem(),
            getContext().getSystem().settings().config().getConfig("uni-meter.mdns"));
    } else if (Files.exists(Paths.get(MDnsAvahi.AVAHI_SERVICES_DIR))) {
      return new MDnsAvahi();
    } else {
      return new MDnsNone();
    }
  }

  public interface Command {}
  
  public record RegisterService(
        @NotNull String type,
        @NotNull String name,
        int port,
        @NotNull Map<String,String> properties
  ) implements Command {}
  
  public record RegistrationSucceeded(
        @NotNull MDnsHandle handle
  ) implements Command {}
}
