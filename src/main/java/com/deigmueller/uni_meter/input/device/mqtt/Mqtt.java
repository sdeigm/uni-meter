package com.deigmueller.uni_meter.input.device.mqtt;

import com.deigmueller.uni_meter.input.device.common.generic.GenericInputDevice;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.typesafe.config.Config;
import org.apache.pekko.Done;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.apache.pekko.japi.Pair;
import org.apache.pekko.stream.connectors.mqtt.MqttConnectionSettings;
import org.apache.pekko.stream.connectors.mqtt.MqttQoS;
import org.apache.pekko.stream.connectors.mqtt.MqttSubscriptions;
import org.apache.pekko.stream.connectors.mqtt.javadsl.MqttSource;
import org.apache.pekko.stream.javadsl.Keep;
import org.apache.pekko.stream.javadsl.Sink;
import org.apache.pekko.util.ByteString;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletionStage;

/**
 * MQTT input device.
 */
public class Mqtt extends GenericInputDevice {
  // Class members
  public static final String TYPE = "MQTT";
  
  // Instance members
  private final List<TopicReader> topicReaders = new ArrayList<>();
  
  /**
   * Static setup method to create a new instance of the MQTT input device.
   * @param outputDevice The output device to send the data to.
   * @param config The configuration for the MQTT input device.
   * @return The behavior of the MQTT input device.
   */
  public static Behavior<Command> create(@NotNull ActorRef<OutputDevice.Command> outputDevice,
                                         @NotNull Config config) {
    return Behaviors.setup(context -> new Mqtt(context, outputDevice, config));
  }

  /**
   * Protected constructor called by the setup method.
   * @param context The actor context.
   * @param outputDevice The output device to send the data to.
   * @param config The configuration for the MQTT input device.
   */
  protected Mqtt(@NotNull ActorContext<Command> context, 
                 @NotNull ActorRef<OutputDevice.Command> outputDevice, 
                 @NotNull Config config) {
    super(context, outputDevice, config);
    
    initTopicReaders();
    
    startMqttStream();
  }

