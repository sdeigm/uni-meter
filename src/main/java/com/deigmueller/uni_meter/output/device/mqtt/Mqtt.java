/*
 * Copyright (C) 2018-2023 layline.io GmbH <http://www.layline.io>
 */

package com.deigmueller.uni_meter.output.device.mqtt;

import com.deigmueller.uni_meter.application.UniMeter;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.typesafe.config.Config;
import org.apache.commons.text.lookup.StringLookup;
import org.apache.commons.text.lookup.StringLookupFactory;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MQTT output device.
 */
public class Mqtt extends OutputDevice {
  // Class members
  public static final String TYPE = "MQTT";
  
  // Instance members
  private final List<TopicWriter> topicWriters = new ArrayList<>();
  private final StringLookup stringLookup;

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
    
    stringLookup = createStringLookup();

    initTopicWriters();
  }

  /**
   * Create the actor's ReceiveBuilder
   * @return The actor's ReceiveBuilder
   */
  @Override
  public ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder();
  }
  
  private StringLookup createStringLookup() {
    Map<String, StringLookup> stringStringLookupMap = new HashMap<>();
    stringStringLookupMap.put("date", StringLookupFactory.INSTANCE.dateStringLookup());
    stringStringLookupMap.put("env", StringLookupFactory.INSTANCE.environmentVariableStringLookup());
    stringStringLookupMap.put("sys", StringLookupFactory.INSTANCE.systemPropertyStringLookup());
    stringStringLookupMap.put("uni-meter", StringLookupFactory.INSTANCE.functionStringLookup(this::stringLookup));

    return StringLookupFactory.INSTANCE.interpolatorStringLookup(
          stringStringLookupMap,
          StringLookupFactory.INSTANCE.environmentVariableStringLookup() ,
          true);
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
    }
  }
}
