package com.deigmueller.uni_meter.output;

import com.deigmueller.uni_meter.application.UniMeter;
import com.deigmueller.uni_meter.mdns.MDnsRegistrator;
import com.typesafe.config.Config;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.PostStop;
import org.apache.pekko.actor.typed.javadsl.*;
import org.apache.pekko.http.javadsl.server.Route;
import org.apache.pekko.stream.Materializer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.NotNull;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter(AccessLevel.PROTECTED)
@Setter(AccessLevel.PROTECTED)
public abstract class OutputDevice extends AbstractBehavior<OutputDevice.Command> {
  // Instance members
  protected final Logger logger = LoggerFactory.getLogger("uni-meter.output");
  private final Materializer materializer = Materializer.createMaterializer(getContext());
  private final ActorRef<UniMeter.Command> controller;
  private final ActorRef<MDnsRegistrator.Command> mdnsRegistrator;
  private final Config config;
  private final Duration forgetInterval;
  private final double defaultVoltage;
  private final double defaultFrequency;
  private final double defaultClientPowerFactor;
  private final Map<InetAddress, ClientContext> clientContexts = new HashMap<>();
  private final List<TimerOverride> timerOverrides = new ArrayList<>();
  
  private Instant lastPowerPhase0Update = Instant.now();
  private double offsetPhase0 = 0;
  private PowerData powerPhase0 = new PowerData(0, 0, 0, 0, 230.0, 50.0);
  private Instant lastPowerPhase1Update = Instant.now();
  private double offsetPhase1 = 0;
  private PowerData powerPhase1 = new PowerData(0, 0, 0, 0, 230.0, 50.0);
  private Instant lastPowerPhase2Update = Instant.now();
  private double offsetPhase2 = 0;
  private PowerData powerPhase2 = new PowerData(0, 0, 0, 0, 230.0, 50.0);

  private Instant lastEnergyPhase0Update = Instant.now();
  private EnergyData energyPhase0 = new EnergyData(0, 0);
  private Instant lastEnergyPhase1Update = Instant.now();
  private EnergyData energyPhase1 = new EnergyData(0, 0);
  private Instant lastEnergyPhase2Update = Instant.now();
  private EnergyData energyPhase2 = new EnergyData(0, 0);
  
  protected OutputDevice(@NotNull ActorContext<Command> context,
                         @NotNull ActorRef<UniMeter.Command> controller,
                         @NotNull ActorRef<MDnsRegistrator.Command> mDnsRegistrator,
                         @NotNull Config config,
                         @NotNull ClientContextsInitializer clientContextsInitializer) {
    super(context);
    this.controller = controller;
    this.mdnsRegistrator = mDnsRegistrator;
    this.config = config;
    this.forgetInterval = config.getDuration("forget-interval");
    this.defaultVoltage = config.getDouble("default-voltage");
    this.defaultFrequency = config.getDouble("default-frequency");
    this.defaultClientPowerFactor = config.getDouble("default-client-power-factor");
    
    if (config.hasPath("timer-overrides")) {
      for (Config timerOverrideConfig : config.getConfigList("timer-overrides")) {
        timerOverrides.add(new TimerOverride(timerOverrideConfig));
      }
    }
    
    initPowerOffsets(config);

    if (config.hasPath("client-contexts")) {
      clientContextsInitializer.initClientContexts(
            logger,
            config.getConfigList("client-contexts"), clientContexts);
      
      for (ClientContext clientContext : clientContexts.values()) {
        logger.info("{}", clientContext);
      } 
    }
  }
  
  @Override
  public Receive<Command> createReceive() {
    return newReceiveBuilder().build();
  }

