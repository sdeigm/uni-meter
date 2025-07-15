package com.deigmueller.uni_meter.input.device.amis;

import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.*;
import org.apache.pekko.http.javadsl.Http;
import org.apache.pekko.http.javadsl.ServerBuilder;
import org.apache.pekko.http.javadsl.marshallers.jackson.Jackson;
import org.apache.pekko.http.javadsl.server.AllDirectives;
import org.apache.pekko.http.javadsl.server.Route;
import org.apache.pekko.http.javadsl.settings.ServerSettings;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

class AmisTestServer {
  public static Logger LOGGER = LoggerFactory.getLogger(AmisTestServer.class);
  
  public static void main(String[] args) {
    ActorSystem<RootActor.Command> actorSystem = ActorSystem.create(
          Behaviors.setup(context -> RootActor.create()), "amis");

    final Http http = Http.get(actorSystem);

    ServerSettings serverSettings = ServerSettings.create(Adapter.toClassic(actorSystem));

    ServerBuilder serverBuilder = http.newServerAt("127.0.0.1", 4242)
          .withSettings(serverSettings);

    serverBuilder
          .bind(new MainRoute().create())
          .whenComplete((binding, throwable) -> {
            if (throwable != null) {
              LOGGER.error("bind failed:", throwable);
            } else {
              LOGGER.info("server started at {}", binding.localAddress());
            }
          });
  }
  
  private static class RootActor extends AbstractBehavior<RootActor.Command> {
    public static Behavior<Command> create() {
      return Behaviors.setup(RootActor::new);
    }
    
    public RootActor(@NotNull ActorContext<Command> context) {
      super(context);
    }

    @Override
    public Receive<Command> createReceive() {
      return newReceiveBuilder().build();
    }

    public interface Command {}
  }
  
  private static class MainRoute extends AllDirectives {
    public Route create() {
      return path("rest", () -> 
            get(this::onGetRest)
      );
    }
    
    private Route onGetRest() {
      Map<String, Long> result = new HashMap<>();
      result.put("1.8.0", 10153293L);
      result.put("2.8.0", 16837023L);
      result.put("3.8.1", 1711844L);
      result.put("4.8.1", 2516731L);
      result.put("1.7.0", 2472L);
      result.put("2.7.0", 2421L);
      result.put("3.7.0", 0L);
      result.put("4.7.0", 1150L);
      result.put("1.128.0", 0L);
      result.put("saldo", 51L);
      
      return completeOK(
            result,
            Jackson.marshaller()
      );
    }
  }
}