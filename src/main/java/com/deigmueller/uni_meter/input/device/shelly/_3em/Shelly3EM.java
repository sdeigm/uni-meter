package com.deigmueller.uni_meter.input.device.shelly._3em;

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
import org.apache.pekko.http.javadsl.model.StatusCodes;
import org.apache.pekko.http.javadsl.model.headers.HttpChallenge;
import org.apache.pekko.http.javadsl.model.headers.HttpCredentials;
import org.apache.pekko.http.javadsl.model.headers.WWWAuthenticate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Shelly 3EM input device.
 */
public class Shelly3EM extends HttpInputDevice {
  // Class members
  public static final String TYPE = "Shelly3EM";

  // Instance members
  private final Duration statusPollingInterval = getConfig().getDuration("polling-interval");
  private final String password = getConfig().getString("password");
  private final String username = getConfig().getString("username");
  private HttpCredentials credentials;

  /**
   * Static factory method to create a new Shelly3EM actor.
   * @param outputDevice The output device actor reference
   * @param config The configuration
   * @return Behavior of the created actor
   */
  public static Behavior<Command> create(@NotNull ActorRef<OutputDevice.Command> outputDevice,
                                         @NotNull Config config) {
    return Behaviors.setup(context -> new Shelly3EM(context, outputDevice, config));
  }

  /**
   * Protected constructor called by the static factory method.
   * @param context The actor context
   * @param outputDevice The output device actor reference
   * @param config The configuration
   */
  protected Shelly3EM(@NotNull ActorContext<Command> context,
                      @NotNull ActorRef<OutputDevice.Command> outputDevice,
                      @NotNull Config config) {
    super(context, outputDevice, config);

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
    logger.trace("Shelly3EM.onStatusRequestFailed()");
    
    if (credentials == null && message.httpResponse() != null && message.httpResponse().status() == StatusCodes.UNAUTHORIZED) {
      logger.debug("request was unauthorized");
      if (handleUnauthorizedFailure(message.httpResponse)) {
        logger.error("authentication method is not supported");
      }
    } else {
      logger.error("failed to execute /status polling: {}", message.throwable().getMessage());
    }

    startNextPollingTimer();

    return Behaviors.same();
  }
  
  private boolean handleUnauthorizedFailure(@NotNull HttpResponse httpResponse) {
    logger.trace("Shelly3EM.handleUnauthorizedFailure()");
    
    WWWAuthenticate wwwAuthenticate = httpResponse.getHeader(WWWAuthenticate.class).orElse(null);
    if (wwwAuthenticate == null) {
      logger.error("response to unauthorized request did not contain WWW-Authenticate header");
    } else {
      for (HttpChallenge challenge : wwwAuthenticate.getChallenges()) {
        if ("basic".equalsIgnoreCase(challenge.scheme())) {
          return initBasicAuthentication(); 
        } else if ("digest".equalsIgnoreCase(challenge.scheme())) {
          return initDigestAuthentication(challenge.realm(), challenge.getParams());
        }
      }
    }
    
    return false;
  }
  
  private boolean initBasicAuthentication() {
    credentials = HttpCredentials.createBasicHttpCredentials(username, password);
    return false;
  }
  
  private boolean initDigestAuthentication(String realm, Map<String, String> params) {
    return false;
  }
  
