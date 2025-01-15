package com.deigmueller.uni_meter.application;

import com.deigmueller.uni_meter.input.InputDevice;
import com.deigmueller.uni_meter.input.device.modbus.sdm120.Sdm120;
import com.deigmueller.uni_meter.input.device.shelly._3em.Shelly3EM;
import com.deigmueller.uni_meter.input.device.shrdzm.ShrDzm;
import com.deigmueller.uni_meter.input.device.tibber.pulse.Pulse;
import com.deigmueller.uni_meter.input.device.sma.energy_meter.EnergyMeter;
import com.deigmueller.uni_meter.input.device.vzlogger.VzLogger;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.deigmueller.uni_meter.output.device.shelly.ShellyPro3EM;
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

        ActorRef<OutputDevice.Command> output = createOutput();
      getContext().watch(output);

        ActorRef<InputDevice.Command> input = createInput(output);
      getContext().watch(input);       
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
    
    httpServerController.tell(new HttpServerController.RegisterHttpRoute(message.port(), message.route()));
    
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
   * Create the output device actor
   * @return Reference to the Shelly actor
   */
  private @NotNull ActorRef<OutputDevice.Command> createOutput() {
    logger.trace("UniMeter.createOutput()");
    
    String outputDeviceConfigPath = getContext().getSystem().settings().config().getString("uni-meter.output");
    
    String outputDeviceType = getContext().getSystem().settings().config().getString(outputDeviceConfigPath + ".device.type");
    
    if (outputDeviceType.equals("ShellyPro3EM")) {
      logger.info("creating Shelly3EM output device");
      return getContext().spawn(
            Behaviors.supervise(
              ShellyPro3EM.create(
                    getContext().getSelf(), 
                    getContext().getSystem().settings().config().getConfig(outputDeviceConfigPath))
            ).onFailure(SupervisorStrategy.restartWithBackoff(
                  getContext().getSystem().settings().config().getDuration("uni-meter.output-supervision.min-backoff"),
                  getContext().getSystem().settings().config().getDuration("uni-meter.output-supervision.max-backoff"),
                  getContext().getSystem().settings().config().getDouble("uni-meter.output-supervision.jitter")
            )), 
            "output");
    } else {
      logger.error("unknown output device type: {}", outputDeviceType);
      throw new IllegalArgumentException("unknown output device type: " + outputDeviceType);
    }
  }
  
  /**
   * Create the input device actor
   * @param output Reference to the output actor
   * @return Reference to the device actor
   */
  private @NotNull ActorRef<InputDevice.Command> createInput(@NotNull ActorRef<OutputDevice.Command> output) {
    logger.trace("UniMeter.createInput()");

    String inputDeviceConfigPath = getContext().getSystem().settings().config().getString("uni-meter.input");
    
    String inputDeviceType = getContext().getSystem().settings().config().getString(inputDeviceConfigPath + ".type");

    Duration minBackoff = getContext().getSystem().settings().config().getDuration("uni-meter.input-supervision.min-backoff");
    Duration maxBackoff = getContext().getSystem().settings().config().getDuration("uni-meter.input-supervision.max-backoff");
    double jitter = getContext().getSystem().settings().config().getDouble("uni-meter.input-supervision.jitter");

    logger.info("creating {} input device", inputDeviceType);
      switch (inputDeviceType) {
          case VzLogger.TYPE -> {
              return getContext().spawn(
                      Behaviors.supervise(
                              VzLogger.create(
                                      output,
                                      getContext().getSystem().settings().config().getConfig(inputDeviceConfigPath))
                      ).onFailure(SupervisorStrategy.restartWithBackoff(minBackoff, maxBackoff, jitter)),
                      "input");
          }
          case EnergyMeter.TYPE -> {
              return getContext().spawn(
                      Behaviors.supervise(
                              EnergyMeter.create(
                                      output,
                                      getContext().getSystem().settings().config().getConfig(inputDeviceConfigPath))
                      ).onFailure(SupervisorStrategy.restartWithBackoff(minBackoff, maxBackoff, jitter)),
                      "input");
          }
          case com.deigmueller.uni_meter.input.device.shelly.pro3em.ShellyPro3EM.TYPE -> {
              return getContext().spawn(
                      Behaviors.supervise(
                              com.deigmueller.uni_meter.input.device.shelly.pro3em.ShellyPro3EM.create(
                                      output,
                                      getContext().getSystem().settings().config().getConfig(inputDeviceConfigPath))
                      ).onFailure(SupervisorStrategy.restartWithBackoff(minBackoff, maxBackoff, jitter)),
                      "input");
          }
          
          case Shelly3EM.TYPE -> {
              return getContext().spawn(
                      Behaviors.supervise(
                              Shelly3EM.create(
                                      output,
                                      getContext().getSystem().settings().config().getConfig(inputDeviceConfigPath))
                      ).onFailure(SupervisorStrategy.restartWithBackoff(minBackoff, maxBackoff, jitter)),
                      "input");
          }
          
          case Sdm120.TYPE -> {
              return getContext().spawn(
                      Behaviors.supervise(
                              Sdm120.create(
                                      output,
                                      getContext().getSystem().settings().config().getConfig(inputDeviceConfigPath))
                      ).onFailure(SupervisorStrategy.restartWithBackoff(minBackoff, maxBackoff, jitter)),
                      "input");
          }
          
          case ShrDzm.TYPE -> {
              return getContext().spawn(
                      Behaviors.supervise(
                              ShrDzm.create(
                                      output,
                                      getContext().getSystem().settings().config().getConfig(inputDeviceConfigPath))
                      ).onFailure(SupervisorStrategy.restartWithBackoff(minBackoff, maxBackoff, jitter)),
                      "input");
          }

          case Pulse.TYPE -> {
            return getContext().spawn(
                      Behaviors.supervise(
                              Pulse.create(
                                      output,
                                      getContext().getSystem().settings().config().getConfig(inputDeviceConfigPath))
                      ).onFailure(SupervisorStrategy.restartWithBackoff(minBackoff, maxBackoff, jitter)),
                      "input");
          }
          
          default -> {
              logger.error("unknown input device type: {}", inputDeviceType);
              throw new IllegalArgumentException("unknown input device type: " + inputDeviceType);
          }
      }
  }
  
  public interface Command {}
  
  public record RegisterHttpRoute(
        int port,
        @NotNull Route route
  ) implements Command {}
}
