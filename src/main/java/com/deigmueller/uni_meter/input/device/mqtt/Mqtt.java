package com.deigmueller.uni_meter.input.device.mqtt;

import com.deigmueller.uni_meter.input.InputDevice;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.json.JsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import com.jayway.jsonpath.spi.mapper.MappingProvider;
import com.typesafe.config.Config;
import org.apache.pekko.Done;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.apache.pekko.japi.Pair;
import org.apache.pekko.stream.Materializer;
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
public class Mqtt extends InputDevice {
  // Class members
  public static final String TYPE = "MQTT";
  public static final String PHASE_MODE_MONO = "mono-phase";
  public static final String PHASE_MODE_TRI = "tri-phase";
  
  // Instance members
  private final Materializer materializer = Materializer.createMaterializer(getContext());
  private final PhaseMode powerPhaseMode = getPhaseMode("power-phase-mode");
  private final PhaseMode energyPhaseMode = getPhaseMode("energy-phase-mode");
  private final double defaultVoltage = getConfig().getDouble("default-voltage");
  private final double defaultFrequency = getConfig().getDouble("default-frequency");
  private final List<TopicReader> topicReaders = new ArrayList<>();
  
  private double powerTotal;
  private double powerL1;
  private double powerL2;
  private double powerL3;
  
  private double energyConsumptionTotal;
  private double energyConsumptionL1;
  private double energyConsumptionL2;
  private double energyConsumptionL3;
  
  private double energyProductionTotal;
  private double energyProductionL1;
  private double energyProductionL2;
  private double energyProductionL3;
  
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
    
