package com.deigmueller.uni_meter.mdns;

import com.typesafe.config.Config;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
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
  private final ActorRef<MDnsKind.Command> mdnsKind;
  
  public static Behavior<Command> create() {
    return Behaviors.setup(MDnsRegistrator::new);
  }
  
  public MDnsRegistrator(@NotNull ActorContext<Command> context) {
    super(context);
    
    Config config = getContext().getSystem().settings().config().getConfig("uni-meter.mdns");
    
    Behavior<MDnsKind.Command> mdnsBehavior = switch (config.getString("type").toLowerCase()) {
      case "auto" -> autoDetectMdns(config);
      case "avahi" -> MDnsAvahi.create(config);
      case "home-assistant" -> MDnsHomeAssistant.create(config);
      default -> MDnsNone.create(config);
    };

    mdnsKind = getContext().spawnAnonymous(mdnsBehavior);
    
  }

  @Override
  public Receive<Command> createReceive() {
    return newReceiveBuilder()
          .onMessage(RegisterService.class, this::onRegisterService)
          .build();
  }
  
  private Behavior<Command> onRegisterService(@NotNull RegisterService message) {
    try {
      mdnsKind.tell(
            new MDnsKind.RegisterService(
                  message.type(),
                  message.name(),
                  message.port(),
                  message.properties()
            ));
    } catch (Exception e) {
      LOGGER.error("mdns registration failed: {}", e.getMessage());
    }
    
    return Behaviors.same();
  }

  /**
   * Try to auto-detect the mDNS announcement to use
   * @return mDNS announcement to use
   */
  private @NotNull Behavior<MDnsKind.Command> autoDetectMdns(@NotNull Config config) {
    if (System.getenv("UNI_HA_URL") != null && System.getenv("UNI_HA_ACCESS_TOKEN") != null) {
      return MDnsHomeAssistant.create(config);
    } else if (Files.exists(Paths.get(MDnsAvahi.AVAHI_SERVICES_DIR))) {
      return MDnsAvahi.create(config);
    } else {
      return MDnsNone.create(config);
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
