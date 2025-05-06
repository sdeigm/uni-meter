package com.deigmueller.uni_meter.input.device.vzlogger;

import com.deigmueller.uni_meter.input.device.common.http.HttpInputDevice;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.typesafe.config.Config;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.apache.pekko.http.javadsl.model.HttpEntity;
import org.apache.pekko.http.javadsl.model.HttpRequest;
import org.apache.pekko.http.javadsl.model.HttpResponse;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public class VzLogger extends HttpInputDevice {
  // Class members
  public static final String TYPE = "VZLogger";
  
  // Instance members
  private final Duration pollingInterval = getConfig().getDuration("polling-interval");
  private final String consumptionChannel = getConfig().getString("energy-consumption-channel");
  private final String productionChannel = getConfig().getString("energy-production-channel");
  private final String powerChannel = getConfig().getString("power-channel");
  private final PhaseMode powerPhaseMode = getPhaseMode("power-phase-mode");
  private final String powerPhase = getConfig().getString("power-phase");
  private final PhaseMode energyPhaseMode = getPhaseMode("energy-phase-mode");
  private final String energyPhase = getConfig().getString("energy-phase");
  private final int lastEnergyValuesQueueSize = getConfig().getInt("last-energy-values-queue-size");
  private final Deque<VzLoggerValue> lastProductionValues = new ArrayDeque<>();
  private final Deque<VzLoggerValue> lastConsumptionValues = new ArrayDeque<>();
  
  
  public static Behavior<Command> create(@NotNull ActorRef<OutputDevice.Command> outputDevice,
                                         @NotNull Config config) {
    return Behaviors.setup(context -> new VzLogger(context, outputDevice, config));
  }

  protected VzLogger(@NotNull ActorContext<Command> context,
                     @NotNull ActorRef<OutputDevice.Command> outputDevice,
                     @NotNull Config config) {
    super(context, outputDevice, config);
    
    executePolling();
  }

  @Override
  public ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onMessage(HttpRequestFailed.class, this::onHttpRequestFailed)
          .onMessage(HttpRequestSuccess.class, this::onHttpRequestSuccess)
          .onMessage(ExecuteNextPolling.class, this::onExecuteNextPolling);
  }
  
  protected Behavior<Command> onHttpRequestFailed(@NotNull HttpRequestFailed message) {
    logger.trace("VzLogger.onHttpRequestFailed()");
    
    logger.error("failed to execute polling: {}", message.throwable().getMessage());

    startNextPollingTimer();
    
    return Behaviors.same();
  }
  
  protected Behavior<Command> onHttpRequestSuccess(@NotNull HttpRequestSuccess message) {
    logger.trace("VzLogger.onHttpRequestSuccess()");
    
    HttpResponse httpResponse = message.response();

    if (httpResponse.entity().isStrict()) {
      // The entity is already strict
      handleEntity((HttpEntity.Strict) httpResponse.entity());
    } else {
      // The entity is not yet strict
      try {
        httpResponse.entity()
              .toStrict(5000, getMaterializer())
              .whenComplete((strictEntity, toStrictFailure) -> {
                if (toStrictFailure != null) {
                  httpResponse.discardEntityBytes(getMaterializer());
                  getContext().getSelf().tell(new HttpRequestFailed(toStrictFailure));
                } else {
                  if (httpResponse.status().isSuccess()) {
                    handleEntity(strictEntity);
                  } else {
                    getContext().getSelf().tell(new HttpRequestFailed(new IOException(
                          "http request to " + getUrl() + " failed with status " + httpResponse.status())));
                  }
                }
              });
      } catch (Exception e) {
        // Failed to get a strict entity
        getContext().getSelf().tell(new HttpRequestFailed(e));
      }
    }
    
    return Behaviors.same();
  }
  
  protected Behavior<Command> onExecuteNextPolling(@NotNull ExecuteNextPolling message) {
    logger.trace("VzLogger.onExecuteNextPolling()");
    
    executePolling();
    
    return Behaviors.same();
  }
  
  private void handleEntity(@NotNull HttpEntity.Strict strictEntity) {
    logger.trace("VzLogger.handleEntity()");
    
    try {
      VzLoggerResponse response = getObjectMapper().readValue(strictEntity.getData().utf8String(), VzLoggerResponse.class);
      
      double consumption = 0.0;
      VzLoggerValue consumptionValue = response.dataByUuid(consumptionChannel);
      if (consumptionValue == null) {
        logger.debug("no consumption data found for channel uuid {}", consumptionChannel);
      } else {
        consumption = consumptionValue.tuples().get(0).get(1);
        logger.debug("current consumption counter: {} kWh", Math.round(consumption / 1000.0));
        
        tryAddToQueue(lastConsumptionValues, consumptionValue);
      }

      double production = 0.0;
      VzLoggerValue productionValue = response.dataByUuid(productionChannel);
      if (productionValue == null) {
        logger.debug("no production data found for channel uuid {}", productionChannel);
      } else {
        production = productionValue.tuples().get(0).get(1);
        logger.debug("current production counter: {} kWh", Math.round(production / 1000.0));
        
        tryAddToQueue(lastProductionValues, productionValue);
      }

      notifyEnergyData(energyPhaseMode, energyPhase, production, consumption);

      double power = 0.0;
      VzLoggerValue powerValue = response.dataByUuid(powerChannel);
      if (powerValue == null) {
        logger.debug("no power data found for channel uuid {}", powerChannel);
        
        Double lastEnergyValuePower = calculatePowerFromEnergyValues();
        if (lastEnergyValuePower != null) {
          power = lastEnergyValuePower;
          logger.debug("using last energy values for power: {} W", power);
        }
      } else {
        power = powerValue.tuples().get(0).get(1);
        logger.debug("current power usage: {} W", power);
      }
      
      notifyPowerData(powerPhaseMode, powerPhase, power);
    } catch (Exception e) {
      logger.error("Failed to parse response: {}", e.getMessage());
    }
    
    startNextPollingTimer();
  }
  
  private void executePolling() {
    logger.trace("VzLogger.executePolling()");
    
    getHttp()
            .singleRequest(HttpRequest.create(getUrl()))
            .whenComplete((response, throwable) -> {
              if (throwable != null) {
                getContext().getSelf().tell(new HttpRequestFailed(throwable));
              } else {
                getContext().getSelf().tell(new HttpRequestSuccess(response));
              }
            });
  }
  
  private void startNextPollingTimer() {
    logger.trace("VzLogger.startNextPollingTimer()");

    getContext().getSystem().scheduler().scheduleOnce(
          pollingInterval,
          () -> getContext().getSelf().tell(ExecuteNextPolling.INSTANCE),
          getContext().getExecutionContext());
  }

  private @Nullable Double calculatePowerFromEnergyValues() {
    logger.trace("VzLogger.calculatePowerFromEnergyValues()");

    Double productionPower = calculatePowerFromAQueue(lastProductionValues);

    Double consumptionPower = calculatePowerFromAQueue(lastConsumptionValues);
    
    Double result = null;
    if (consumptionPower != null) {
      result = consumptionPower;
    } 
    if (productionPower != null) {
      if (result != null) {
        result -= productionPower;
      } else {
        result = -productionPower;
      }
    }
    
    return result;
  }
  
  private @Nullable Double calculatePowerFromAQueue(Deque<VzLoggerValue> deque) {
    logger.trace("VzLogger.calculatePowerFromAQueue()");

    if (deque.size() >= lastEnergyValuesQueueSize) {
      try {
        long timeDiff = Long.parseLong(deque.getFirst().last())
              - Long.parseLong(deque.getLast().last());

        double energyDiff = deque.getFirst().tuples().get(0).get(1)
              - deque.getLast().tuples().get(0).get(1);

        if (timeDiff > 0) {
          return Math.round(energyDiff / timeDiff * 3600.0 * 10000.0) / 10.0;
        }
      } catch (Exception e) {
        logger.debug("failed to calculate power from last energy values: {}", e.getMessage());
      }
    }
    return null;
  }
  
  private void tryAddToQueue(Deque<VzLoggerValue> deque, VzLoggerValue value) {
    logger.trace("VzLogger.tryAddToQueue()");
    
    if (deque.isEmpty() || !deque.peek().last().equals(value.last())) {
      deque.addFirst(value);
      if (deque.size() > lastEnergyValuesQueueSize) {
        deque.removeLast();
      }
    }
  }

  protected record HttpRequestFailed(
        @NotNull Throwable throwable
  ) implements Command {}

  protected record HttpRequestSuccess(
        @NotNull HttpResponse response
  ) implements Command {}
  
  protected enum ExecuteNextPolling implements Command {
    INSTANCE
  }
  
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  protected record VzLoggerResponse(
        @JsonProperty("version") String version,
        @JsonProperty("generator") String generator,
        @JsonProperty("data") List<VzLoggerValue> data
  ) {
    public @Nullable VzLoggerValue dataByUuid(@NotNull String uuid) {
      if (data != null) {
        for (VzLoggerValue value : data) {
          if (value.uuid().equals(uuid)) {
            return value;
          }
        }
      }
      return null;
    } 
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  protected record VzLoggerValue(
        @JsonProperty("uuid") String uuid,
        @JsonProperty("last") String last,
        @JsonProperty("interval") long interval,
        @JsonProperty("protocol") String protocol,
        @JsonProperty("tuples") List<List<Double>> tuples
  ) {}
}
