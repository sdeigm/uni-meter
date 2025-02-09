package com.deigmueller.uni_meter.input.device.shelly._3em;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;

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
      return path("status", () -> get(this::onGetStatus));
    }
    
    private static final String RESPONSE = """
{
    "Body": {
        "Data": {
            "0": {
                "Current_AC_Phase_1": 2.2400000000000002,
                "Current_AC_Phase_2": 1.0109999999999999,
                "Current_AC_Phase_3": 0.52400000000000002,
                "Details": {
                    "Manufacturer": "Fronius",
                    "Model": "Smart Meter 63A",
                    "Serial": "18370225"
                },
                "Enable": 1,
                "EnergyReactive_VArAC_Sum_Consumed": 78945280,
                "EnergyReactive_VArAC_Sum_Produced": 220109110,
                "EnergyReal_WAC_Minus_Absolute": 67524528,
                "EnergyReal_WAC_Plus_Absolute": 16097894,
                "EnergyReal_WAC_Sum_Consumed": 16097894,
                "EnergyReal_WAC_Sum_Produced": 67524528,
                "Frequency_Phase_Average": 49.899999999999999,
                "Meter_Location_Current": 0,
                "PowerApparent_S_Phase_1": 522.36800000000005,
                "PowerApparent_S_Phase_2": 235.15859999999998,
                "PowerApparent_S_Phase_3": 122.1968,
                "PowerApparent_S_Sum": 546,
                "PowerFactor_Phase_1": -0.82999999999999996,
                "PowerFactor_Phase_2": 0.59999999999999998,
                "PowerFactor_Phase_3": 0.44,
                "PowerFactor_Sum": -0.33000000000000002,
                "PowerReactive_Q_Phase_1": -236.68000000000001,
                "PowerReactive_Q_Phase_2": -179.94999999999999,
                "PowerReactive_Q_Phase_3": -98.819999999999993,
                "PowerReactive_Q_Sum": -515.45000000000005,
                "PowerReal_P_Phase_1": -367.05000000000001,
                "PowerReal_P_Phase_2": 137.15000000000001,
                "PowerReal_P_Phase_3": 49.240000000000002,
                "PowerReal_P_Sum": -180.66,
                "TimeStamp": 1738870362,
                "Visible": 1,
                "Voltage_AC_PhaseToPhase_12": 403.39999999999998,
                "Voltage_AC_PhaseToPhase_23": 403.39999999999998,
                "Voltage_AC_PhaseToPhase_31": 403.89999999999998,
                "Voltage_AC_Phase_1": 233.19999999999999,
                "Voltage_AC_Phase_2": 232.59999999999999,
                "Voltage_AC_Phase_3": 233.19999999999999
            }
        }
    },
    "Head": {
        "RequestArguments": {
            "DeviceClass": "Meter",
            "Scope": "System"
        },
        "Status": {
            "Code": 0,
            "Reason": "",
            "UserMessage": ""
        },
        "Timestamp": "2025-02-06T20:32:43+01:00"
    }
}
          """;    
    
    private Route onGetStatus() {
      try {
        Map<String,Object> response = new ObjectMapper().readValue(RESPONSE, Map.class);
        
        return completeOK(
              response,
              Jackson.marshaller()
        );
      } catch (JsonProcessingException e) {
        return complete("error");
      }
    }
  }
}