  @Override
  public ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onSignal(PostStop.class, this::onPostStop)
          .onMessage(NotifyPhasePowerData.class, this::onNotifyPhasePowerData)
          .onMessage(NotifyPhasesPowerData.class, this::onNotifyPhasesPowerData)
          .onMessage(NotifyTotalPowerData.class, this::onNotifyTotalPowerData)
          .onMessage(NotifyPhaseEnergyData.class, this::onNotifyPhaseEnergyData)
          .onMessage(NotifyPhasesEnergyData.class, this::onNotifyPhasesEnergyData)
          .onMessage(NotifyTotalEnergyData.class, this::onNotifyTotalEnergyData);
  }

  /**
   * Handle the post stop signal
   * @param message Post stop signal
   */
  protected @NotNull Behavior<Command> onPostStop(@NotNull PostStop message) {
    logger.trace("OutputDevice.onPostStop()");

    return Behaviors.same();
  }

  /**
   * Handle the notification of power data
   * @param message Notification of power data
   * @return Same behavior
   */
  protected @NotNull Behavior<Command> onNotifyPhasePowerData(@NotNull NotifyPhasePowerData message) {
    logger.trace("OutputDevice.onNotifyPhasePowerData()");

    switch (message.phaseId()) {
      case 0 -> setPowerPhase0(message.data());
      case 1 -> setPowerPhase1(message.data());
      case 2 -> setPowerPhase2(message.data());
    }

    message.replyTo().tell(new Ack(message.messageId()));
    
    eventPowerDataChanged();

    return Behaviors.same();
  }

  /**
   * Handle the notification of power data
   * @param message Notification of power data
   * @return Same behavior
   */
  protected @NotNull Behavior<Command> onNotifyPhasesPowerData(@NotNull NotifyPhasesPowerData message) {
    logger.trace("OutputDevice.onNotifyPhasesPowerData()");
    
    setPowerPhase0(message.phase1());
    setPowerPhase1(message.phase2());
    setPowerPhase2(message.phase3());

    message.replyTo().tell(new Ack(message.messageId()));

    eventPowerDataChanged();

    return Behaviors.same();
  }

  /**
   * Handle the notification of total power data
   * @param message Notification of total power data
   * @return Same behavior
   */
  protected @NotNull Behavior<Command> onNotifyTotalPowerData(@NotNull NotifyTotalPowerData message) {
    logger.trace("OutputDevice.onNotifyTotalPowerData()");

    PowerData data = message.data();

    double phasePower = Math.round(data.power() / 3.0 * 100.0) / 100.0;
    double phaseApparentPower = Math.round(data.apparentPower() / 3.0 * 100.0) / 100.0;
    double phaseCurrent = Math.round(data.current() / 3.0 * 100.0) / 100.0;
    
    PowerData phasePowerData = new PowerData(
            phasePower, 
            phaseApparentPower, 
            data.powerFactor(), 
            phaseCurrent, 
            data.voltage(), 
            data.frequency()); 

    setPowerPhase0(phasePowerData);
    setPowerPhase1(phasePowerData);
    setPowerPhase2(phasePowerData);

    message.replyTo().tell(new Ack(message.messageId()));

    eventPowerDataChanged();

    return Behaviors.same();
  }

  /**
   * Handle the notification of phase energy data
   * @param message Notification of energy data
   * @return Same behavior
   */
  protected @NotNull Behavior<Command> onNotifyPhaseEnergyData(@NotNull OutputDevice.NotifyPhaseEnergyData message) {
    logger.trace("OutputDevice.onNotifyPhaseEnergyData()");

    switch (message.phaseId()) {
      case 0 -> setEnergyPhase0(message.data());
      case 1 -> setEnergyPhase1(message.data());
      case 2 -> setEnergyPhase2(message.data());
    }

    message.replyTo().tell(new Ack(message.messageId()));

    return Behaviors.same();
  }

  /**
   * Handle the notification of the energy data for all 3 phases
   * @param message Notification of energy data
   * @return Same behavior
   */
  protected @NotNull Behavior<Command> onNotifyPhasesEnergyData(@NotNull OutputDevice.NotifyPhasesEnergyData message) {
    logger.trace("OutputDevice.onNotifyPhasesEnergyData()");
    
    setEnergyPhase0(message.phase1());
    setEnergyPhase1(message.phase2());
    setEnergyPhase2(message.phase3());

    message.replyTo().tell(new Ack(message.messageId()));

    return Behaviors.same();
  }

  /**
   * Handle the notification of total energy data
   * @param message Notification of total energy data
   * @return Same behavior
   */
  protected @NotNull Behavior<Command> onNotifyTotalEnergyData(@NotNull OutputDevice.NotifyTotalEnergyData message) {
    logger.trace("OutputDevice.onNotifyTotalEnergyData()");

    EnergyData data = message.data();

    double phaseConsumption = Math.round(data.totalConsumption() / 3.0 * 100.0) / 100.0;
    double phaseProduction = Math.round(data.totalProduction() / 3.0 * 100.0) / 100.0;

    EnergyData phaseEnergyData = new EnergyData(phaseConsumption, phaseProduction); 
    setEnergyPhase0(phaseEnergyData);
    setEnergyPhase1(phaseEnergyData);
    setEnergyPhase2(phaseEnergyData);

    return Behaviors.same();
  }
  
  protected void setPowerPhase0(@NotNull PowerData powerPhase0) {
    this.lastPowerPhase0Update = Instant.now();
    this.powerPhase0 = powerPhase0.adjustPower(offsetPhase0);
    logger.debug("power phase 0: {}", this.powerPhase0);
  }
  
  protected @Nullable PowerData getPowerPhase0() {
    if (Duration.between(this.lastPowerPhase0Update, Instant.now()).compareTo(forgetInterval) > 0) {
      return null;
    } else {
      return this.powerPhase0;
    }
  }

  protected @NotNull PowerData getPowerPhase0OrDefault() {
    PowerData powerData = getPowerPhase0();
    if (powerData != null) {
      return powerData;
    }
    return new PowerData(0, 0, 0, 0, getDefaultVoltage(), getDefaultFrequency());
  }
  
  protected void setPowerPhase1(@NotNull PowerData powerPhase1) {
    this.lastPowerPhase1Update = Instant.now();
    this.powerPhase1 = powerPhase1.adjustPower(offsetPhase1);
    logger.debug("power phase 1: {}", this.powerPhase1);
  }
  
  protected @Nullable PowerData getPowerPhase1() {
    if (Duration.between(this.lastPowerPhase1Update, Instant.now()).compareTo(forgetInterval) > 0) {
      return null;
    } else {
      return this.powerPhase1;
    }
  }

  protected @NotNull PowerData getPowerPhase1OrDefault() {
    PowerData powerData = getPowerPhase1();
    if (powerData != null) {
      return powerData;
    }
    return new PowerData(0, 0, 0, 0, getDefaultVoltage(), getDefaultFrequency());
  }
  
  protected void setPowerPhase2(@NotNull PowerData powerPhase2) {
    this.lastPowerPhase2Update = Instant.now();
    this.powerPhase2 = powerPhase2.adjustPower(offsetPhase2);
    logger.debug("power phase 2: {}", this.powerPhase2);
  }
  
  protected @Nullable PowerData getPowerPhase2() {
    if (Duration.between(this.lastPowerPhase2Update, Instant.now()).compareTo(forgetInterval) > 0) {
      return null;
    } else {
      return this.powerPhase2;
    }
  }

  protected @NotNull PowerData getPowerPhase2OrDefault() {
    PowerData powerData = getPowerPhase2();
    if (powerData != null) {
      return powerData;
    }
    return new PowerData(0, 0, 0, 0, getDefaultVoltage(), getDefaultFrequency());
  }
  
  protected void setEnergyPhase0(@NotNull EnergyData energyPhase0) {
    this.lastEnergyPhase0Update = Instant.now();
    this.energyPhase0 = energyPhase0;
    logger.debug("energy phase 0: {}", energyPhase0);
  }
  
  protected @NotNull EnergyData getEnergyPhase0() {
    if (Duration.between(this.lastEnergyPhase0Update, Instant.now()).compareTo(forgetInterval) > 0) {
      return new EnergyData(0, 0);
    } else {
      return this.energyPhase0;
    }
  }
  
  protected void setEnergyPhase1(@NotNull EnergyData energyPhase1) {
    this.lastEnergyPhase1Update = Instant.now();
    this.energyPhase1 = energyPhase1;
    logger.debug("energy phase 1: {}", energyPhase0);
  }
  
  protected @NotNull EnergyData getEnergyPhase1() {
    if (Duration.between(this.lastEnergyPhase1Update, Instant.now()).compareTo(forgetInterval) > 0) {
      return new EnergyData(0, 0);
    } else {
      return this.energyPhase1;
    }
  }
  
  protected void setEnergyPhase2(@NotNull EnergyData energyPhase2) {
    this.lastEnergyPhase2Update = Instant.now();
    this.energyPhase2 = energyPhase2;
    logger.debug("energy phase 2: {}", energyPhase0);
  }
  
  protected @NotNull EnergyData getEnergyPhase2() {
    if (Duration.between(this.lastEnergyPhase2Update, Instant.now()).compareTo(forgetInterval) > 0) {
      return new EnergyData(0, 0);
    } else {
      return this.energyPhase2;
    }
  }
  
  protected abstract Route createRoute();
  
  protected abstract void eventPowerDataChanged();
  
  private @Nullable TimerOverride getPowerTimerOverride() {
    LocalDateTime now = LocalDateTime.now();
    
    for (TimerOverride timerOverride : timerOverrides) {
      if (timerOverride.matches(now)) {
        return timerOverride;
      }
    }
    return null;  
  }
  
  protected void initPowerOffsets(@NotNull Config config) {
    offsetPhase0 = config.getDouble("power-offset-l1");
    offsetPhase1 = config.getDouble("power-offset-l2");
    offsetPhase2 = config.getDouble("power-offset-l3");
    
    if (offsetPhase0 == 0 && offsetPhase1 == 0 && offsetPhase2 == 0) {
      double totalOffset = config.getDouble("power-offset-total");
      if (totalOffset != 0) {
        offsetPhase0 = totalOffset / 3.0;
        offsetPhase1 = totalOffset / 3.0;
        offsetPhase2 = totalOffset / 3.0;
        logger.info("using total power offset of {}", totalOffset);
      }
    } else {
      logger.info("using phase power offsets: L1={}, L2={}, L3={}", offsetPhase0, offsetPhase1, offsetPhase2);
    }
  }

  /**
   * Get the power factor for the given remote address
   * @param remoteAddress Remote address
   * @return Power factor
   */
  protected double getPowerFactorForRemoteAddress(@NotNull InetAddress remoteAddress) {
    ClientContext clientContext = clientContexts.get(remoteAddress);
    if (clientContext != null && clientContext.powerFactor() != null) {
      logger.trace("power factor for {} is {}", remoteAddress, clientContext.powerFactor());
      return clientContext.powerFactor();
    } else {
      logger.trace("using default power factor {} for {}", defaultClientPowerFactor, remoteAddress);
      return defaultClientPowerFactor;
    }
  }
  
  public interface Command {}

  public record NotifyPhasePowerData(
        int messageId,
        int phaseId,
        @NotNull PowerData data,
        @NotNull ActorRef<Ack> replyTo
  ) implements Command {}

  public record NotifyPhasesPowerData(
        int messageId,
        @NotNull PowerData phase1,
        @NotNull PowerData phase2,
        @NotNull PowerData phase3,
        @NotNull ActorRef<Ack> replyTo
  ) implements Command {}

  public record NotifyTotalPowerData(
        int messageId,
        @NotNull PowerData data, 
        @NotNull ActorRef<Ack> replyTo
  ) implements Command {}

  public record PowerData(
        double power,
        double apparentPower,
        double powerFactor,
        double current,
        double voltage,
        double frequency
  ) {
    public PowerData adjustPower(double offset) {
      if (offset == 0) {
        return this;
      } else {
        double adjustedPower = power + offset;
        double adjustedApparentPower = adjustedPower * powerFactor;
        double adjustedCurrent = adjustedPower / voltage;
        return new PowerData(adjustedPower, adjustedApparentPower, powerFactor, adjustedCurrent, voltage, frequency);
      }
    }
  }
  
  public record NotifyPhaseEnergyData(
        int messageId,
        int phaseId,
        @NotNull OutputDevice.EnergyData data,
        @NotNull ActorRef<Ack> replyTo
  ) implements Command {}

  public record NotifyPhasesEnergyData(
        int messageId,
        @NotNull OutputDevice.EnergyData phase1,
        @NotNull OutputDevice.EnergyData phase2,
        @NotNull OutputDevice.EnergyData phase3,
        @NotNull ActorRef<Ack> replyTo
  ) implements Command {}

  public record NotifyTotalEnergyData(
        int messageId,
        @NotNull OutputDevice.EnergyData data,
        @NotNull ActorRef<Ack> replyTo
  ) implements Command {}

  public record EnergyData(
        double totalConsumption,
        double totalProduction
  ) {}
  
  public record Ack(
        int messageId
  ) {}
  
}