    initJsonPath();
    
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
      for (TopicReader topicReader : topicReaders) {
        if (Objects.equals(topicReader.getTopic(), message.topic())) {
          Double value = topicReader.getValue(message.payload());
          if (value != null) {
            setChannelData(topicReader.getChannel(), value);
            
            notifyOutputDevice();
          }
        }
      }
    } catch (Exception exception) {
      logger.error("failed to process MQTT topic data", exception);
    }

    message.replyTo.tell(AckTopicData.INSTANCE);

    return Behaviors.same();
  }

  /**
   * Set the channel data.
   * @param channel The channel to set the data for.
   * @param value The value to set.
   */
  private void setChannelData(@NotNull String channel, double value) {
    switch (channel) {
      case "power-total":
        powerTotal = value;
        break;
      case "power-l1":
        powerL1 = value;
        break;
      case "power-l2":
        powerL2 = value;
        break;
      case "power-l3":
        powerL3 = value;
        break;
      case "energy-consumption-total":
        energyConsumptionTotal = value;
        break;
      case "energy-consumption-l1":
        energyConsumptionL1 = value;
        break;
      case "energy-consumption-l2":
        energyConsumptionL2 = value;
        break;
      case "energy-consumption-l3":
        energyConsumptionL3 = value;
        break;
      case "energy-production-total":
        energyProductionTotal = value;
        break;
      case "energy-production-l1":
        energyProductionL1 = value;
        break;
      case "energy-production-l2":
        energyProductionL2 = value;
        break;
      case "energy-production-l3":
        energyProductionL3 = value;
        break;
      default:
        logger.warn("unknown channel: {}", channel);
    }
  }
  
  /**
   * Notify the current readings to the output device.
   */
  private void notifyOutputDevice() {
    notifyPowerData();
    
    notifyEnergyData();
  }
  
  /**
   * Notify the current power data to the output device.
   */
  private void notifyPowerData() {
    if (powerPhaseMode == PhaseMode.MONO) {
      getOutputDevice().tell(new OutputDevice.NotifyTotalPowerData(
            getNextMessageId(),
            new OutputDevice.PowerData(
                  powerTotal, powerTotal, 1.0, powerTotal / defaultVoltage, defaultVoltage, defaultFrequency),
            getOutputDeviceAckAdapter()));
    } else {
      getOutputDevice().tell(new OutputDevice.NotifyPhasePowerData(
            getNextMessageId(),
            0,
            new OutputDevice.PowerData(
                  powerL1, powerL1, 1.0, powerL1 / defaultVoltage, defaultVoltage, defaultFrequency),
            getOutputDeviceAckAdapter()));
      getOutputDevice().tell(new OutputDevice.NotifyPhasePowerData(
            getNextMessageId(),
            1,
            new OutputDevice.PowerData(
                  powerL2, powerL2, 1.0, powerL2 / defaultVoltage, defaultVoltage, defaultFrequency),
            getOutputDeviceAckAdapter()));
      getOutputDevice().tell(new OutputDevice.NotifyPhasePowerData(
            getNextMessageId(),
            2,
            new OutputDevice.PowerData(
                  powerL3, powerL3, 1.0, powerL3 / defaultVoltage, defaultVoltage, defaultFrequency),
            getOutputDeviceAckAdapter()));
    }
  }
  
  /**
   * Notify the current energy data to the output device.
   */
  private void notifyEnergyData() {
    if (energyPhaseMode == PhaseMode.MONO) {
      getOutputDevice().tell(new OutputDevice.NotifyTotalEnergyData(
            getNextMessageId(),
            new OutputDevice.EnergyData(energyConsumptionTotal, energyProductionTotal),
            getOutputDeviceAckAdapter()));
    } else {
      getOutputDevice().tell(new OutputDevice.NotifyPhaseEnergyData(
            getNextMessageId(),
            0,
            new OutputDevice.EnergyData(energyConsumptionL1, energyProductionL1),
            getOutputDeviceAckAdapter()));
      getOutputDevice().tell(new OutputDevice.NotifyPhaseEnergyData(
            getNextMessageId(),
            1,
            new OutputDevice.EnergyData(energyConsumptionL2, energyProductionL2),
            getOutputDeviceAckAdapter()));
      getOutputDevice().tell(new OutputDevice.NotifyPhaseEnergyData(
            getNextMessageId(),
            2,
            new OutputDevice.EnergyData(energyConsumptionL3, energyProductionL3),
            getOutputDeviceAckAdapter()));
    }
  }
  
  private void initJsonPath() {
    Configuration.setDefaults(new Configuration.Defaults() {

      private final JsonProvider jsonProvider = new JacksonJsonProvider();
      private final MappingProvider mappingProvider = new JacksonMappingProvider();

      @Override
      public JsonProvider jsonProvider() {
        return jsonProvider;
      }

      @Override
      public MappingProvider mappingProvider() {
        return mappingProvider;
      }

      @Override
      public Set<Option> options() {
        return EnumSet.noneOf(Option.class);
      }
    });    
  }
  
  private void initTopicReaders() {
    for (Config channelConfig : getConfig().getConfigList("channels")) {
      switch (channelConfig.getString("type")) {
        case "value":
          topicReaders.add(new ValueTopicReader(channelConfig));
          break;
        case "json":
          topicReaders.add(new JsonTopicReader(channelConfig));
          break;
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
//          .mapAsync(
//                1,
//                messageWithAck ->
//                      messageWithAck.ack().thenApply(unused2 -> messageWithAck.message()))
          .mapAsync(5, message -> AskPattern.ask(
                self,
                (ActorRef<AckTopicData> replyTo) -> new NotifyTopicData(message.topic(), message.payload(), replyTo),
                Duration.ofSeconds(5),
                getContext().getSystem().scheduler()))
          .toMat(Sink.ignore(), Keep.both())
          .run(materializer);
    
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
  
  private @NotNull PhaseMode getPhaseMode(@NotNull String key) {
    String value = getConfig().getString(key);
    
    if (PHASE_MODE_MONO.compareToIgnoreCase(value) == 0) {
      return PhaseMode.MONO;
    } else if (PHASE_MODE_TRI.compareToIgnoreCase(key) == 0) {
      return PhaseMode.TRI;
    } else {
      throw new IllegalArgumentException("unknown phase mode: " + value);
    }
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
  
  public enum PhaseMode {
    MONO,
    TRI
  }
}
