package com.deigmueller.uni_meter.input.device.failover;

import com.deigmueller.uni_meter.input.InputDevice;
import com.deigmueller.uni_meter.input.InputDeviceFactory;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.typesafe.config.Config;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.apache.pekko.actor.typed.javadsl.TimerScheduler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Input device which combines a primary and a secondary input device. The readings of the primary input device are
 * forwarded to the output device as long as the primary input device is healthy. The primary input device is
 * considered unhealthy, if it did not provide any power readings within the configured {@code timeout} or if
 * the power readings did not change within the configured {@code frozen-timeout}. In that case the readings of the
 * secondary input device are forwarded instead. As soon as the primary input device has been healthy again for the
 * configured {@code switch-back-delay}, the uni-meter switches back to the primary input device.
 */
public class Failover extends InputDevice {
  // Class members
  public static final String TYPE = "Failover";

  // Instance members
  private final String primaryPath;
  private final String secondaryPath;
  private final Duration timeout;
  private final Duration frozenTimeout;
  private final Duration switchBackDelay;
  private final EnergySource energySource;
  private final SourceState primary;
  private final SourceState secondary;

  private Source active = Source.PRIMARY;
  private Instant primaryHealthySince;

  public static Behavior<Command> create(@NotNull ActorRef<OutputDevice.Command> outputDevice,
                                         @NotNull Config config) {
    return Behaviors.setup(context -> Behaviors.withTimers(timers -> new Failover(context, timers, outputDevice, config)));
  }

  protected Failover(@NotNull ActorContext<Command> context,
                     @NotNull TimerScheduler<Command> timers,
                     @NotNull ActorRef<OutputDevice.Command> outputDevice,
                     @NotNull Config config) {
    super(context, outputDevice, config);

    Config rootConfig = context.getSystem().settings().config();

    primaryPath = config.getString("primary");
    secondaryPath = config.getString("secondary");
    timeout = config.getDuration("timeout");
    frozenTimeout = config.getDuration("frozen-timeout");
    switchBackDelay = config.getDuration("switch-back-delay");
    energySource = EnergySource.fromConfig(config.getString("energy-source"));

    for (String path : List.of(primaryPath, secondaryPath)) {
      if (TYPE.equals(InputDeviceFactory.getType(rootConfig, path))) {
        throw new IllegalArgumentException("nested failover input devices are not supported: " + path);
      }
    }

    // The input devices start in a healthy state, so that the primary device has the chance to deliver its first
    // readings before the failover is triggered.
    Instant now = Instant.now();
    primary = new SourceState(now);
    secondary = new SourceState(now);
    primaryHealthySince = now;

    logger.info("failover input: primary = {} ({}), secondary = {} ({})",
          primaryPath, InputDeviceFactory.getType(rootConfig, primaryPath),
          secondaryPath, InputDeviceFactory.getType(rootConfig, secondaryPath));

    spawnSource(Source.PRIMARY, rootConfig, primaryPath);
    spawnSource(Source.SECONDARY, rootConfig, secondaryPath);

    timers.startTimerWithFixedDelay(CheckHealth.INSTANCE, config.getDuration("check-interval"));
  }

