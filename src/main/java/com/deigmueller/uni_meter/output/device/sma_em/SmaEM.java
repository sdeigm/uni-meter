package com.deigmueller.uni_meter.output.device.sma_em;

import com.deigmueller.uni_meter.application.UniMeter;
import com.deigmueller.uni_meter.input.device.sma.energy_meter.ObisChannel;
import com.deigmueller.uni_meter.mdns.MDnsRegistrator;
import com.deigmueller.uni_meter.output.ClientContextsInitializer;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.typesafe.config.Config;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.PostStop;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.apache.pekko.http.javadsl.server.Route;
import org.jetbrains.annotations.NotNull;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.time.Duration;

public class SmaEM extends OutputDevice {
  public static final String TYPE = "SmaEM";

  private final Duration sendInterval = getConfig().getDuration("send-interval");
  private final String multicastGroup = getConfig().getString("group");
  private final int multicastPort = getConfig().getInt("port");
  private final int multicastTtl = getConfig().getInt("ttl");
  private final int susyId = getConfig().getInt("susy-id");
  private final long serialNumber = getConfig().getLong("serial-number");
  private final EmeterPacket packet;
  private final InetAddress groupAddress;
  private MulticastSocket socket;

  public static Behavior<Command> create(@NotNull ActorRef<UniMeter.Command> controller,
                                         @NotNull ActorRef<MDnsRegistrator.Command> mDnsRegistrator,
                                         @NotNull Config config) {
    return Behaviors.setup(context -> new SmaEM(context, controller, mDnsRegistrator, config));
  }

  protected SmaEM(@NotNull ActorContext<Command> context,
                  @NotNull ActorRef<UniMeter.Command> controller,
                  @NotNull ActorRef<MDnsRegistrator.Command> mDnsRegistrator,
                  @NotNull Config config) {
    super(context, controller, mDnsRegistrator, config, ClientContextsInitializer.empty());
    warnIfSerialTruncated();
    this.packet = new EmeterPacket(serialNumber, susyId);
    this.groupAddress = createGroupAddress();
    this.socket = createSocket();

    scheduleNextSend();
  }

