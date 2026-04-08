/*
 * Copyright (C) 2018-2023 layline.io GmbH <http://www.layline.io>
 */

package com.deigmueller.uni_meter.input.device.poweropti;

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
import org.apache.pekko.http.javadsl.model.HttpHeader;
import org.apache.pekko.http.javadsl.model.HttpRequest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

public class Poweropti extends HttpInputDevice {
  // Class members
  public static final String TYPE = "Poweropti";

  // Instance members
  private final PhaseMode powerPhaseMode = getPhaseMode("power-phase-mode");
  private final String powerPhase = getConfig().getString("power-phase");
  private final PhaseMode energyPhaseMode = getPhaseMode("energy-phase-mode");
  private final String energyPhase = getConfig().getString("energy-phase");
  private final HttpHeader xApiKeyHeader = HttpHeader.parse("X-API-KEY", getConfig().getString("api-key"));
  private final String requestUrl = getUrl() + "/value";
  private final Duration pollingInterval = getConfig().getDuration("polling-interval");
  private long lastTimestamp = 0;


  /**
   * Static setup method.
   * @param outputDevice The output device to send the data to
   * @param config The configuration for the Poweropti input device
   * @return Behavior of the created actor
   */ 
  public static Behavior<Command> create(@NotNull ActorRef<OutputDevice.Command> outputDevice,
                                         @NotNull Config config) {
    return Behaviors.setup(context -> new Poweropti(context, outputDevice, config));
  }

  /**
   * Protected constructor called by the setup method.
   * @param context The actor context
   * @param outputDevice The output device to send the data to
   * @param config The configuration for the Poweropti input device
   */
  protected Poweropti(@NotNull ActorContext<Command> context, 
                      @NotNull ActorRef<OutputDevice.Command> outputDevice, 
                      @NotNull Config config) {
    super(context, outputDevice, config);
    
    executePolling();
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
          .onMessage(ExecuteNextPolling.class, this::onExecuteNextPolling);
  }

  /**
   * Handle a HttpRequestFailed message.
   * @param message Message to handle
   * @return Same behavior
   */
  protected Behavior<Command> onHttpRequestFailed(@NotNull HttpRequestFailed message) {
    logger.trace("Poweropti.onHttpRequestFailed()");

    logger.error("failed to execute http polling: {}", message.throwable().getMessage());

    startNextPollingTimer();

    return Behaviors.same();
  }

  /**
   * Handle a HttpRequestSuccess message.
   * @param message Message to handle
   * @return Same behavior
   */
  protected Behavior<Command> onHttpRequestSuccess(@NotNull HttpRequestSuccess message) {
    logger.trace("Poweropti.onHttpRequestSuccess()");

    try {
      logger.debug("http response: {}", message.entity().getData().utf8String());
      Response response = getObjectMapper().readValue(message.entity().getData().utf8String(), Response.class);
      
      if (response.timestamp() == lastTimestamp) {
        logger.debug("no new data available");
      } else {
        lastTimestamp = response.timestamp();
        
        Double power = findValue(response, "1.7.0");
        if (power != null) {
          notifyPowerData(powerPhaseMode, powerPhase, power);
        } else {
          logger.debug("no power data available");
        }
        
        Double importEnergy = findValue(response, "1.8.0");
        Double exportEnergy = findValue(response, "2.8.0");
        if (importEnergy != null || exportEnergy != null) {
          notifyEnergyData(
                energyPhaseMode, 
                energyPhase, 
                importEnergy != null ? importEnergy : 0.0, 
                exportEnergy != null ? exportEnergy : 0.0);
        } else {
          logger.debug("no energy data available");
        }
      }
    } catch (Exception e) {
      logger.error("Failed to parse http response: {}", e.getMessage());
    }

    startNextPollingTimer();

    return Behaviors.same();
  }

  /**
   * Handle the notification to execute the next status polling.
   * @param message Notification message
   * @return Same behavior
   */
  protected Behavior<Command> onExecuteNextPolling(@NotNull ExecuteNextPolling message) {
    logger.trace("Poweropti.onExecuteNextPolling()");

    executePolling();

    return Behaviors.same();
  }
  
  private void executePolling() {
    logger.trace("Poweropti.executePolling()");
    
    HttpRequest request = HttpRequest.create(requestUrl).addHeader(xApiKeyHeader);

    getHttp()
          .singleRequest(request)
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
                          getContext().getSelf().tell(new HttpRequestFailed(toStrictFailure));
                        } else {
                          if (response.status().isSuccess()) {
                            getContext().getSelf().tell(new HttpRequestSuccess(strictEntity));
                          } else {
                            getContext().getSelf().tell(new HttpRequestFailed(new IOException(
                                  "http request to " + requestUrl + " failed with status " + response.status())));
                          }
                        }
                      });
              } catch (Exception e) {
                // Failed to get a strict entity
                getContext().getSelf().tell(new HttpRequestFailed(e));
              }
            }
          });
  }

  private void startNextPollingTimer() {
    logger.trace("Poweropti.startNextPollingTimer()");

    getContext().getSystem().scheduler().scheduleOnce(
          pollingInterval,
          () -> getContext().getSelf().tell(ExecuteNextPolling.INSTANCE),
          getContext().getExecutionContext());
  }

  /**
   * Find the value for the given OBIS channel in the given response.
   * @param response The response to search in
   * @param obis The OBIS channel to search for
   * @return The value for the given OBIS channel or null if not found
   */
  private @Nullable Double findValue(@NotNull Response response, @NotNull String obis) {
    for (Value value : response.values()) {
      if (value.obis().equals(obis)) {
        return value.value();
      }
    }
    return null;
  }

  protected enum ExecuteNextPolling implements Command {
    INSTANCE
  }

  protected record HttpRequestFailed(
        @NotNull Throwable throwable
  ) implements Command {}

  protected record HttpRequestSuccess(
        @NotNull HttpEntity.Strict entity
  ) implements Command {}
  
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  protected record Response(
        @JsonProperty("timestamp") long timestamp,
        @JsonProperty("values") List<Value> values
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  protected record Value(
        @JsonProperty("obis") String obis,
        @JsonProperty("value") double value
  ) {}
}