  @Override
  public ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onMessage(SourceMessage.class, this::onSourceMessage)
          .onMessage(CheckHealth.class, this::onCheckHealth);
  }

  /**
   * Handle a message one of the source input devices sent to its (proxied) output device
   */
  private @NotNull Behavior<Command> onSourceMessage(@NotNull SourceMessage message) {
    logger.trace("Failover.onSourceMessage()");

    Instant now = Instant.now();
    OutputDevice.Command command = message.command();
    SourceState state = message.source() == Source.PRIMARY ? primary : secondary;

    String powerKey = powerKey(command);
    if (powerKey != null) {
      state.updatePower(powerKey, powerSignature(command), command, now);

      evaluate(now);

      if (message.source() == active) {
        getOutputDevice().tell(command);
      }
    } else if (isEnergyCommand(command)) {
      state.lastEnergyCommand = command;

      if (energySource.matches(message.source(), active)) {
        getOutputDevice().tell(command);
      }
    } else {
      // Should not happen as input devices only send notifications, but forward anything else unchanged
      getOutputDevice().tell(command);
    }

    return Behaviors.same();
  }

  /**
   * Periodically check the health of the primary input device
   */
  private @NotNull Behavior<Command> onCheckHealth(@NotNull CheckHealth message) {
    logger.trace("Failover.onCheckHealth()");

    evaluate(Instant.now());

    return Behaviors.same();
  }

  /**
   * Evaluate the health of the primary input device and switch the active source if necessary
   */
  private void evaluate(@NotNull Instant now) {
    String problem = primaryProblem(now);

    if (problem != null) {
      primaryHealthySince = null;
      if (active == Source.PRIMARY) {
        logger.warn("primary input device {} is unhealthy ({}), switching to secondary input device {}",
              primaryPath, problem, secondaryPath);
        switchTo(Source.SECONDARY, now);
      }
    } else {
      if (primaryHealthySince == null) {
        primaryHealthySince = now;
        if (active == Source.SECONDARY) {
          logger.info("primary input device {} delivers valid readings again, switching back in {}s",
                primaryPath, switchBackDelay.toSeconds());
        }
      }
      if (active == Source.SECONDARY && !now.isBefore(primaryHealthySince.plus(switchBackDelay))) {
        logger.info("switching back to primary input device {}", primaryPath);
        switchTo(Source.PRIMARY, now);
      }
    }
  }

  /**
   * Check whether the primary input device is unhealthy
   * @return Description of the problem or null if the primary input device is healthy
   */
  private @Nullable String primaryProblem(@NotNull Instant now) {
    Duration sinceUpdate = Duration.between(primary.lastPowerUpdate, now);
    if (sinceUpdate.compareTo(timeout) > 0) {
      return "no power readings for " + sinceUpdate.toSeconds() + "s";
    }

    if (!frozenTimeout.isZero()) {
      Duration sinceChange = Duration.between(primary.lastPowerChange, now);
      if (sinceChange.compareTo(frozenTimeout) > 0) {
        return "power readings unchanged for " + sinceChange.toSeconds() + "s";
      }
    }

    return null;
  }

  /**
   * Switch the active source and immediately forward its latest readings, if they are recent enough
   */
  private void switchTo(@NotNull Source source, @NotNull Instant now) {
    active = source;
    SourceState state = source == Source.PRIMARY ? primary : secondary;

    if (Duration.between(state.lastPowerUpdate, now).compareTo(timeout) <= 0) {
      for (OutputDevice.Command command : state.lastPowerCommands.values()) {
        getOutputDevice().tell(command);
      }
    }

    if (energySource == EnergySource.ACTIVE && state.lastEnergyCommand != null) {
      getOutputDevice().tell(state.lastEnergyCommand);
    }
  }

  private void spawnSource(@NotNull Source source, @NotNull Config rootConfig, @NotNull String path) {
    ActorRef<Command> self = getContext().getSelf();

    ActorRef<OutputDevice.Command> proxy = getContext().spawn(
          Behaviors.receive(OutputDevice.Command.class)
                .onAnyMessage(command -> {
                  self.tell(new SourceMessage(source, command));
                  return Behaviors.same();
                })
                .build(),
          source.name().toLowerCase() + "-proxy");

    getContext().spawn(
          InputDeviceFactory.createSupervised(proxy, rootConfig, path),
          source.name().toLowerCase());
  }

  private static @Nullable String powerKey(@NotNull OutputDevice.Command command) {
    if (command instanceof OutputDevice.NotifyPhasePowerData phase) {
      return "phase-" + phase.phaseId();
    } else if (command instanceof OutputDevice.NotifyPhasesPowerData) {
      return "phases";
    } else if (command instanceof OutputDevice.NotifyTotalPowerData) {
      return "total";
    }
    return null;
  }

  private static @NotNull List<Double> powerSignature(@NotNull OutputDevice.Command command) {
    if (command instanceof OutputDevice.NotifyPhasePowerData phase) {
      return List.of(phase.data().power());
    } else if (command instanceof OutputDevice.NotifyPhasesPowerData phases) {
      return List.of(phases.phase1().power(), phases.phase2().power(), phases.phase3().power());
    } else if (command instanceof OutputDevice.NotifyTotalPowerData total) {
      return List.of(total.data().power());
    }
    return List.of();
  }

  private static boolean isEnergyCommand(@NotNull OutputDevice.Command command) {
    return command instanceof OutputDevice.NotifyPhaseEnergyData
          || command instanceof OutputDevice.NotifyPhasesEnergyData
          || command instanceof OutputDevice.NotifyTotalEnergyData;
  }

  private enum Source {
    PRIMARY,
    SECONDARY
  }

  private enum EnergySource {
    ACTIVE,
    PRIMARY,
    SECONDARY;

    static EnergySource fromConfig(@NotNull String value) {
      return switch (value.toLowerCase()) {
        case "active" -> ACTIVE;
        case "primary" -> PRIMARY;
        case "secondary" -> SECONDARY;
        default -> throw new IllegalArgumentException("unknown energy-source: " + value);
      };
    }

    boolean matches(@NotNull Source source, @NotNull Source active) {
      return switch (this) {
        case ACTIVE -> source == active;
        case PRIMARY -> source == Source.PRIMARY;
        case SECONDARY -> source == Source.SECONDARY;
      };
    }
  }

  private static class SourceState {
    private final Map<String, List<Double>> lastPowerSignatures = new HashMap<>();
    private final Map<String, OutputDevice.Command> lastPowerCommands = new HashMap<>();
    private Instant lastPowerUpdate;
    private Instant lastPowerChange;
    private OutputDevice.Command lastEnergyCommand;

    SourceState(@NotNull Instant now) {
      lastPowerUpdate = now;
      lastPowerChange = now;
    }

    void updatePower(@NotNull String key,
                     @NotNull List<Double> signature,
                     @NotNull OutputDevice.Command command,
                     @NotNull Instant now) {
      lastPowerUpdate = now;
      lastPowerCommands.put(key, command);

      List<Double> previous = lastPowerSignatures.put(key, signature);
      if (!Objects.equals(previous, signature)) {
        lastPowerChange = now;
      }
    }
  }

  private record SourceMessage(
        @NotNull Source source,
        @NotNull OutputDevice.Command command
  ) implements Command {}

  private enum CheckHealth implements Command {
    INSTANCE
  }
}
