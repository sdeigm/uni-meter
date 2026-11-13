package com.deigmueller.uni_meter.application;

import com.deigmueller.uni_meter.input.InputDevice;
import com.deigmueller.uni_meter.input.InputDeviceFactory;
import com.deigmueller.uni_meter.mdns.MDnsRegistrator;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.deigmueller.uni_meter.output.device.eco_tracker.EcoTracker;
import com.deigmueller.uni_meter.output.device.shelly.ShellyPro3EM;
import com.deigmueller.uni_meter.output.device.sma_em.SmaEM;
import com.typesafe.config.Config;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.SupervisorStrategy;
import org.apache.pekko.actor.typed.Terminated;
import org.apache.pekko.actor.typed.javadsl.*;
import org.apache.pekko.http.javadsl.server.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;

public class UniMeter extends AbstractBehavior<UniMeter.Command> {
  // Class members
  private static final String COMMON_CONFIG = "uni-meter.output-devices.common";
  
  // Instance members
  private final Logger logger = LoggerFactory.getLogger("uni-meter.controller");
  private final ActorRef<HttpServerController.Command> httpServerController;

  public static Behavior<Command> create() {
    return Behaviors.setup(UniMeter::new);
  }
  
  private UniMeter(@NotNull ActorContext<Command> context) {
    super(context);
    
    try {
      httpServerController = createHttpServerController();
      getContext().watch(httpServerController);
      
      final ActorRef<MDnsRegistrator.Command> mDnsRegistrator = createMDnsRegistrator();

      ActorRef<OutputDevice.Command> output = createOutput(mDnsRegistrator);
      getContext().watch(output);

      ActorRef<InputDevice.Command> input = createInput(output);
      getContext().watch(input);       
      
      httpServerController.tell(
            new HttpServerController.RegisterHttpRoute(
                  getContext().getSystem().settings().config().getString("uni-meter.http-server.interface"), 
                  getContext().getSystem().settings().config().getInt("uni-meter.http-server.port"), 
                  new UniMeterHttpRoute(context.getSystem(), output).createRoute()));
    } catch (Exception e) {
      logger.error("failed to initialize the main controller", e);
      throw e;
    }
  }
  
  @Override
  public ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onSignal(Terminated.class, this::onTerminated)
          .onMessage(RegisterHttpRoute.class, this::onRegisterHttpRoute);
  }

  @Override
  public Receive<Command> createReceive() {
    return newReceiveBuilder()
          .build();
  }
  
  protected @NotNull Behavior<Command> onTerminated(@NotNull Terminated signal) {
    logger.error("UniMeter.onTerminated()");
    return Behaviors.stopped();
  }
  
  private @NotNull Behavior<Command> onRegisterHttpRoute(@NotNull RegisterHttpRoute message) {
    logger.trace("UniMeter.onRegisterHttpRoute()");
    
    httpServerController.tell(
          new HttpServerController.RegisterHttpRoute(
                message.bindInterface(),
                message.bindPort(), 
                message.route()));
    
    return Behaviors.same();
  }

  /**
   * Create the HTTP server controller
   */
  private @NotNull ActorRef<HttpServerController.Command> createHttpServerController() {
    logger.trace("UniMeter.createHttpServerController()");
    return getContext().spawn(HttpServerController.create(), "http-server-controller");
  }

  /**
   * Create the mDNS registrator
   */
  private @NotNull ActorRef<MDnsRegistrator.Command> createMDnsRegistrator() {
    logger.trace("UniMeter.createMDnsRegistrator()");
    return getContext().spawn(MDnsRegistrator.create(), "mdns-registrator");
  }

  /**
   * Create the output device actor
   * @return Reference to the Shelly actor
   */
  private @NotNull ActorRef<OutputDevice.Command> createOutput(@NotNull ActorRef<MDnsRegistrator.Command> mDnsRegistrator) {
    logger.trace("UniMeter.createOutput()");
    
    String outputDeviceConfigPath = getContext().getSystem().settings().config().getString("uni-meter.output");
    
    String outputDeviceType = getContext().getSystem().settings().config().getString(outputDeviceConfigPath + ".type");
    
    final SupervisorStrategy failureStrategy = SupervisorStrategy.restartWithBackoff(
          getContext().getSystem().settings().config().getDuration("uni-meter.output-supervision.min-backoff"),
          getContext().getSystem().settings().config().getDuration("uni-meter.output-supervision.max-backoff"),
          getContext().getSystem().settings().config().getDouble("uni-meter.output-supervision.jitter"));
    
    final Config config = getContext().getSystem().settings().config()
          .getConfig(outputDeviceConfigPath)
          .withFallback(getContext().getSystem().settings().config().getConfig(COMMON_CONFIG));

    switch (outputDeviceType) {
      case ShellyPro3EM.TYPE -> {
        logger.info("creating ShellyPro3EM output device");
        return getContext().spawn(
              Behaviors.supervise(
                    ShellyPro3EM.create(
                          getContext().getSelf(),
                          mDnsRegistrator,
                          config)
              ).onFailure(failureStrategy),
              "output");
      }
      case EcoTracker.TYPE -> {
        logger.info("creating EcoTracker output device");
        return getContext().spawn(
              Behaviors.supervise(
                    EcoTracker.create(
                          getContext().getSelf(),
                          mDnsRegistrator,
                          config)
              ).onFailure(failureStrategy),
              "output");
      }
      case SmaEM.TYPE -> {
        logger.info("creating SmaEM output device");
        return getContext().spawn(
              Behaviors.supervise(
                    SmaEM.create(
                          getContext().getSelf(),
                          mDnsRegistrator,
                          config)
              ).onFailure(failureStrategy),
              "output");
      }
      default -> {
        logger.error("unknown output device type: {}", outputDeviceType);
        throw new IllegalArgumentException("unknown output device type: " + outputDeviceType);
      }
    }
  }
  
  /**
   * Create the input device actor
   * @param output Reference to the output actor
   * @return Reference to the device actor
   */
  private @NotNull ActorRef<InputDevice.Command> createInput(@NotNull ActorRef<OutputDevice.Command> output) {
    logger.trace("UniMeter.createInput()");

    Config rootConfig = getContext().getSystem().settings().config();
    String inputDeviceConfigPath = rootConfig.getString("uni-meter.input");

    logger.info("creating {} input device", InputDeviceFactory.getType(rootConfig, inputDeviceConfigPath));
    try {
      return getContext().spawn(
            InputDeviceFactory.createSupervised(output, rootConfig, inputDeviceConfigPath),
            "input");
    } catch (IllegalArgumentException e) {
      logger.error("failed to create input device: {}", e.getMessage());
      throw e;
    }
  }
  
  public interface Command {}
  
  public record RegisterHttpRoute(
        @NotNull String bindInterface,
        int bindPort,
        @NotNull Route route
  ) implements Command {}
}
