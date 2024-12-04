package com.deigmueller.uni_meter.input.device.vzlogger;

import com.deigmueller.uni_meter.input.InputDevice;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.apache.pekko.http.javadsl.Http;
import org.apache.pekko.http.javadsl.model.HttpEntity;
import org.apache.pekko.http.javadsl.model.HttpRequest;
import org.apache.pekko.http.javadsl.model.HttpResponse;
import org.apache.pekko.stream.Materializer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.List;

public class VzLogger extends InputDevice {
  // Class members
  public static final String TYPE = "VZLogger";
  
  // Instance members
  private final Http http = Http.get(getContext().getSystem());
  private final Materializer materializer = Materializer.createMaterializer(getContext());
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final double defaultVoltage = getConfig().getDouble("default-voltage");
  private final double defaultFrequency = getConfig().getDouble("default-frequency");
  private final String url = getConfig().getString("url");
  private final Duration pollingInterval = getConfig().getDuration("polling-interval");
  private final String consumptionChannel = getConfig().getString("energy-consumption-channel");
  private final String productionChannel = getConfig().getString("energy-production-channel");
  private final String powerChannel = getConfig().getString("power-channel");
  
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
              .toStrict(5000, materializer)
              .whenComplete((strictEntity, toStrictFailure) -> {
                if (toStrictFailure != null) {
                  getContext().getSelf().tell(new HttpRequestFailed(toStrictFailure));
                } else {
                  handleEntity(strictEntity);
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
      VzLoggerResponse response = objectMapper.readValue(strictEntity.getData().utf8String(), VzLoggerResponse.class);
      
      double power = 0.0;
      VzLoggerValue powerValue = response.dataByUuid(powerChannel);
      if (powerValue == null) {
        logger.debug("no power data found for channel uuid {}", powerChannel);
      } else {
        power = powerValue.tuples().get(0).get(1);
        logger.debug("current power usage: {} W", power);
      }
      
      OutputDevice.PowerData data = new OutputDevice.PowerData(
            power,
            power, 
            1.0,
            power / defaultVoltage, 
            defaultVoltage, 
            defaultFrequency);

      getOutputDevice().tell(new OutputDevice.NotifyTotalPowerData(0, data, getOutputDeviceAckAdapter()));

      double consumption = 0.0;
      VzLoggerValue consumptionValue = response.dataByUuid(consumptionChannel);
      if (consumptionValue == null) {
        logger.debug("no consumption data found for channel uuid {}", consumptionChannel);
      } else {
        consumption = consumptionValue.tuples().get(0).get(1);
        logger.debug("current consumption counter: {} kWh", Math.round(consumption / 1000.0));
      }

      double production = 0.0;
      VzLoggerValue productionValue = response.dataByUuid(productionChannel);
      if (productionValue == null) {
        logger.debug("no production data found for channel uuid {}", productionChannel);
      } else {
        production = productionValue.tuples().get(0).get(1);
        logger.debug("current production counter: {} kWh", Math.round(production / 1000.0));
      }

      OutputDevice.EnergyData energyData = new OutputDevice.EnergyData(consumption, production);
      getOutputDevice().tell(new OutputDevice.NotifyTotalEnergyData(0, energyData, getOutputDeviceAckAdapter()));
    } catch (Exception e) {
      logger.error("Failed to parse response: {}", e.getMessage());
    }
    
    startNextPollingTimer();
  }
  
  private void executePolling() {
    logger.trace("VzLogger.executePolling()");
    
    http.singleRequest(HttpRequest.create(url))
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
