package com.deigmueller.uni_meter.input.device.tasmota;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.*;
import org.apache.pekko.http.javadsl.Http;
import org.apache.pekko.http.javadsl.ServerBuilder;
import org.apache.pekko.http.javadsl.marshallers.jackson.Jackson;
import org.apache.pekko.http.javadsl.server.AllDirectives;
import org.apache.pekko.http.javadsl.server.Route;
import org.apache.pekko.http.javadsl.settings.ServerSettings;
import org.apache.pekko.http.javadsl.unmarshalling.StringUnmarshallers;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class TasmotaTestServer {
  public static Logger LOGGER = LoggerFactory.getLogger(TasmotaTestServer.class);
  
  public static void main(String[] args) {
    ActorSystem<RootActor.Command> actorSystem = ActorSystem.create(
          Behaviors.setup(context -> RootActor.create()), "tasmota");

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
      return path("cm", () -> 
            get(() -> 
                  parameter(StringUnmarshallers.STRING, "cmnd", this::onGetCm)
            )
      );
    }
    
    private Route onGetCm(String cmnd) {
      return completeOK(
            new StatusResponse(
                  new StatusSNS(
                        "2025-01-22T12:00:00",
                        new SML(
                              "abcdef1234567890abcdef",
                              9003,
                              20003,
                              -427,
                              230,
                              230,
                              230,
                              0.62,
                              0.62,
                              0.62,
                              50.0
                        )
                  )
            ),
            Jackson.marshaller()
      );
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record StatusResponse(
        @JsonProperty("StatusSNS") StatusSNS statusSNS
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record StatusSNS(
        @JsonProperty("Time") String time,
        @JsonProperty("SML") SML sml
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SML(
        @JsonProperty("server_id") String serverId,
        @JsonProperty("export_total_kwh") double exportTotalKwh,
        @JsonProperty("total_kwh") double totalKwh,
        @JsonProperty("curr_w") double currW,
        @JsonProperty("volt_p1") double voltP1,
        @JsonProperty("volt_p2") double voltP2,
        @JsonProperty("volt_p3") double voltP3,

        @JsonProperty("amp_p1") double ampP1,
        @JsonProperty("amp_p2") double ampP2,
        @JsonProperty("amp_p3") double ampP3,

        @JsonProperty("freq") double freq
  ) {}
}