package com.deigmueller.uni_meter.input.device.shrdzm;

import com.deigmueller.uni_meter.input.InputDevice;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import lombok.AccessLevel;
import lombok.Getter;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.DispatcherSelector;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.jetbrains.annotations.NotNull;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@Getter(AccessLevel.PROTECTED)
public class ShrDzm extends InputDevice {
  // Class members
  public static final String TYPE = "ShrDzm";

  // Instance members
  private final Executor executor = getContext().getSystem().dispatchers().lookup(DispatcherSelector.blocking());
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final int port = getConfig().getInt("port");
  private final String bindInterface = getConfig().getString("interface");
  private final Duration socketTimeout = getConfig().getDuration("socket-timeout");
  private final PhaseMode powerPhaseMode = getPhaseMode("power-phase-mode");
  private final String powerPhase = getConfig().getString("power-phase");
  private final PhaseMode energyPhaseMode = getPhaseMode("energy-phase-mode");
  private final String energyPhase = getConfig().getString("energy-phase");
  private final AtomicBoolean doReceive = new AtomicBoolean(true);

  /**
   * Static factory method to create a new instance of the ShrDzm actor.
   * @param outputDevice The output device actor reference
   * @param config The input device configuration
   * @return Behavior of the ShrDzm actor
   */
  public static Behavior<Command> create(@NotNull ActorRef<OutputDevice.Command> outputDevice,
                                         @NotNull Config config) {
    return Behaviors.setup(context -> new ShrDzm(context, outputDevice, config));
  }

  /**
   * Protected constructor called by the static factory method.
   * @param context The actor context
   * @param outputDevice The output device actor reference
   * @param config The input device configuration
   */
  protected ShrDzm(@NotNull ActorContext<Command> context,
                   @NotNull ActorRef<OutputDevice.Command> outputDevice,
                   @NotNull Config config) {
    super(context, outputDevice, config);
    
    startReceiveLoop();
  }

  /**
   * Create the actor's ReceiveBuilder
   * @return The actor's ReceiveBuilder
   */
  @Override
  public ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onMessage(ReceiveLoopStarted.class, this::onReceiveLoopStarted)
          .onMessage(ReceiveLoopFinished.class, this::onReceiveLoopFinished)
          .onMessage(ReceiveLoopFailed.class, this::onReceiveLoopFailed)
          .onMessage(NotifyShrDzmPacket.class, this::onNotifyNotifyShrDzmPacket);
  }

  /**
   * Handle the notification that the receive-loop is started.
   * @param message The message
   * @return Same behavior
   */
  protected Behavior<Command> onReceiveLoopStarted(ReceiveLoopStarted message) {
    logger.trace("ShrDzm.onReceiveLoopStarted()");

    logger.info("receive loop for notifications from the ShrDzm device started");

    return Behaviors.same();
  }

  /**
   * Handle the notification that the receive-loop is finished.
   * @param message The message
   * @return Same behavior
   */
  protected Behavior<Command> onReceiveLoopFinished(ReceiveLoopFinished message) {
    logger.trace("ShrDzm.onReceiveLoopFinished()");

    logger.info("receive loop finished");

    return Behaviors.same();
  }

  /**
   * Handle the notification that the receive-loop failed.
   * @param message The message
   * @return Same behavior
   */
  protected Behavior<Command> onReceiveLoopFailed(ReceiveLoopFailed message) {
    logger.trace("ShrDzm.onReceiveLoopFailed()");

    logger.error("receive loop failed:", message.exception());

    throw new RuntimeException("EnergyMeter input failed: " + message.exception.getMessage(), message.exception());
  }

  /**
   * Handle the notification of a ShrDzm packet.
   * @param message The message
   * @return Same behavior
   */
  protected Behavior<Command> onNotifyNotifyShrDzmPacket(NotifyShrDzmPacket message) {
    logger.trace("ShrDzm.onNotifyNotifyShrDzmPacket()");

    logger.debug("received ShrDzm packet: {}", message.packet());

    notifyPowerDataSinglePhase(message.packet());

    notifyEnergyData(message.packet());

    return Behaviors.same();
  }
  
  private void notifyPowerDataSinglePhase(ShrDzmPacket shrDzmPacket) {
    logger.trace("ShrDzm.notifyPowerDataSinglePhase()");

    try {
      String powerString = shrDzmPacket.data().get("16.7.0");
      if (powerString == null) {
        logger.debug("no power data found for channel 16.7.0");
        return;
      } else {
        logger.debug("power data found for channel 16.7.0: {}", powerString);
      }
      
      double power = Double.parseDouble(powerString);
      
      notifyPowerData(powerPhaseMode, powerPhase, power);
    } catch (Exception e) {
      logger.error("failed to notify single phase power data: {}", e.getMessage());
    }
  }
  
  private void notifyEnergyData(ShrDzmPacket shrDzmPacket) {
    logger.trace("ShrDzm.notifyEnergyData()");

    try {
      double consumption = 0.0;
      String consumptionString = shrDzmPacket.data().get("1.8.0");
      if (consumptionString == null) {
        logger.debug("no consumption data found for channel 1.8.0");
      } else {
        logger.debug("consumption data found for channel 1.8.0: {}", consumptionString);
        consumption = Double.parseDouble(consumptionString);
      }
      
      double production = 0.0;
      String productionString = shrDzmPacket.data().get("2.8.0");
      if (productionString == null) {
        logger.debug("no production data found for channel 2.8.0");
      } else {
        logger.debug("production data found for channel 2.8.0: {}", productionString);
        production = Double.parseDouble(productionString);
      }

      notifyEnergyData(energyPhaseMode, energyPhase, consumption / 1000.0, production / 1000.0);
    } catch (Exception e) {
      logger.error("failed to notify energy data: {}", e.getMessage());
    }
  }
  
  private void startReceiveLoop() {
    executor.execute(() -> {
      try (DatagramSocket socket = new DatagramSocket(this.port, InetAddress.getByName(bindInterface))) {
        logger.info("successfully bound to UDP socket {}:{}", bindInterface, port);
        
        socket.setSoTimeout((int) Math.min(socketTimeout.toMillis(), Integer.MAX_VALUE));

        getContext().getSelf().tell(ReceiveLoopStarted.INSTANCE);

        DatagramPacket datagramPacket = new DatagramPacket(new byte[1024], 1024);
        while (doReceive.get()) {
          try {
            socket.receive(datagramPacket);

            try  {
              ShrDzmPacket shrDzmPacket = objectMapper.readValue(datagramPacket.getData(), 0, datagramPacket.getLength(), ShrDzmPacket.class);
              
              getContext().getSelf().tell(new NotifyShrDzmPacket(shrDzmPacket));
            } catch (Exception e) {
              logger.debug("dropping invalid packet: {}", e.getMessage());
            }
          } catch (SocketTimeoutException ignore) {
            // Ignore
          }
        }

        getContext().getSelf().tell(ReceiveLoopFinished.INSTANCE);

      } catch (Exception e) {
        getContext().getSelf().tell(new ReceiveLoopFailed(e));
      }
    });
  }

  public enum ReceiveLoopStarted implements Command {
    INSTANCE
  }

  public enum ReceiveLoopFinished implements Command {
    INSTANCE
  }

  public record ReceiveLoopFailed(
        @NotNull Throwable exception
  ) implements Command {}

  public record NotifyShrDzmPacket(
        @NotNull ShrDzmPacket packet
  ) implements Command {}
}
