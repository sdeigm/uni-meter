/*
 * Copyright (C) 2018-2023 layline.io GmbH <http://www.layline.io>
 */

package com.deigmueller.uni_meter.input.device.poweropti;

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


public class PoweroptiTestServer {
  public static Logger LOGGER = LoggerFactory.getLogger(PoweroptiTestServer.class);

  public static void main(String[] args) {
    ActorSystem<RootActor.Command> actorSystem = ActorSystem.create(
          Behaviors.setup(context -> RootActor.create()), "poweropti");

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
      return pathPrefix("value", () ->
            headerValueByName("X-API-KEY", xApiKey ->
              get(() -> onGet(xApiKey))
            )
      );
    }
    private static final HttpEntity.Strict ENTITY =
          HttpEntities.create(
                ContentTypes.APPLICATION_JSON,
                """
                {
                  "timestamp": 1757053304,
                  "values": [
                    { "obis": "1.7.0", "value": 228 },
                    { "obis": "1.8.0", "value": 17784955 },
                    { "obis": "1.8.1", "value": 17784955 },
                    { "obis": "1.8.2", "value": 0 },
                    { "obis": "2.8.0", "value": 181 }
                  ]
                }
                """.replace("\n", "\r\n"));

    private Route onGet(@NotNull String xApiKey) {
      return complete(ENTITY);
    }
  }
}
