package com.deigmueller.uni_meter.output.device.eco_tracker;

import com.deigmueller.uni_meter.application.UniMeter;
import com.deigmueller.uni_meter.common.utils.NetUtils;
import com.deigmueller.uni_meter.mdns.MDnsRegistrator;
import com.deigmueller.uni_meter.output.ClientContextsInitializer;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.deigmueller.uni_meter.output.TemporaryNotAvailableException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.typesafe.config.Config;
import lombok.AccessLevel;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.apache.pekko.http.javadsl.server.Route;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

@Getter(AccessLevel.PROTECTED)
public class EcoTracker  extends OutputDevice {
  // Class members
  public static final String TYPE = "EcoTracker";

  // Instance members
  private final String bindInterface = getConfig().getString("interface");
  private final int bindPort = getConfig().getInt("port");
  private final Duration averageInterval = getConfig().getDuration("average-interval");
  private final boolean suppressPhaseOutput =  getConfig().getBoolean("suppress-phase-output");
  private final String defaultMac = getDefaultMacAddress(getConfig());
  private final String defaultHostname = getDefaultHostName(getConfig(), defaultMac);
  private final Deque<PowerHistory> powerHistory = new ArrayDeque<>();

  /**
   * Static setup method
   * @param controller Controller actor reference
   * @param config Output device configuration
   * @return Behavior of the created actor
   */
  public static Behavior<Command> create(@NotNull ActorRef<UniMeter.Command> controller,
                                         @NotNull ActorRef<MDnsRegistrator.Command> mDnsRegistrator,
                                         @NotNull Config config) {
    return Behaviors.setup(context -> new EcoTracker(context, controller, mDnsRegistrator, config));
  }

  protected EcoTracker(@NotNull ActorContext<Command> context, 
                       @NotNull ActorRef<UniMeter.Command> controller, 
                       @NotNull ActorRef<MDnsRegistrator.Command> mDnsRegistrator, 
                       @NotNull Config config) {
    super(context, controller, mDnsRegistrator, config, ClientContextsInitializer.empty());

    registerMDns();
    
    controller.tell(new UniMeter.RegisterHttpRoute(getBindInterface(), getBindPort(), createRoute()));
  }

