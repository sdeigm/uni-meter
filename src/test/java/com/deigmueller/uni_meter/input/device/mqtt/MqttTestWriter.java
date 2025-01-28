package com.deigmueller.uni_meter.input.device.mqtt;

import org.apache.pekko.Done;
import org.apache.pekko.NotUsed;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.apache.pekko.stream.connectors.mqtt.MqttConnectionSettings;
import org.apache.pekko.stream.connectors.mqtt.MqttMessage;
import org.apache.pekko.stream.connectors.mqtt.MqttQoS;
import org.apache.pekko.stream.connectors.mqtt.MqttSubscriptions;
import org.apache.pekko.stream.connectors.mqtt.javadsl.MqttFlow;
import org.apache.pekko.stream.javadsl.Flow;
import org.apache.pekko.stream.javadsl.Keep;
import org.apache.pekko.stream.javadsl.Sink;
import org.apache.pekko.stream.javadsl.Source;
import org.apache.pekko.util.ByteString;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletionStage;

class MqttTestWriter {
  public static void main(String[] args) throws InterruptedException {
    ActorSystem<RootActor.Command> actorSystem = ActorSystem.create(
          Behaviors.setup(context -> RootActor.create()), "mqtt-writer");
    
    final MqttConnectionSettings connectionSettings = MqttConnectionSettings.create(
          "tcp://localhost:1883",
          "test-writer",
          new MemoryPersistence());

    final Flow<MqttMessage, MqttMessage, CompletionStage<Done>> mqttFlow =
          MqttFlow.atMostOnce(
                connectionSettings,
                MqttSubscriptions
                      .create("energy-meter/main/total-power", MqttQoS.atMostOnce())
                      .addSubscription("energy-meter/main/total-energy", MqttQoS.atMostOnce()),
                256,
                MqttQoS.atLeastOnce());
    
//    final Source<MqttMessage, NotUsed> source = 
//          Source.single(MqttMessage.create("solar/powermeter/powertotal", ByteString.fromString("196.0")));
    
    final Source<MqttMessage, NotUsed> source = 
          Source.single(MqttMessage.create("tele/smlreader/SENSOR", ByteString.fromString("{\"Time\":\"2025-01-18T01:57:30\",\"Main\":{\"power\":466,\"counter_pos\":1306.661,\"counter_neg\":148.611}}")));
    
    while (true) {
      source.viaMat(mqttFlow, Keep.both()).toMat(Sink.seq(), Keep.both()).run(actorSystem);
      
      
      Thread.sleep(1000);
    }
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
}