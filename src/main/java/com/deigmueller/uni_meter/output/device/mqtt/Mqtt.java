/*
 * Copyright (C) 2018-2023 layline.io GmbH <http://www.layline.io>
 */

package com.deigmueller.uni_meter.output.device.mqtt;

import com.deigmueller.uni_meter.application.UniMeter;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.typesafe.config.Config;
import org.apache.commons.text.StringSubstitutor;
import org.apache.commons.text.lookup.StringLookup;
import org.apache.commons.text.lookup.StringLookupFactory;
import org.apache.pekko.Done;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.apache.pekko.japi.Pair;
import org.apache.pekko.stream.KillSwitches;
import org.apache.pekko.stream.OverflowStrategy;
import org.apache.pekko.stream.QueueOfferResult;
import org.apache.pekko.stream.UniqueKillSwitch;
import org.apache.pekko.stream.connectors.mqtt.MqttConnectionSettings;
import org.apache.pekko.stream.connectors.mqtt.MqttMessage;
import org.apache.pekko.stream.connectors.mqtt.MqttQoS;
import org.apache.pekko.stream.connectors.mqtt.javadsl.MqttSink;
import org.apache.pekko.stream.javadsl.Keep;
import org.apache.pekko.stream.javadsl.Source;
import org.apache.pekko.stream.javadsl.SourceQueueWithComplete;
import org.apache.pekko.util.ByteString;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * MQTT output device.
 */
public class Mqtt extends OutputDevice {
  // Class members
  public static final String TYPE = "MQTT";
  
  // Instance members
  private final List<TopicWriter> topicWriters = new ArrayList<>();
  private final StringSubstitutor stringSubstitutor;
  private UniqueKillSwitch mqttStreamKillSwitch;
  private SourceQueueWithComplete<MqttMessage> mqttStreamMessageQueue;

  /**
   * Static setup method
   * @param controller The controller actor
   * @param config The output device configuration
   * @return Behavior of the created actor
   */
  public static Behavior<Command> create(@NotNull ActorRef<UniMeter.Command> controller,
                                         @NotNull Config config) {
    return Behaviors.setup(context -> new Mqtt(context, controller, config));
  }

  /**
   * Protected constructor called by the setup method.
   * @param context The actor context
   * @param controller The controller actor
   * @param config The output device configuration
   */
  public Mqtt(@NotNull ActorContext<Command> context,
              @NotNull ActorRef<UniMeter.Command> controller,
              @NotNull Config config) {
    super(context, controller, config);
    
    stringSubstitutor = createStringSubstitutor();

    initTopicWriters();
    
    startMqttOutputStream();
  }

