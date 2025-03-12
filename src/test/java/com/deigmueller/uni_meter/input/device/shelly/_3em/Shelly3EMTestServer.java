package com.deigmueller.uni_meter.input.device.shelly._3em;

import org.apache.pekko.Done;
import org.apache.pekko.NotUsed;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.*;
import org.apache.pekko.http.javadsl.Http;
import org.apache.pekko.http.javadsl.ServerBuilder;
import org.apache.pekko.http.javadsl.marshallers.jackson.Jackson;
import org.apache.pekko.http.javadsl.model.ws.Message;
import org.apache.pekko.http.javadsl.model.ws.WebSocketUpgrade;
import org.apache.pekko.http.javadsl.server.AllDirectives;
import org.apache.pekko.http.javadsl.server.Route;
import org.apache.pekko.http.javadsl.settings.ServerSettings;
import org.apache.pekko.stream.OverflowStrategy;
import org.apache.pekko.stream.javadsl.Sink;
import org.apache.pekko.stream.javadsl.Source;
import org.apache.pekko.stream.javadsl.SourceQueueWithComplete;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletionStage;

class Shelly3EMTestServer {
  public static Logger LOGGER = LoggerFactory.getLogger(Shelly3EMTestServer.class);
  
  public static void main(String[] args) {
    ActorSystem<RootActor.Command> actorSystem = ActorSystem.create(
          Behaviors.setup(context -> RootActor.create()), "3em");

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
      return concat(
            path("status", () -> get(this::onGetStatus)),
            path("websocket", () -> extractWebSocketUpgrade(this::createWebSocketHandler))
      );
    }
    
    private Route onGetStatus() {
      return completeOK(
            new Shelly3EM.EmStatusResponse(
                  List.of(
                        new Shelly3EM.EmRelay(false, false, 0, 0, 0, false, true, "http")
                  ),
                  List.of(
                        new Shelly3EM.EmMeter(230.0, 0.98, 1.0, 230.0, true, 1000000.0, 2000000.0),
                        new Shelly3EM.EmMeter(460.0, 0.98, 2.0, 230.0, true, 1000000.0, 2000000.0),
                        new Shelly3EM.EmMeter(690.0, 0.98, 3.0, 230.0, true, 1000000.0, 2000000.0)
                  ),
                  1380.0,
                  true
            ),
            Jackson.marshaller()
      );
    }
    
    private Route createWebSocketHandler(WebSocketUpgrade upgrade) {
      Source<Message, SourceQueueWithComplete<Message>> source = Source.queue(10, OverflowStrategy.dropHead());
      
      Sink<Message, CompletionStage<Done>> sink = Sink.<Message>foreach(message -> {
        if (message.isText()) {
          System.out.println("recv <= " + message.asTextMessage().getStrictText());
        } else {
          System.out.println("recv <= " + message.asBinaryMessage().getStrictData().utf8String());
        }
      });
      
      return complete(upgrade.handleMessagesWith(sink, source));
    }
  }
}