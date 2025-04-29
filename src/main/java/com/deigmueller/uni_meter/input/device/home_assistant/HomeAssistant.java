package com.deigmueller.uni_meter.input.device.home_assistant;

import com.deigmueller.uni_meter.input.device.common.generic.GenericInputDevice;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import lombok.AccessLevel;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.apache.pekko.http.javadsl.Http;
import org.apache.pekko.http.javadsl.model.HttpEntity;
import org.apache.pekko.http.javadsl.model.HttpRequest;
import org.apache.pekko.http.javadsl.model.headers.HttpCredentials;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.*;

@Getter(AccessLevel.PROTECTED)
public class HomeAssistant extends GenericInputDevice {
  // Class members
  public static final String TYPE = "HomeAssistant";
  
  // Instance members
  private final Http http = Http.get(getContext().getSystem());
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final String url = getConfig().getString("url");
  private final Duration pollingInterval = getConfig().getDuration("polling-interval");
  private final Map<String,String> sensorChannelMap = new HashMap<>();
  private final HttpCredentials credentials;
  
  public static Behavior<Command> create(@NotNull ActorRef<OutputDevice.Command> outputDevice,
                                         @NotNull Config config) {
    return Behaviors.setup(context -> new HomeAssistant(context, outputDevice, config));
  }

  protected HomeAssistant(@NotNull ActorContext<Command> context, 
                          @NotNull ActorRef<OutputDevice.Command> outputDevice, 
                          @NotNull Config config) {
    super(context, outputDevice, config);
    
    credentials = HttpCredentials.createOAuth2BearerToken(getConfig().getString("access-token"));
    
    initChannels();

    readNextSensorValue(new HashSet<>(sensorChannelMap.keySet()));
  }