  /**
   * Create the actor's ReceiveBuilder
   * @return The actor's ReceiveBuilder
   */
  @Override
  public @NotNull ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onMessage(NotifyMqttStreamConnected.class, this::onMqttStreamConnected)
          .onMessage(NotifyMqttStreamFailed.class, this::onMqttStreamFailed)
          .onMessage(NotifyMqttStreamFinished.class, this::onMqttStreamFinished)
          .onMessage(RestartMqttStream.class, this::onRestartMqttStream)
          .onMessage(NotifyTopicData.class, this::onTopicData);
  }
  
  /**
   * Handle the notification that the MQTT stream connected.
   * @param message Notification that the MQTT stream connected.
   * @return Same behavior
   */
  private @NotNull Behavior<Command> onMqttStreamConnected(@NotNull NotifyMqttStreamConnected message) {
    logger.trace("Mqtt.onMqttStreamConnected()");
    
    logger.info("MQTT stream connected");
    
    return Behaviors.same();
  }

  /**
   * Handle the notification that the MQTT stream failed.
   * @param message Notification that the MQTT stream failed.
   * @return Never
   */
  private @NotNull Behavior<Command> onMqttStreamFailed(@NotNull NotifyMqttStreamFailed message) {
    logger.trace("Mqtt.onMqttStreamFailed()");
    
    logger.debug("MQTT stream failed", message.throwable());
    
    throw new RuntimeException("MQTT polling failed " + message.throwable().getMessage());
  }
  
  /**
   * Handle the notification that the MQTT stream finished.
   * @param message Notification that the MQTT stream finished.
   * @return Same behavior
   */
  private @NotNull Behavior<Command> onMqttStreamFinished(@NotNull NotifyMqttStreamFinished message) {
    logger.trace("Mqtt.onMqttStreamFinished()");
    
    logger.info("MQTT stream finished");
    
    getContext().getSystem().scheduler().scheduleOnce(
          Duration.ofSeconds(5),
          () -> getContext().getSelf().tell(RestartMqttStream.INSTANCE),
          getContext().getSystem().executionContext());
    
    return Behaviors.same();
  }

  /**
   * Handle the request to restart the MQTT stream.
   * @param message Request to restart the MQTT stream.
   * @return Same behavior
   */
  private @NotNull Behavior<Command> onRestartMqttStream(@NotNull RestartMqttStream message) {
    logger.trace("Mqtt.onRestartMqttStream()");
    
    startMqttStream();
    
    return Behaviors.same();
  }

  /**
   * Handle the MQTT topic data.
   * @param message The MQTT topic data.
   * @return Same behavior
   */
  private @NotNull Behavior<Command> onTopicData(@NotNull NotifyTopicData message) {
    logger.trace("Mqtt.onTopicData()");
    
    try {
      boolean changes = false;
      
      String payload = message.payload().utf8String();
      logger.debug("received data for MQTT topic {}: {}", message.topic(), payload);
      
      for (TopicReader topicReader : topicReaders) {
        if (Objects.equals(topicReader.getTopic(), message.topic())) {
          Double value = topicReader.getValue(logger, payload);
          if (value != null) {
            setChannelData(topicReader.getChannel(), value);
            changes = true;
          }
        }
      }
      
      if (changes) {
        notifyOutputDevice();
      }
    } catch (Exception exception) {
      logger.error("failed to process MQTT topic data", exception);
    }

    message.replyTo.tell(AckTopicData.INSTANCE);

    return Behaviors.same();
  }
  
  /**
   * Initialize the topic readers.
   */
  private void initTopicReaders() {
    for (Config channelConfig : getConfig().getConfigList("channels")) {
      switch (channelConfig.getString("type")) {
        case "value":
          topicReaders.add(new ValueTopicReader(channelConfig));
          break;
        case "json":
          topicReaders.add(new JsonTopicReader(channelConfig));
          break;
          
        default:
          throw new IllegalArgumentException("unknown channel type: " + channelConfig.getString("type"));
      }
    }
  }
  
  /**
   * Create the connection settings for the MQTT connection.
   * @return The connection settings.
   */
  private MqttConnectionSettings createConnectionSettings() {
    MqttConnectionSettings settings = MqttConnectionSettings
          .create(getConfig().getString("url"),
                getConfig().getString("client-id"), 
                new MemoryPersistence() 
          );
    
    if (!getConfig().getString("username").isEmpty() || !getConfig().getString("password").isEmpty()) {
      settings = settings.withAuth(getConfig().getString("username"), getConfig().getString("password"));
    }
    
    return settings;
  }
  
  /**
   * Create the subscriptions for the MQTT connection.
   * @return The subscriptions.
   */
  private MqttSubscriptions createSubscriptions() {
    logger.trace("Mqtt.createSubscriptions()");
    
    List<Pair<String,MqttQoS>> subscriptions = new ArrayList<>();
    
    Set<String> topics = new HashSet<>();
    
    for (TopicReader topicReader : topicReaders) {
      if (topics.add(topicReader.getTopic())) {
        logger.info("subscribing to topic: {}", topicReader.getTopic());
        subscriptions.add(Pair.create(topicReader.getTopic(), MqttQoS.atMostOnce()));
      }
    }
    
    return MqttSubscriptions.create(subscriptions);
  }
  
  /**
   * Start the MQTT stream.
   */
  private void startMqttStream() {
    final ActorRef<Command> self = getContext().getSelf();
    
    
    Pair<CompletionStage<Done>, CompletionStage<Done>> result = MqttSource
          .atMostOnce(createConnectionSettings(), createSubscriptions(), 256)
          .mapAsync(5, message -> AskPattern.ask(
                self,
                (ActorRef<AckTopicData> replyTo) -> new NotifyTopicData(message.topic(), message.payload(), replyTo),
                Duration.ofSeconds(5),
                getContext().getSystem().scheduler()))
          .toMat(Sink.ignore(), Keep.both())
          .run(getMaterializer());
    
    result.first().whenComplete((done, throwable) -> {
      if (throwable != null) {
        self.tell(new NotifyMqttStreamFailed(throwable));
      } else {
        self.tell(new NotifyMqttStreamConnected());
      }
    });

    result.second().whenComplete((done, throwable) -> {
      if (throwable != null) {
        self.tell(new NotifyMqttStreamFailed(throwable));
      } else {
        self.tell(new NotifyMqttStreamFinished());
      }
    });
    
  }
  
  public record NotifyMqttStreamFailed(@NotNull Throwable throwable) implements Command {}
  
  public record NotifyMqttStreamFinished() implements Command {}

  public record NotifyMqttStreamConnected() implements Command {}
  
  public enum RestartMqttStream implements Command {
    INSTANCE
  }
  
  public record NotifyTopicData(
        @NotNull String topic,
        @NotNull ByteString payload,
        @NotNull ActorRef<AckTopicData> replyTo
  ) implements Command {}
  
  public enum AckTopicData {
    INSTANCE
  }
}
