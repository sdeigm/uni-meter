package com.deigmueller.uni_meter.input.device.tasmota;

import com.deigmueller.uni_meter.common.utils.Json;
import com.deigmueller.uni_meter.input.device.common.http.HttpInputDevice;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.typesafe.config.Config;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.apache.pekko.http.javadsl.model.HttpEntity;
import org.apache.pekko.http.javadsl.model.HttpRequest;
import org.apache.pekko.http.scaladsl.model.headers.HttpCredentials;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;

/**
 * Tasmota input device.
 */
public class Tasmota extends HttpInputDevice {
  // Class members
  public static final String TYPE = "Tasmota";

  // Instance members
  private final PhaseMode powerPhaseMode = getPhaseMode("power-phase-mode");
  private final String powerPhase = getConfig().getString("power-phase");
  private final PhaseMode energyPhaseMode = getPhaseMode("energy-phase-mode");
  private final String energyPhase = getConfig().getString("energy-phase");
  private final Duration statusPollingInterval = getConfig().getDuration("polling-interval");
  private final String powerJsonPath = getConfig().getString("power-json-path");
  private final double powerScale = getConfig().getDouble("power-scale");
  private final String energyConsumptionJsonPath = getConfig().getString("energy-consumption-json-path");
  private final double energyConsumptionScale = getConfig().getDouble("energy-consumption-scale");
  private final String energyProductionJsonPath = getConfig().getString("energy-production-json-path");
  private final double energyProductionScale = getConfig().getDouble("energy-production-scale");
  private final HttpCredentials credentials;    
  
  /**
   * Static factory method to create a new Shelly3EM actor.
   * @param outputDevice The output device actor reference
   * @param config The configuration
   * @return Behavior of the created actor
   */
  public static Behavior<Command> create(@NotNull ActorRef<OutputDevice.Command> outputDevice,
                                         @NotNull Config config) {
    return Behaviors.setup(context -> new Tasmota(context, outputDevice, config));
  }

  /**
   * Protected constructor called by the static factory method.
   * @param context The actor context
   * @param outputDevice The output device actor reference
   * @param config The configuration
   */
  protected Tasmota(@NotNull ActorContext<Command> context,
                    @NotNull ActorRef<OutputDevice.Command> outputDevice,
                    @NotNull Config config) {
    super(context, outputDevice, config);
    
    credentials = !getConfig().getString("username").isEmpty() || !getConfig().getString("password").isEmpty() 
          ? HttpCredentials.createBasicHttpCredentials(getConfig().getString("username"), getConfig().getString("password"))
          : null;
    
    executeStatusPolling();
  }

  /**
   * Create the actor's ReceiveBuilder.
   * @return The actor's ReceiveBuilder
   */
  @Override
  public ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onMessage(StatusRequestFailed.class, this::onStatusRequestFailed)
          .onMessage(StatusRequestSuccess.class, this::onStatusRequestSuccess)
          .onMessage(ExecuteNextStatusPolling.class, this::onExecuteNextStatusPolling);
  }

  /**
   * Handle a StatusRequestFailed message.
   * @param message Message to handle
   * @return Same behavior
   */
  protected Behavior<Command> onStatusRequestFailed(@NotNull StatusRequestFailed message) {
    logger.trace("Tasmota.onStatusRequestFailed()");

    logger.error("failed to execute /status polling: {}", message.throwable().getMessage());

    startNextPollingTimer();

    return Behaviors.same();
  }

  /**
   * Handle a StatusRequestSuccess message.
   * @param message Message to handle
   * @return Same behavior
   */
  protected Behavior<Command> onStatusRequestSuccess(@NotNull StatusRequestSuccess message) {
    logger.trace("Tasmota.onStatusRequestSuccess()");

    try {
      logger.debug("status response: {}", message.entity().getData().utf8String());
      
      if (! powerJsonPath.isEmpty()) {
        Double power = Json.readDoubleValue(powerJsonPath, message.entity().getData().utf8String(), powerScale);
        if (power != null) {
          notifyPowerData(powerPhaseMode, powerPhase, power);
        } else {
          logger.debug("no power data available: {}", powerJsonPath);
        }
      }
      
      if (! energyConsumptionJsonPath.isEmpty() && ! energyProductionJsonPath.isEmpty()) {
        Double energyConsumption = Json.readDoubleValue(energyConsumptionJsonPath, message.entity().getData().utf8String(), energyConsumptionScale);
        Double energyProduction = Json.readDoubleValue(energyProductionJsonPath, message.entity().getData().utf8String(), energyProductionScale);
        if (energyConsumption != null && energyProduction != null) {
          notifyEnergyData(energyPhaseMode, energyPhase, energyConsumption, energyProduction);
        }
      }
    } catch (Exception e) {
      logger.error("Failed to parse status response: {}", e.getMessage());
    }

    startNextPollingTimer();

    return Behaviors.same();
  }

  /**
   * Handle the notification to execute the next status polling.
   * @param message Notification message
   * @return Same behavior
   */
  protected Behavior<Command> onExecuteNextStatusPolling(@NotNull ExecuteNextStatusPolling message) {
    logger.trace("Tasmota.onExecuteNextStatusPolling()");

    executeStatusPolling();

    return Behaviors.same();
  }

  
  private void executeStatusPolling() {
    logger.trace("Tasmota.executeStatusPolling()");

    HttpRequest httpRequest = HttpRequest.create(getUrl() + "/cm?cmnd=Status%2010");
    if (credentials != null) {
      httpRequest = httpRequest.addCredentials(credentials);
    }

    getHttp().singleRequest(httpRequest)
          .whenComplete((response, throwable) -> {
            if (throwable != null) {
              getContext().getSelf().tell(new StatusRequestFailed(throwable));
            } else {
              try {
                response.entity()
                      .toStrict(5000, getMaterializer())
                      .whenComplete((strictEntity, toStrictFailure) -> {
                        if (toStrictFailure != null) {
                          getContext().getSelf().tell(new StatusRequestFailed(toStrictFailure));
                        } else {
                          getContext().getSelf().tell(new StatusRequestSuccess(strictEntity));
                        }
                      });
              } catch (Exception e) {
                // Failed to get a strict entity
                getContext().getSelf().tell(new StatusRequestFailed(e));
              }
            }
          });
  }

  private void startNextPollingTimer() {
    logger.trace("Tasmota.startNextPollingTimer()");

    getContext().getSystem().scheduler().scheduleOnce(
          statusPollingInterval,
          () -> getContext().getSelf().tell(ExecuteNextStatusPolling.INSTANCE),
          getContext().getExecutionContext());
  }
  
  protected record StatusRequestFailed(
        @NotNull Throwable throwable
  ) implements Command {}

  protected record StatusRequestSuccess(
        @NotNull HttpEntity.Strict entity
  ) implements Command {}
  
  protected enum ExecuteNextStatusPolling implements Command {
    INSTANCE
  }
}