  /**
   * Create the actor's ReceiveBuilder.
   * @return The actor's ReceiveBuilder
   */
  @Override
  public ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onMessage(HttpRequestFailed.class, this::onHttpRequestFailed)
          .onMessage(HttpRequestSuccess.class, this::onHttpRequestSuccess)
          .onMessage(ExecuteNextPollingCycle.class, this::onExecuteNextPollingCycle);
  }
  
  /**
   * Handles the failure of an HTTP request within the system, logging the error and initiating 
   * the next polling timer for retrying the operation.
   * @param message An instance of {@code HttpRequestFailed} containing details about the 
   *                failed HTTP request, including the associated throwable that caused the failure. 
   *                Must not be null.
   * @return The same actor behavior, allowing the system to continue processing commands 
   *         as expected.
   */
  private Behavior<Command> onHttpRequestFailed(@NotNull HttpRequestFailed message) {
    logger.trace("HomeAssistant.onHttpRequestFailed()");

    logger.error("failed to execute http polling: {}", message.throwable().getMessage());

    startNextPollingTimer();

    return Behaviors.same();
  }
  
  private Behavior<Command> onHttpRequestSuccess(@NotNull HttpRequestSuccess message) {
    logger.trace("HomeAssistant.onHttpRequestSuccess()");

    try {
      String response = message.strictEntity().getData().utf8String();
      logger.debug("http response: {}", response);
      
      Entity entity = getObjectMapper().readValue(response, Entity.class);
      
      String channel = sensorChannelMap.get(message.sensor());
      
      setChannelData(channel, Double.parseDouble(entity.state()));
      
      if (message.remainingSensors().isEmpty()) {
        notifyOutputDevice();
        startNextPollingTimer();
      } else {
        readNextSensorValue(message.remainingSensors());
      }
    } catch (Exception e) {
      logger.error("failed to process http response: {}", e.getMessage());
      startNextPollingTimer();
    }

    return Behaviors.same();
  }
  
  private Behavior<Command> onExecuteNextPollingCycle(@NotNull ExecuteNextPollingCycle message) {
    readNextSensorValue(new HashSet<>(sensorChannelMap.keySet()));
    return Behaviors.same();
  }
  
  /**
   * Start the next polling timer.
   */
  private void startNextPollingTimer() {
    logger.trace("HomeAssistant.startNextPollingTimer()");

    getContext().getSystem().scheduler().scheduleOnce(
          pollingInterval,
          () -> getContext().getSelf().tell(ExecuteNextPollingCycle.INSTANCE),
          getContext().getExecutionContext());
  }

  /**
   * Reads the next sensor value from the list of remaining sensors.
   * Sends a corresponding message back to the actor for success or failure.
   * @param remainingSensors A collection of sensor identifiers that are yet to be read. Must not be empty.
   */
  private void readNextSensorValue(@NotNull Collection<String> remainingSensors) {
    logger.trace("HomeAssistant.readNextSensorValue()");
    
    if (remainingSensors.isEmpty()) {
      throw new IllegalStateException("remaining sensors to read should not be empty");
    }
    
    String sensor = remainingSensors.iterator().next();
    remainingSensors.remove(sensor);
    
    String path = getUrl() +"/api/states/" + sensor;
    
    HttpRequest httpRequest = HttpRequest
          .create(path)
          .addCredentials(credentials);

    getHttp().singleRequest(httpRequest)
          .whenComplete((response, throwable) -> {
            if (throwable != null) {
              getContext().getSelf().tell(new HttpRequestFailed(throwable));
            } else {
              try {
                response.entity()
                      .toStrict(5000, getMaterializer())
                      .whenComplete((strictEntity, toStrictFailure) -> {
                        if (toStrictFailure != null) {
                          response.discardEntityBytes(getMaterializer());
                          getContext().getSelf().tell(
                                new HttpRequestFailed(toStrictFailure));
                        } else {
                          getContext().getSelf().tell(
                                new HttpRequestSuccess(sensor, remainingSensors, strictEntity));
                        }
                      });
              } catch (Exception e) {
                // Failed to get a strict entity
                getContext().getSelf().tell(new HttpRequestFailed(e));
              }
            }
          });
  }
  
  private void initChannels() {
    logger.trace("HomeAssistant.initChannels()");
    
    if (getPowerPhaseMode() == PhaseMode.MONO) {
      initChannelSensor("power-sensor", "power-total");
      initChannelSensor("power-production-sensor", "power-production-total");
    } else {
      initChannelSensor("power-l1-sensor", "power-l1");
      initChannelSensor("power-production-l1-sensor", "power-production-l1");
      initChannelSensor("power-l2-sensor", "power-l2");
      initChannelSensor("power-production-l2-sensor", "power-production-l2");
      initChannelSensor("power-l3-sensor", "power-l3");
      initChannelSensor("power-production-l3-sensor", "power-production-l3");
    }
    
    if (getEnergyPhaseMode() == PhaseMode.MONO) {
      initChannelSensor("energy-consumption-sensor", "energy-consumption-total");
      initChannelSensor("energy-production-sensor", "energy-production-total");
    } else {
      initChannelSensor("energy-consumption-l1-sensor", "energy-consumption-l1");
      initChannelSensor("energy-production-l1-sensor", "energy-production-l1");
      initChannelSensor("energy-consumption-l2-sensor", "energy-consumption-l2");
      initChannelSensor("energy-production-l2-sensor", "energy-production-l2");
      initChannelSensor("energy-consumption-l3-sensor", "energy-consumption-l3");
      initChannelSensor("energy-production-l3-sensor", "energy-production-l3");
    }
    
    if (sensorChannelMap.isEmpty()) {
      throw new RuntimeException("at least one channel sensor has to be configured");
    }
  }
  
  private void initChannelSensor(String key, String channel) {
    String sensor = getConfig().getString(key);
    if (!StringUtils.isAllBlank(sensor)) {
      sensorChannelMap.put(sensor, channel);
    }
  }
  
  private enum ExecuteNextPollingCycle implements Command {
    INSTANCE
  }
  
  private record HttpRequestFailed(
        @NotNull Throwable throwable
  ) implements Command {}
  
  private record HttpRequestSuccess(
        @NotNull String sensor,
        @NotNull Collection<String> remainingSensors,
        @NotNull HttpEntity.Strict strictEntity
  ) implements Command {}
}
