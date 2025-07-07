package com.deigmueller.uni_meter.input.device.sma.energy_meter;

import com.deigmueller.uni_meter.input.InputDevice;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.digitalpetri.modbus.internal.util.Hex;
import com.typesafe.config.Config;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.DispatcherSelector;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

public class EnergyMeter extends InputDevice {
  // Class members
  public static final String TYPE = "SMAEnergyMeter";
  
  // Instance members
  private final Executor executor = getContext().getSystem().dispatchers().lookup(DispatcherSelector.blocking());
  private final int port = getConfig().getInt("port");
  private final String group = getConfig().getString("group");
  private final List<String> networkInterface = getConfig().getStringList("network-interfaces");
  private final Duration socketTimeout = getConfig().getDuration("socket-timeout");
  private final AtomicBoolean doReceive = new AtomicBoolean(true);
  private int susyId = getConfig().getInt("susy-id");
  private long serialNumber = getConfig().getLong("serial-number");
  
  public static Behavior<Command> create(@NotNull ActorRef<OutputDevice.Command> outputDevice,
                                         @NotNull Config config) {
    return Behaviors.setup(context -> new EnergyMeter(context, outputDevice, config));
  }

  protected EnergyMeter(@NotNull ActorContext<Command> context,
                        @NotNull ActorRef<OutputDevice.Command> outputDevice,
                        @NotNull Config config) {
    super(context, outputDevice, config);
    
    startReceiveLoop();
  }
  
