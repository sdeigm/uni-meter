/*
 * Copyright (C) 2018-2023 layline.io GmbH <http://www.layline.io>
 */

package com.deigmueller.uni_meter.input.device.tibber.pulse;

import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.*;
import org.apache.pekko.http.javadsl.Http;
import org.apache.pekko.http.javadsl.ServerBuilder;
import org.apache.pekko.http.javadsl.model.ContentTypes;
import org.apache.pekko.http.javadsl.model.HttpEntities;
import org.apache.pekko.http.javadsl.model.HttpEntity;
import org.apache.pekko.http.javadsl.server.AllDirectives;
import org.apache.pekko.http.javadsl.server.Route;
import org.apache.pekko.http.javadsl.settings.ServerSettings;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class PulseTextTestServer {
  public static Logger LOGGER = LoggerFactory.getLogger(PulseTextTestServer.class);

  public static void main(String[] args) {
    ActorSystem<RootActor.Command> actorSystem = ActorSystem.create(
          Behaviors.setup(context -> RootActor.create()), "pulse");

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
      return pathPrefix("data.json", () ->
            get(this::onGet)
      );
    }
    private static final HttpEntity.Strict FIXED_ENTITY =
          HttpEntities.create(
                ContentTypes.APPLICATION_JSON,
                """
                1-0:0.0.0*255(1EBZ0101937879)\r
                1-0:96.1.0*255(1EBZ0101937879)\r
                1-0:1.8.0*255(003902.58359727*kWh)\r
                1-0:2.8.0*255(001267.59172376*kWh)\r
                1-0:16.7.0*255(000458.90*W)\r
                1-0:36.7.0*255(000344.88*W)\r
                1-0:56.7.0*255(000114.02*W)\r
                1-0:76.7.0*255(000000.00*W)\r
                1-0:96.5.0*255(001C0104)\r
                0-0:96.8.0*255(055BD83E)\r
                """);


    private Route onGet() {
      return complete(FIXED_ENTITY);
    }
  }
}