  /**
   * Create the actor's ReceiveBuilder
   * @return The actor's ReceiveBuilder
   */
  @Override
  public ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onMessage(NotifyMqttStreamFailed.class, this::onNotifyMqttStreamFailed)
          .onMessage(NotifyMqttStreamFinished.class, this::onNotifyMqttStreamFinished)
          .onMessage(NotifyQueueOfferFailed.class, this::onNotifyQueueOfferFailed)
          .onMessage(NotifyQueueClosed.class, this::onNotifyQueueClosed)
          .onMessage(NotifyRestartMqttStream.class, this::onRestartMqttStream);
  }

  /**
   * Handle the notification that the MQTT stream failed.
   * @param message Notification that the MQTT stream failed.
   * @return Same behavior
   */
  private Behavior<Command> onNotifyMqttStreamFailed(@NotNull NotifyMqttStreamFailed message) {
    logger.trace("Mqtt.onNotifyMqttStreamFailed()");
    
    logger.error("failed to create the MQTT stream", message.throwable());
    
    closeMqttStreamAndRestart(message.queue());
    
    return Behaviors.same();
  }

  /**
   * Handle the notification that the MQTT stream finished.
   * @param message Notification that the MQTT stream finished.
   * @return Same behavior
   */
  private Behavior<Command> onNotifyMqttStreamFinished(@NotNull NotifyMqttStreamFinished message) {
    logger.trace("Mqtt.onNotifyMqttStreamFinished()");
    
    logger.info("MQTT stream finished");

    closeMqttStreamAndRestart(message.queue());
    
    return Behaviors.same();
  }
  
  /**
   * Handle the notification that the queue offer failed.
   * @param message Notification that the queue offer failed.
   * @return Same behavior
   */
  private Behavior<Command> onNotifyQueueOfferFailed(@NotNull NotifyQueueOfferFailed message) {
    logger.trace("Mqtt.onNotifyQueueOfferFailed()");

    logger.error("MQTT queue offer failed", message.throwable());

    closeMqttStreamAndRestart(message.queue());
    
    return Behaviors.same();
  }
  
  /**
   * Handle the notification that the queue closed.
   * @param message Notification that the queue closed.
   * @return Same behavior
   */
  private Behavior<Command> onNotifyQueueClosed(@NotNull NotifyQueueClosed message) {
    logger.trace("Mqtt.onNotifyQueueClosed()");
    
    logger.info("MQTT queue closed");
    
    closeMqttStreamAndRestart(message.queue());
    
    return Behaviors.same();
  }
  
  /**
   * Handle the request to restart the MQTT stream.
   * @param message Request to restart the MQTT stream.
   * @return Same behavior
   */
  private Behavior<Command> onRestartMqttStream(@NotNull NotifyRestartMqttStream message) {
    logger.trace("Mqtt.onRestartMqttStream()");
    
    startMqttOutputStream();
    
    return Behaviors.same();
  }
  
  @Override
  protected void powerDataUpdated() {
    logger.trace("Mqtt.powerDataUpdated()");
    
    if (mqttStreamMessageQueue != null) {
      for (TopicWriter topicWriter : topicWriters) {
        String value = topicWriter.getValue(stringSubstitutor);
        logger.debug("writing value '{}' to topic '{}'", value, topicWriter.getTopic());
        
        final SourceQueueWithComplete<MqttMessage> queue = this.mqttStreamMessageQueue;
  
        queue.offer(
              MqttMessage.create(topicWriter.getTopic(), ByteString.fromString(value)).withQos(MqttQoS.atMostOnce())
        ).whenComplete((offerResult, failure) -> {
          if (failure != null) {
            getContext().getSelf().tell(new NotifyQueueOfferFailed(queue, failure));
          } else if (offerResult == QueueOfferResult.closed()) {
            getContext().getSelf().tell(new NotifyQueueClosed(queue));
          } else if (offerResult == QueueOfferResult.dropped()){
            logger.warn("MQTT message dropped");
          } else if (offerResult == QueueOfferResult.enqueued()) {
            logger.debug("MQTT message enqueued");
          }
        });
      }
    }
  }
  
  private void closeMqttStreamAndRestart(SourceQueueWithComplete<MqttMessage> queue) {
    if (mqttStreamMessageQueue == queue) {
      if (mqttStreamKillSwitch != null) {
        mqttStreamKillSwitch.shutdown();
      }
      mqttStreamMessageQueue = null;
      mqttStreamKillSwitch = null;
      
      getContext().getSystem().scheduler().scheduleOnce(
            getConfig().getDuration("restart-delay"),
            () -> getContext().getSelf().tell(NotifyRestartMqttStream.INSTANCE),
            getContext().getSystem().executionContext());
    }
  }
  
  
  private StringSubstitutor createStringSubstitutor() {
    Map<String, StringLookup> stringStringLookupMap = new HashMap<>();
    stringStringLookupMap.put("date", StringLookupFactory.INSTANCE.dateStringLookup());
    stringStringLookupMap.put("env", StringLookupFactory.INSTANCE.environmentVariableStringLookup());
    stringStringLookupMap.put("sys", StringLookupFactory.INSTANCE.systemPropertyStringLookup());
    stringStringLookupMap.put("uni-meter", StringLookupFactory.INSTANCE.functionStringLookup(this::stringLookup));

    return new StringSubstitutor(
          StringLookupFactory.INSTANCE.interpolatorStringLookup(
                stringStringLookupMap,
                stringStringLookupMap.get("uni-meter"),
                true));
  }
  
  private String stringLookup(String key) {
    return switch (key) {
      case "apparent-power-l1" ->
            toString(getPowerPhase0().apparentPower());
      case "apparent-power-l2" ->
            toString(getPowerPhase1().apparentPower());
      case "apparent-power-l3" ->
            toString(getPowerPhase2().apparentPower());
      case "apparent-power-total" ->
            toString(getPowerPhase0().apparentPower() + getPowerPhase1().apparentPower() + getPowerPhase2().apparentPower());
      case "current-l1" ->
            toString(getPowerPhase0().current());
      case "current-l2" ->
            toString(getPowerPhase1().current());
      case "current-l3" ->
            toString(getPowerPhase2().current());
      case "current-total" ->
            toString((getPowerPhase0().current() + getPowerPhase1().current() + getPowerPhase2().current()));
      case "energy-consumption-l1" -> 
            toString(getEnergyPhase0().totalConsumption());
      case "energy-consumption-l2" ->
            toString(getEnergyPhase1().totalConsumption());
      case "energy-consumption-l3" ->
            toString(getEnergyPhase2().totalConsumption());
      case "energy-consumption-total" ->
            toString(getEnergyPhase0().totalConsumption() + getEnergyPhase1().totalConsumption() + getEnergyPhase2().totalConsumption());
      case "energy-production-l1" ->
            toString(getEnergyPhase0().totalProduction());
      case "energy-production-l2" ->
            toString(getEnergyPhase1().totalProduction());
      case "energy-production-l3" ->
            toString(getEnergyPhase2().totalProduction());
      case "energy-production-total" ->
            toString(getEnergyPhase0().totalProduction() + getEnergyPhase1().totalProduction() + getEnergyPhase2().totalProduction());
      case "power-factor-l1" ->
            toString(getPowerPhase0().powerFactor());
      case "power-factor-l2" ->
            toString(getPowerPhase1().powerFactor());
      case "power-factor-l3" ->
            toString(getPowerPhase2().powerFactor());
      case "power-l1" -> 
            toString(getPowerPhase0().power());
      case "power-l2" -> 
            toString(getPowerPhase1().power());
      case "power-l3" -> 
            toString(getPowerPhase2().power());
      case "power-total" ->
            toString(getPowerPhase0().power() + getPowerPhase1().power() + getPowerPhase2().power());
      case "voltage-l1" -> 
            toString(getPowerPhase0().voltage());
      case "voltage-l2" ->
            toString(getPowerPhase1().voltage());
      case "voltage-l3" ->
            toString(getPowerPhase2().voltage());
      case "voltage-total" ->
            toString((getPowerPhase0().voltage() + getPowerPhase1().voltage() + getPowerPhase2().voltage()) / 3.0);
      default -> null;
    };
  }
  
  private String toString(double value) {
    return Double.toString(Math.round(value * 100.0) / 100.0);
  }

  /**
   * Initialize the topic writers.
   */
  private void initTopicWriters() {
    for (Config topicConfig : getConfig().getConfigList("topics")) {
      topicWriters.add(new TopicWriter(topicConfig));
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
   * Start the MQTT stream.
   */
  private void startMqttOutputStream() {
    logger.trace("Mqtt.createMqttOutputStream()");
    
    if (mqttStreamMessageQueue != null) {
      throw new IllegalStateException("MQTT stream already running");
    }
    
    final ActorRef<Command> self = getContext().getSelf();
    
    Pair<Pair<SourceQueueWithComplete<MqttMessage>, UniqueKillSwitch>,CompletionStage<Done>> resultPair = Source
          .<MqttMessage>queue(1, OverflowStrategy.dropHead())
          .viaMat(KillSwitches.single(), Keep.both())
          .toMat(MqttSink.create(createConnectionSettings(), MqttQoS.atLeastOnce()), Keep.both())
          .run(getContext().getSystem());
    
    mqttStreamMessageQueue = resultPair.first().first();
    mqttStreamKillSwitch = resultPair.first().second();
    
    final SourceQueueWithComplete<MqttMessage> queue = mqttStreamMessageQueue;
    resultPair.second().whenComplete((done, throwable) -> {
      if (throwable != null) {
        self.tell(new NotifyMqttStreamFailed(queue, throwable));
      } else {
        self.tell(new NotifyMqttStreamFinished(queue));
      }
    });
  }
  
  private record NotifyMqttStreamFailed(
        @NotNull SourceQueueWithComplete<MqttMessage> queue,
        @NotNull Throwable throwable
  ) implements Command {}
  
  private record NotifyMqttStreamFinished(
        @NotNull SourceQueueWithComplete<MqttMessage> queue
  ) implements Command {}
  
  private record NotifyQueueOfferFailed(
        @NotNull SourceQueueWithComplete<MqttMessage> queue,
        @NotNull Throwable throwable
  ) implements Command {}

  private record NotifyQueueClosed(
        @NotNull SourceQueueWithComplete<MqttMessage> queue
  ) implements Command {}
 
  private enum NotifyRestartMqttStream implements Command {
    INSTANCE
  } 
}