  @Override
  public ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onMessage(ReceiveLoopStarted.class, this::onReceiveLoopStarted)
          .onMessage(ReceiveLoopFinished.class, this::onReceiveLoopFinished)
          .onMessage(ReceiveLoopFailed.class, this::onReceiveLoopFailed)
          .onMessage(NotifyEnergyMeterPacket.class, this::onNotifyEnergyMeterPacket);
  }
  
  protected Behavior<Command> onReceiveLoopStarted(ReceiveLoopStarted message) {
    logger.trace("EnergyMeter.onReceiveLoopStarted()");
    
    logger.info("starting receive loop for notifications from the SMA energy meter");
    
    return Behaviors.same();
  }
  
  protected Behavior<Command> onReceiveLoopFinished(ReceiveLoopFinished message) {
    logger.trace("EnergyMeter.onReceiveLoopFinished()");
    
    logger.info("receive loop finished");
    
    return Behaviors.same();
  }
  
  protected Behavior<Command> onReceiveLoopFailed(ReceiveLoopFailed message) {
    logger.trace("EnergyMeter.onReceiveLoopFailed()");
    
    logger.error("receive loop failed:", message.exception());

    throw new RuntimeException("EnergyMeter input failed: " + message.exception.getMessage(), message.exception());
  }
  
  protected Behavior<Command> onNotifyEnergyMeterPacket(NotifyEnergyMeterPacket message) {
    logger.trace("EnergyMeter.onNotifyEnergyMeterPacket()");
    
    logger.debug("received energy meter packet: {}", message.packet());
    
    if (checkSmaDevice(message.packet())) {
      notifyPowerData(message.packet());
      
      notifyEnergyData(message.packet());
    }
    
    return Behaviors.same();
  }
  
  private boolean checkSmaDevice(@NotNull Telegram packet) {
    logger.trace("EnergyMeter.checkSmaDevice()");
    
    if (packet.susyId() == susyId && packet.serialNumber() == serialNumber) {
      return true;
    } else {
      if (susyId == 0 || serialNumber == 0) {
        String device = null;
        if (packet.susyId() == 372  || packet.susyId() == 501) {
          device = "Sunny Home Manager 2.0";
        } else if (packet.susyId() == 349) {
          device = "SMA Energy Meter 2.0";
        } else if (packet.susyId() == 270) {
          device = "SMA Energy Meter 1.0";
        }     
        
        if (device != null) {
          logger.info("auto detected {} with serial number {}", device, packet.serialNumber());
          susyId = packet.susyId();
          serialNumber = packet.serialNumber();
          return true;
        }
      }
    }
    
    return false;
  }

  /**
   * Try to notify the power data from the SMA telegram to the output device.
   * @param packet SMA telegram to handle
   */
  private void notifyPowerData(@NotNull Telegram packet) {
    logger.trace("EnergyMeter.notifyPowerData()");

    OutputDevice.PowerData phase1PowerData = getPhase1PowerData(packet);
    OutputDevice.PowerData phase2PowerData = getPhase2PowerData(packet);
    OutputDevice.PowerData phase3PowerData = getPhase3PowerData(packet);

    if (phase1PowerData != null && phase2PowerData != null && phase3PowerData != null) {
      getOutputDevice().tell(new OutputDevice.NotifyPhasesPowerData(
            getNextMessageId(), 
            phase1PowerData,
            phase2PowerData,
            phase3PowerData,
            getOutputDeviceAckAdapter()));
    } else {
      OutputDevice.PowerData combinedPowerData = getCombinedPowerData(packet);
      if (combinedPowerData != null) {
        getOutputDevice().tell(new OutputDevice.NotifyTotalPowerData(getNextMessageId(), combinedPowerData, getOutputDeviceAckAdapter()));
      } else {
        logger.debug("dropping packet without valid power data");
      }
    }
  }

  /**
   * Try to notify the energy data from the SMA telegram to the output device.
   * @param packet SMA telegram to handle
   */
  private void notifyEnergyData(@NotNull Telegram packet) {
    logger.trace("EnergyMeter.notifyEnergyData()");
    
    OutputDevice.EnergyData phase1EnergyData = getPhase1EnergyData(packet);
    OutputDevice.EnergyData phase2energyData = getPhase2EnergyData(packet);
    OutputDevice.EnergyData phase3EnergyData = getPhase3EnergyData(packet);
    if (phase1EnergyData != null && phase2energyData != null && phase3EnergyData != null) {
      getOutputDevice().tell(new OutputDevice.NotifyPhaseEnergyData(getNextMessageId(), 0, phase1EnergyData, getOutputDeviceAckAdapter()));
      getOutputDevice().tell(new OutputDevice.NotifyPhaseEnergyData(getNextMessageId(), 1, phase2energyData, getOutputDeviceAckAdapter()));
      getOutputDevice().tell(new OutputDevice.NotifyPhaseEnergyData(getNextMessageId(), 2, phase3EnergyData, getOutputDeviceAckAdapter()));
    } else {
      OutputDevice.EnergyData combinedEnergyData = getCombinedEnergyData(packet);
      if (combinedEnergyData != null) {
        getOutputDevice().tell(new OutputDevice.NotifyTotalEnergyData(getNextMessageId(), combinedEnergyData, getOutputDeviceAckAdapter()));
      } else {
        logger.debug("dropping packet without valid energy data");
      }
    }
    
  }

  private @Nullable OutputDevice.PowerData getPhase1PowerData(@NotNull Telegram packet) {
    Double phasePowerPlus = packet.getActivePowerPhase1Plus();
    Double phasePowerMinus = packet.getActivePowerPhase1Minus();
    Double phaseApparentPowerPlus = packet.getApparentPowerPhase1Plus();
    Double phaseApparentPowerMinus = packet.getApparentPowerPhase1Minus();
    Double phaseCurrent = packet.getCurrentPhase1();
    Double phaseVoltage = packet.getVoltagePhase1();
    
    if (phasePowerPlus != null && phasePowerMinus != null  && phaseApparentPowerPlus != null 
          && phaseApparentPowerMinus != null && phaseCurrent != null) {
      return createPowerData(
            phasePowerPlus, 
            phasePowerMinus, 
            phaseApparentPowerPlus, 
            phaseApparentPowerMinus, 
            phaseCurrent,
            phaseVoltage != null ? phaseVoltage : getDefaultVoltage());
    }
    
    return null;
  }
  
  private @Nullable OutputDevice.PowerData getPhase2PowerData(@NotNull Telegram packet) {
    Double phasePowerPlus = packet.getActivePowerPhase2Plus();
    Double phasePowerMinus = packet.getActivePowerPhase2Minus();
    Double phaseApparentPowerPlus = packet.getApparentPowerPhase2Plus();
    Double phaseApparentPowerMinus = packet.getApparentPowerPhase2Minus();
    Double phaseCurrent = packet.getCurrentPhase2();
    Double phaseVoltage = packet.getVoltagePhase2();

    if (phasePowerPlus != null && phasePowerMinus != null
          && phaseApparentPowerPlus != null && phaseApparentPowerMinus != null && phaseCurrent != null) {
      return createPowerData(
            phasePowerPlus,
            phasePowerMinus,
            phaseApparentPowerPlus,
            phaseApparentPowerMinus,
            phaseCurrent,
            phaseVoltage != null ? phaseVoltage : getDefaultVoltage());
    }

    return null;
  }

  private @Nullable OutputDevice.PowerData getPhase3PowerData(@NotNull Telegram packet) {
    Double phasePowerPlus = packet.getActivePowerPhase3Plus();
    Double phasePowerMinus = packet.getActivePowerPhase3Minus();
    Double phaseApparentPowerPlus = packet.getApparentPowerPhase3Plus();
    Double phaseApparentPowerMinus = packet.getApparentPowerPhase3Minus();
    Double phaseCurrent = packet.getCurrentPhase3();
    Double phaseVoltage = packet.getVoltagePhase3();

    if (phasePowerPlus != null && phasePowerMinus != null
          && phaseApparentPowerPlus != null && phaseApparentPowerMinus != null && phaseCurrent != null) {
      return createPowerData(
            phasePowerPlus,
            phasePowerMinus,
            phaseApparentPowerPlus,
            phaseApparentPowerMinus,
            phaseCurrent,
            phaseVoltage != null ? phaseVoltage : getDefaultVoltage());
    }

    return null;
  }

  private @NotNull OutputDevice.PowerData createPowerData(double phasePowerPlus,
                                                          double phasePowerMinus,
                                                          double phaseApparentPowerPlus,
                                                          double phaseApparentPowerMinus,
                                                          double phaseCurrent,
                                                          double phaseVoltage) {
    double power = phasePowerPlus - phasePowerMinus;
    double apparentPower = phaseApparentPowerPlus - phaseApparentPowerMinus;
    if (apparentPower == 0.0) {
      apparentPower = power; // avoid division by zero
    }

    return new OutputDevice.PowerData(
          power,
          apparentPower,
          power / apparentPower,
          phaseCurrent,
          phaseVoltage,
          getDefaultFrequency());
  }


  private @Nullable OutputDevice.PowerData getCombinedPowerData(@NotNull Telegram packet) {
    Double powerPlus = packet.getActivePowerTotalPlus();
    Double powerMinus = packet.getActivePowerTotalMinus();
    Double apparentPowerPlus = packet.getApparentPowerTotalPlus();
    Double apparentPowerMinus = packet.getApparentPowerTotalMinus();
    
    if (powerPlus != null && powerMinus != null && apparentPowerPlus != null && apparentPowerMinus != null) {
      double power = powerPlus - powerMinus;
      double apparentPower = apparentPowerPlus - apparentPowerMinus;
      
      return new OutputDevice.PowerData(
            power,
            apparentPower,
            apparentPower != 0.0 ? power / apparentPower : 1.0,
            power / getDefaultVoltage(),
            getDefaultVoltage(),
            getDefaultFrequency());
    }
    
    return null;
  }

  private @Nullable OutputDevice.EnergyData getPhase1EnergyData(@NotNull Telegram packet) {
    Double consumption = packet.getConsumptionPhase1();
    Double production = packet.getProductionPhase1();
    
    if (consumption != null && production != null) {
      return new OutputDevice.EnergyData(consumption, production);
    }
    
    return null;
  }

  private @Nullable OutputDevice.EnergyData getPhase2EnergyData(@NotNull Telegram packet) {
    Double consumption = packet.getConsumptionPhase2();
    Double production = packet.getProductionPhase2();

    if (consumption != null && production != null) {
      return new OutputDevice.EnergyData(consumption, production);
    }

    return null;
  }

  private @Nullable OutputDevice.EnergyData getPhase3EnergyData(@NotNull Telegram packet) {
    Double consumption = packet.getConsumptionPhase3();
    Double production = packet.getProductionPhase3();

    if (consumption != null && production != null) {
      return new OutputDevice.EnergyData(consumption, production);
    }

    return null;
  }

  private @Nullable OutputDevice.EnergyData getCombinedEnergyData(@NotNull Telegram packet) {
    Double consumption = packet.getConsumptionTotal();
    Double production = packet.getProductionTotal();

    if (consumption != null && production != null) {
      return new OutputDevice.EnergyData(consumption, production);
    }

    return null;
  }

  private void startReceiveLoop() {
    executor.execute(() -> {
      try (MulticastSocket socket = new MulticastSocket(this.port)) {
        socket.setSoTimeout((int) Math.min(socketTimeout.toMillis(), Integer.MAX_VALUE));

        InetSocketAddress multiCastAddress = new InetSocketAddress(InetAddress.getByName(this.group), this.port);

        boolean joined = false;

        for (String networkInterfaceName : this.networkInterface) {
          NetworkInterface networkInterface = lookupNetworkInterface(networkInterfaceName);
          if (networkInterface == null) {
            logger.warn("unknown network interface {}, will not join", networkInterfaceName);
          } else {
            logger.info("joining multicast group {} on network interface {}",
                    multiCastAddress, networkInterfaceName);

            socket.joinGroup(multiCastAddress, networkInterface);
            joined = true;
          }
        }

        if (!joined) {
          throw new IllegalArgumentException("could not join any multicast groups");
        }
        
        getContext().getSelf().tell(ReceiveLoopStarted.INSTANCE);

        DatagramPacket datagramPacket = new DatagramPacket(new byte[1024], 1024);
        while (doReceive.get()) {
          try {
            socket.receive(datagramPacket);
            
            try  {
              if (logger.isDebugEnabled()) {
                logger.debug("received packet {}", Hex.format(ByteBuffer.wrap(datagramPacket.getData(), 0, datagramPacket.getLength())));
              }
              Telegram energyMeterPacket = ProtocolParser.parse(datagramPacket.getData(), datagramPacket.getLength());
              
              getContext().getSelf().tell(new NotifyEnergyMeterPacket(energyMeterPacket));
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
  
  private static @Nullable NetworkInterface lookupNetworkInterface(@NotNull String name) throws SocketException {
    NetworkInterface networkInterface = NetworkInterface.getByName(name);
    if (networkInterface == null) {
      try {
        networkInterface = NetworkInterface.getByInetAddress(InetAddress.getByName(name));
      } catch (UnknownHostException ignore) {
      }
    }
    
    return networkInterface;
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
  
  public record NotifyEnergyMeterPacket(
        @NotNull Telegram packet
  ) implements Command {}
}