  @Override
  public @NotNull ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onMessage(SendTick.class, this::onSendTick);
  }

  @Override
  protected Route createRoute() {
    return null;
  }

  @Override
  protected void eventPowerDataChanged() {
    // handled by send loop
  }

  private Behavior<Command> onSendTick(@NotNull SendTick message) {
    trySendPacket();
    scheduleNextSend();
    return Behaviors.same();
  }

  private void scheduleNextSend() {
    getContext().getSystem().scheduler().scheduleOnce(
          sendInterval,
          () -> getContext().getSelf().tell(SendTick.INSTANCE),
          getContext().getExecutionContext());
  }

  @Override
  protected @NotNull Behavior<Command> onPostStop(@NotNull PostStop message) {
    closeSocket();
    return Behaviors.same();
  }

  private void trySendPacket() {
    if (isSwitchedOff()) {
      return;
    }

    PowerData p0 = getPowerPhase0OrDefault();
    PowerData p1 = getPowerPhase1OrDefault();
    PowerData p2 = getPowerPhase2OrDefault();

    double totalPower = p0.power() + p1.power() + p2.power();
    if (checkUsageConstraint(totalPower)) {
      return;
    }

    EnergyData e0 = getEnergyPhase0();
    EnergyData e1 = getEnergyPhase1();
    EnergyData e2 = getEnergyPhase2();

    packet.begin(System.currentTimeMillis());

    // totals
    addPowerPair(ObisChannel.CHANNEL_1_4_0, ObisChannel.CHANNEL_2_4_0, totalPower);
    addEnergyPair(ObisChannel.CHANNEL_1_8_0, ObisChannel.CHANNEL_2_8_0,
          e0.totalConsumption() + e1.totalConsumption() + e2.totalConsumption(),
          e0.totalProduction() + e1.totalProduction() + e2.totalProduction());
    addPowerPair(ObisChannel.CHANNEL_3_4_0, ObisChannel.CHANNEL_4_4_0, 0.0);
    addEnergyPair(ObisChannel.CHANNEL_3_8_0, ObisChannel.CHANNEL_4_8_0, 0.0, 0.0);
    addPowerPair(ObisChannel.CHANNEL_9_4_0, ObisChannel.CHANNEL_10_4_0,
          p0.apparentPower() + p1.apparentPower() + p2.apparentPower());
    addEnergyPair(ObisChannel.CHANNEL_9_8_0, ObisChannel.CHANNEL_10_8_0, 0.0, 0.0);
    packet.addMeasurementValue(ObisChannel.CHANNEL_13_4_0, scalePowerFactor(totalPower, p0.apparentPower() + p1.apparentPower() + p2.apparentPower()));
    packet.addMeasurementValue(ObisChannel.CHANNEL_14_4_0, scaleFrequency(p0.frequency()));

    // phase L1
    addPowerPair(ObisChannel.CHANNEL_21_4_0, ObisChannel.CHANNEL_22_4_0, p0.power());
    addEnergyPair(ObisChannel.CHANNEL_21_8_0, ObisChannel.CHANNEL_22_8_0, e0.totalConsumption(), e0.totalProduction());
    addPowerPair(ObisChannel.CHANNEL_23_4_0, ObisChannel.CHANNEL_24_4_0, 0.0);
    addEnergyPair(ObisChannel.CHANNEL_23_8_0, ObisChannel.CHANNEL_24_8_0, 0.0, 0.0);
    addPowerPair(ObisChannel.CHANNEL_29_4_0, ObisChannel.CHANNEL_30_4_0, p0.apparentPower());
    addEnergyPair(ObisChannel.CHANNEL_29_8_0, ObisChannel.CHANNEL_30_8_0, 0.0, 0.0);
    packet.addMeasurementValue(ObisChannel.CHANNEL_31_4_0, scaleCurrent(p0.current()));
    packet.addMeasurementValue(ObisChannel.CHANNEL_32_4_0, scaleVoltage(p0.voltage()));
    packet.addMeasurementValue(ObisChannel.CHANNEL_33_4_0, scalePowerFactor(p0.powerFactor()));

    // phase L2
    addPowerPair(ObisChannel.CHANNEL_41_4_0, ObisChannel.CHANNEL_42_4_0, p1.power());
    addEnergyPair(ObisChannel.CHANNEL_41_8_0, ObisChannel.CHANNEL_42_8_0, e1.totalConsumption(), e1.totalProduction());
    addPowerPair(ObisChannel.CHANNEL_43_4_0, ObisChannel.CHANNEL_44_4_0, 0.0);
    addEnergyPair(ObisChannel.CHANNEL_43_8_0, ObisChannel.CHANNEL_44_8_0, 0.0, 0.0);
    addPowerPair(ObisChannel.CHANNEL_49_4_0, ObisChannel.CHANNEL_50_4_0, p1.apparentPower());
    addEnergyPair(ObisChannel.CHANNEL_49_8_0, ObisChannel.CHANNEL_50_8_0, 0.0, 0.0);
    packet.addMeasurementValue(ObisChannel.CHANNEL_51_4_0, scaleCurrent(p1.current()));
    packet.addMeasurementValue(ObisChannel.CHANNEL_52_4_0, scaleVoltage(p1.voltage()));
    packet.addMeasurementValue(ObisChannel.CHANNEL_53_4_0, scalePowerFactor(p1.powerFactor()));

    // phase L3
    addPowerPair(ObisChannel.CHANNEL_61_4_0, ObisChannel.CHANNEL_62_4_0, p2.power());
    addEnergyPair(ObisChannel.CHANNEL_61_8_0, ObisChannel.CHANNEL_62_8_0, e2.totalConsumption(), e2.totalProduction());
    addPowerPair(ObisChannel.CHANNEL_63_4_0, ObisChannel.CHANNEL_64_4_0, 0.0);
    addEnergyPair(ObisChannel.CHANNEL_63_8_0, ObisChannel.CHANNEL_64_8_0, 0.0, 0.0);
    addPowerPair(ObisChannel.CHANNEL_69_4_0, ObisChannel.CHANNEL_70_4_0, p2.apparentPower());
    addEnergyPair(ObisChannel.CHANNEL_69_8_0, ObisChannel.CHANNEL_70_8_0, 0.0, 0.0);
    packet.addMeasurementValue(ObisChannel.CHANNEL_71_4_0, scaleCurrent(p2.current()));
    packet.addMeasurementValue(ObisChannel.CHANNEL_72_4_0, scaleVoltage(p2.voltage()));
    packet.addMeasurementValue(ObisChannel.CHANNEL_73_4_0, scalePowerFactor(p2.powerFactor()));

    packet.end();

    byte[] data = packet.getData();
    int length = packet.getLength();

    try {
      DatagramPacket datagramPacket = new DatagramPacket(data, length, groupAddress, multicastPort);
      socket.send(datagramPacket);
    } catch (Exception e) {
      logger.warn("failed to send SMA energy meter multicast packet", e);
    }
  }

  private void addPowerPair(long positiveId, long negativeId, double power) {
    double positive = Math.max(power, 0.0);
    double negative = Math.max(-power, 0.0);
    packet.addMeasurementValue(positiveId, scalePower(positive));
    packet.addMeasurementValue(negativeId, scalePower(negative));
  }

  private void addEnergyPair(long positiveId, long negativeId, double consumptionWh, double productionWh) {
    packet.addCounterValue(positiveId, scaleEnergy(consumptionWh));
    packet.addCounterValue(negativeId, scaleEnergy(productionWh));
  }

  private long scalePower(double watts) {
    return Math.round(Math.max(0.0, watts) * 10.0);
  }

  private long scaleCurrent(double amps) {
    return Math.round(Math.max(0.0, amps) * 1000.0);
  }

  private long scaleVoltage(double volts) {
    return Math.round(Math.max(0.0, volts) * 1000.0);
  }

  private long scalePowerFactor(double powerFactor) {
    double clamped = Math.max(-1.0, Math.min(1.0, powerFactor));
    return Math.round(Math.abs(clamped) * 1000.0);
  }

  private long scalePowerFactor(double totalPower, double totalApparentPower) {
    if (totalApparentPower == 0.0) {
      return scalePowerFactor(1.0);
    }
    return scalePowerFactor(totalPower / totalApparentPower);
  }

  private long scaleFrequency(double frequencyHz) {
    return Math.round(Math.max(0.0, frequencyHz) * 1000.0);
  }

  private long scaleEnergy(double energyWh) {
    // SMA energy channels use a divisor of 3600000 (Wh -> kWh), so scale by 3600.
    return Math.round(Math.max(0.0, energyWh) * 3600.0);
  }

  private InetAddress createGroupAddress() {
    try {
      return InetAddress.getByName(multicastGroup);
    } catch (Exception e) {
      throw new IllegalArgumentException("invalid multicast group: " + multicastGroup, e);
    }
  }

  private MulticastSocket createSocket() {
    try {
      MulticastSocket multicastSocket = new MulticastSocket();
      multicastSocket.setTimeToLive(multicastTtl);

      return multicastSocket;
    } catch (Exception e) {
      throw new RuntimeException("failed to create multicast socket", e);
    }
  }

  private void closeSocket() {
    if (socket != null) {
      socket.close();
      socket = null;
    }
  }

  private void warnIfSerialTruncated() {
    if (serialNumber > 0xFFFFFFFFL || serialNumber < 0) {
      long truncated = serialNumber & 0xFFFFFFFFL;
      logger.warn(
            "serial-number {} exceeds 32-bit range; SMA packet uses {}",
            serialNumber,
            truncated
      );
    }
  }

  private enum SendTick implements Command {
    INSTANCE
  }
}