  @Override
  public @NotNull ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
         .onMessage(V1GetJson.class, this::onV1GetJson);
  }

  /**
   * Handle the request to get the data for the JSON API
   * @param message Request message
   * @return Same behavior
   */
  private Behavior<Command> onV1GetJson(@NotNull V1GetJson message) {
    getLogger().trace("EcoTracer.onV1GetJson()");
    
    if (isSwitchedOff()) {
      // The device is disabled
      return Behaviors.same();
    }

    PowerData powerPhase0 = getPowerPhase0();
    PowerData powerPhase1 = getPowerPhase1();
    PowerData powerPhase2 = getPowerPhase2();

    if (powerPhase0 == null && powerPhase1 == null && powerPhase2 == null) {
      message.replyTo().tell(
            V1GetJsonResponse.of(new TemporaryNotAvailableException("device usage constraint until " + getOffUntil())));
      return Behaviors.same();
    }
    
    if (powerPhase0 == null) {
      powerPhase0 = getPowerPhase0OrDefault();
    }
    if (powerPhase1 == null) {
      powerPhase1 = getPowerPhase1OrDefault();
    }
    if (powerPhase2 == null) {
      powerPhase2 = getPowerPhase2OrDefault();
    }

    double factor = getPowerFactorForRemoteAddress(message.remoteAddress());

    double power = (powerPhase0.power() + powerPhase1.power() + powerPhase2.power()) * factor;

    double powerAverage = 0.0;
    for (PowerHistory history : powerHistory) {
      powerAverage += history.power();
    }
    if (!powerHistory.isEmpty()) {
      powerAverage /= powerHistory.size();
      powerAverage *= factor;
    }

    if (checkUsageConstraint(power)) {
      // Usage constraint => notify failure
      message.replyTo().tell(
            V1GetJsonResponse.of(new TemporaryNotAvailableException("device usage constraint until " + getUsageConstraintInitUntil())));
      return Behaviors.same();
    }

    EnergyData energyPhase0 = getEnergyPhase0();
    EnergyData energyPhase1 = getEnergyPhase1();
    EnergyData energyPhase2 = getEnergyPhase2();
    
    double energyIn = energyPhase0.totalConsumption() + energyPhase1.totalConsumption() + energyPhase2.totalConsumption();
    
    double energyOut = energyPhase0.totalProduction() + energyPhase1.totalProduction() + energyPhase2.totalProduction();
    
    message.replyTo().tell(
          V1GetJsonResponse.of(
                new V1Json(
                      (long) power,
                      (long) powerAverage, 
                      suppressPhaseOutput ? null : powerPhase0.power(),
                      suppressPhaseOutput ? null : powerPhase1.power(),
                      suppressPhaseOutput ? null : powerPhase2.power(),
                      energyIn * 1000.0,
                      null,
                      null,
                      energyOut * 1000.0)));
    
    return Behaviors.same();
  }

  /**
   * Create the HTTP route of the device
   * @return HTTP route of the device
   */
  @Override
  protected Route createRoute() {
    HttpRoute httpRoute = new HttpRoute(
          LoggerFactory.getLogger(logger.getName() + ".http"),
          getContext().getSystem(),
          getContext().getSelf());

    return httpRoute.createRoute();
  }

  @Override
  protected void eventPowerDataChanged() {
    double totalPower = 0.0;
    
    PowerData powerPhase0 = getPowerPhase0();
    if (powerPhase0 != null) {
      totalPower += powerPhase0.power();
    }
    
    PowerData powerPhase1 = getPowerPhase1();
    if (powerPhase1 != null) {
      totalPower += powerPhase1.power();
    }
    
    PowerData powerPhase2 = getPowerPhase2();
    if (powerPhase2 != null) {
      totalPower += powerPhase2.power();
    }
    
    Instant now = Instant.now();
    powerHistory.addFirst(new PowerHistory(now, totalPower));
    
    Instant cutoff = now.minus(averageInterval);
    while (!powerHistory.isEmpty() && powerHistory.peekLast().timestamp().isBefore(cutoff)) {
      powerHistory.removeLast();
    }
  }

  protected static String getDefaultMacAddress(@NotNull Config config) {
    if (! StringUtils.isAllBlank(config.getString("mac"))) {
      return config.getString("mac");
    }

    String detected = NetUtils.detectPrimaryMacAddress();
    if (detected != null) {
      return detected;
    }

    return "B827EB364242";
  }

  protected static String getDefaultHostName(@NotNull Config config, @NotNull String mac) {
    if (! StringUtils.isAllBlank(config.getString("hostname"))) {
      return config.getString("hostname");
    }

    return "ecotracker-" + mac.toLowerCase();
  }
  
  /**
   * Register the EcoTracker in mDNS
   */
  protected void registerMDns() {
    logger.trace("EcoTracer.registerMDns()");

    getMdnsRegistrator().tell(
          new MDnsRegistrator.RegisterService(
                "_http",
                getDefaultHostname(),
                getBindPort(),
                Map.of(
                      "name", getDefaultHostname(),
                      "id", getDefaultHostname()
                )
          )
    );
    getMdnsRegistrator().tell(
          new MDnsRegistrator.RegisterService(
                "_everhome",
                getDefaultHostname(),
                getBindPort(),
                Map.of(
                      "name", getDefaultHostname(),
                      "id", getDefaultHostname()
                )
          )
    );
  }
  
  private record PowerHistory(
        @NotNull Instant timestamp,
        double power
  ) {}
  
  public record V1GetJson(
        @NotNull InetAddress remoteAddress,
        @NotNull ActorRef<V1GetJsonResponse> replyTo
  ) implements Command {}
  
  public record V1GetJsonResponse(
        @Nullable V1Json json,
        @Nullable Throwable failure
  ) {
    public static V1GetJsonResponse of(@NotNull Throwable failure) {
      return new V1GetJsonResponse(null, failure);
    }
    public static V1GetJsonResponse of(@NotNull V1Json json) {
      return new V1GetJsonResponse(json, null);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonPropertyOrder({"power", "powerAvg", "energyCounterIn", "energyCounterInT1", "energyCounterInT2",
        "energyCounterOut"})
  public record V1Json(
        @JsonProperty("power") long power,
        @JsonProperty("powerAvg") long powerAvg,
        @JsonProperty("powerPhase1") Double powerPhase1,
        @JsonProperty("powerPhase2") Double powerPhase2,
        @JsonProperty("powerPhase3") Double powerPhase3,
        @JsonProperty("energyCounterIn") Double energyCounterIn,
        @JsonProperty("energyCounterInT1") Double energyCounterInT1,
        @JsonProperty("energyCounterInT2") Double energyCounterInT2,
        @JsonProperty("energyCounterOut") Double energyCounterOut
  ) {}
}