  /**
   * Handle a StatusRequestSuccess message.
   * @param message Message to handle
   * @return Same behavior
   */
  protected Behavior<Command> onStatusRequestSuccess(@NotNull StatusRequestSuccess message) {
    logger.trace("Shelly3EM.onStatusRequestSuccess()");

    try {
      logger.debug("/status response: {}", message.entity().getData().utf8String());

      EmStatusResponse response =
            getObjectMapper().readValue(message.entity().getData().toArray(), EmStatusResponse.class);
      
      OutputDevice.PowerData phase1PowerData = new OutputDevice.PowerData(
            response.emeters.get(0).power(),
            response.emeters.get(0).power() / response.emeters.get(0).pf(),
            response.emeters.get(0).pf(),
            response.emeters.get(0).current(),
            response.emeters.get(0).voltage(),
            getDefaultFrequency());
      OutputDevice.PowerData phase2PowerData = new OutputDevice.PowerData(
            response.emeters.get(1).power(),
            response.emeters.get(1).power() / response.emeters.get(1).pf(),
            response.emeters.get(1).pf(),
            response.emeters.get(1).current(),
            response.emeters.get(1).voltage(),
            getDefaultFrequency());
      OutputDevice.PowerData phase3PowerData = new OutputDevice.PowerData(
            response.emeters.get(2).power(),
            response.emeters.get(2).power() / response.emeters.get(2).pf(),
            response.emeters.get(2).pf(),
            response.emeters.get(2).current(),
            response.emeters.get(2).voltage(),
            getDefaultFrequency());

      getOutputDevice().tell(
            new OutputDevice.NotifyPhasesPowerData(
                  getNextMessageId(),
                  phase1PowerData,
                  phase2PowerData,
                  phase3PowerData,
                  getOutputDeviceAckAdapter()));
      
      OutputDevice.EnergyData phase1EnergyData = new OutputDevice.EnergyData(
            response.emeters.get(0).total(),
            response.emeters.get(0).total_returned());
      OutputDevice.EnergyData phase2EnergyData = new OutputDevice.EnergyData(
            response.emeters.get(1).total(),
            response.emeters.get(1).total_returned());
      OutputDevice.EnergyData phase3EnergyData = new OutputDevice.EnergyData(
            response.emeters.get(2).total(),
            response.emeters.get(2).total_returned());
      
      getOutputDevice().tell(
            new OutputDevice.NotifyPhasesEnergyData(
                  getNextMessageId(),
                  phase1EnergyData,
                  phase2EnergyData,
                  phase3EnergyData,
                  getOutputDeviceAckAdapter()));
  } catch (Exception e) {
      logger.error("Failed to parse /status response: {}", e.getMessage());
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
    logger.trace("Shelly3EM.onExecuteNextStatusPolling()");

    executeStatusPolling();

    return Behaviors.same();
  }

  
  private void executeStatusPolling() {
    logger.trace("Shelly3EM.executeStatusPolling()");
    
    final String requestUrl = getUrl() + "/status";

    HttpRequest httpRequest = HttpRequest.create(requestUrl);
    if (credentials != null) {
      httpRequest = httpRequest.addCredentials(credentials);
    }

    getHttp().singleRequest(httpRequest).whenComplete((response, throwable) -> {
            if (throwable != null) {
              getContext().getSelf().tell(new StatusRequestFailed(throwable, response));
            } else {
              try {
                response.entity()
                      .toStrict(5000, getMaterializer())
                      .whenComplete((strictEntity, toStrictFailure) -> {
                        if (toStrictFailure != null) {
                          response.discardEntityBytes(getMaterializer());
                          getContext().getSelf().tell(new StatusRequestFailed(toStrictFailure, response));
                        } else {
                          if (response.status().isSuccess()) {
                            getContext().getSelf().tell(new StatusRequestSuccess(strictEntity));
                          } else {
                            getContext().getSelf().tell(new StatusRequestFailed(
                                  new IOException(
                                        "http request to " + requestUrl + " failed with status " + response.status()),
                                  response));
                          }
                        }
                      });
              } catch (Exception e) {
                // Failed to get a strict entity
                getContext().getSelf().tell(new StatusRequestFailed(e, null));
              }
            }
          });
  }

  private void startNextPollingTimer() {
    logger.trace("Shelly3EM.startNextPollingTimer()");

    getContext().getSystem().scheduler().scheduleOnce(
          statusPollingInterval,
          () -> getContext().getSelf().tell(ExecuteNextStatusPolling.INSTANCE),
          getContext().getExecutionContext());
  }
  
  protected record StatusRequestFailed(
        @NotNull Throwable throwable,
        @Nullable HttpResponse httpResponse
  ) implements Command {}

  protected record StatusRequestSuccess(
        @NotNull HttpEntity.Strict entity
  ) implements Command {}
  
  protected enum ExecuteNextStatusPolling implements Command {
    INSTANCE
  }
  
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record EmStatusResponse(
        @JsonProperty("relays") List<EmRelay> relays,
        @JsonProperty("emeters") List<EmMeter> emeters,
        @JsonProperty("total_power") double total_power,
        @JsonProperty("fs_mounted") boolean fs_mounted
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record EmRelay(
        @JsonProperty("ison") boolean ison,
        @JsonProperty("has_timer") boolean has_timer,
        @JsonProperty("timer_started") long timer_started,
        @JsonProperty("timer_duration") long timer_duration,
        @JsonProperty("timer_remaining") long timer_remaining,
        @JsonProperty("overpower") boolean overpower,
        @JsonProperty("is_valid") boolean is_valid,
        @JsonProperty("source") String source
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record EmMeter(
        @JsonProperty("power") double power,
        @JsonProperty("pf") double pf,
        @JsonProperty("current") double current,
        @JsonProperty("voltage") double voltage,
        @JsonProperty("is_valid") boolean is_valid,
        @JsonProperty("total") double total,
        @JsonProperty("total_returned") double total_returned
  ) {}
}
