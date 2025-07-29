package com.deigmueller.uni_meter.input.device.inexogy;

import com.deigmueller.uni_meter.input.device.common.http.HttpInputDevice;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.github.scribejava.core.model.OAuthRequest;
import com.github.scribejava.core.model.Response;
import com.github.scribejava.core.model.Verb;
import com.typesafe.config.Config;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.DispatcherSelector;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.concurrent.Executor;

public class Inexogy extends HttpInputDevice {
  // Class members
  public static final String TYPE = "Inexogy";

  // Instance members
  private final Executor executor = getContext().getSystem().dispatchers().lookup(DispatcherSelector.blocking());
  private final Duration pollingInterval = getConfig().getDuration("polling-interval");
  private final String clientId = getConfig().getString("client-id");
  private final String email = getConfig().getString("email");
  private final String password = getConfig().getString("password");
  private final String meterId = getConfig().getString("meter-id");
  private final String fields = getConfig().getString("fields");
  private final boolean readSubMeters = getConfig().getBoolean("read-sub-meters");
  private InexogyApiClient apiClient;

  /**
   * Static setup method to create a new instance of the Inexogy input device.
   * @param outputDevice The output device to send the data to.
   * @param config The configuration for the MQTT input device.
   * @return The behavior of the MQTT input device.
   */
  public static Behavior<Command> create(@NotNull ActorRef<OutputDevice.Command> outputDevice,
                                         @NotNull Config config) {
    return Behaviors.setup(context -> new Inexogy(context, outputDevice, config));
  }

  /**
   * Protected constructor called by the static setup method.
   * @param context The actor context.
   * @param outputDevice The output device to send the data to.
   * @param config The configuration for the Inexogy input device.
   */
  protected Inexogy(@NotNull ActorContext<Command> context, 
                    @NotNull ActorRef<OutputDevice.Command> outputDevice, 
                    @NotNull Config config) {
    super(context, outputDevice, config);
    
    createApiClient();
  }

  @Override
  public @NotNull ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onMessage(GetLastReadingSucceeded.class, this::onGetLastReadingsSucceeded)
          .onMessage(GetLastReadingFailed.class, this::onGetLastReadingsFailed)
          .onMessage(ExecuteNextPolling.class,  this::onExecuteNextPolling)
          .onMessage(ApiClientCreationSucceeded.class, this::onApiClientCreationSucceeded)
          .onMessage(ApiClientCreationFailed.class, this::onApiClientCreationFailed)
          .onMessage(RetryApiClientCreation.class, this::onRetryApiClientCreation);
  }
  
  private @NotNull Behavior<Command> onGetLastReadingsSucceeded(@NotNull Inexogy.GetLastReadingSucceeded message) {
    logger.trace("Inexogy.onGetLastReadingsSucceeded()");
    
    logger.debug("received last readings: {}", message.responseBody);
    
    try {
      MeterReading reading = getObjectMapper().readValue(message.responseBody, MeterReading.class);
      
      MeterValues values = reading.values();
      
      if (values.power1() != null || values.power2() != null || values.power3() != null) {
        getOutputDevice().tell(new OutputDevice.NotifyPhasesPowerData(
              getNextMessageId(),
              getPhasePowerData(values.power1(), values.voltage1()),
              getPhasePowerData(values.power2(), values.voltage2()),
              getPhasePowerData(values.power3(), values.voltage3()),
              getOutputDeviceAckAdapter()));
      } else {
        notifyPowerData(PhaseMode.TRI, "l1", values.power());
      }
      
      notifyEnergyData(PhaseMode.TRI, "l1", values.energy() / 10000000000L, values.energyOut() / 10000000000L);
    } catch (Exception e) {
      logger.error("failed to parse last readings response: {}", e.getMessage());
      return Behaviors.same();
    } finally {
      startNextPollingTimer();
    }
    
    return Behaviors.same();
  }
  
  private @NotNull OutputDevice.PowerData getPhasePowerData(@Nullable Double power, @Nullable Double voltage) {
    double p = power != null ? power / 1000.0 : 0.0;
    double v = voltage != null ? voltage : getDefaultVoltage();
    
    return new OutputDevice.PowerData(
          p,
          p,
          1.0,
          calcCurrent(p, v),
          v,
          getDefaultFrequency());
  }
  
  private @NotNull Behavior<Command> onGetLastReadingsFailed(@NotNull Inexogy.GetLastReadingFailed message) {
    logger.trace("Inexogy.onGetLastReadingsFailed()");
    
    logger.error("failed to get last readings: {}", message.failure.getMessage());

    startNextPollingTimer();

    return Behaviors.same();
  }
  
  private @NotNull Behavior<Command> onExecuteNextPolling(@NotNull ExecuteNextPolling message) {
    logger.trace("Inexogy.onExecuteNextPolling()");

    getLastReading();
    
    return Behaviors.same();
  }
  
  private @NotNull Behavior<Command> onApiClientCreationSucceeded(@NotNull ApiClientCreationSucceeded message) {
    logger.trace("Inexogy.onApiClientCreationSucceeded()");

    apiClient = message.apiClient;
    
    getLastReading();
    
    return Behaviors.same();
  }
  
  private @NotNull Behavior<Command> onApiClientCreationFailed(@NotNull ApiClientCreationFailed message) {
    logger.trace("Inexogy.onApiClientCreationFailed()");
    
    logger.error("failed to create Inexogy API client: {}", message.failure.getMessage());

    getContext().getSystem().scheduler().scheduleOnce(
          Duration.ofSeconds(30),
          () -> getContext().getSelf().tell(RetryApiClientCreation.INSTANCE),
          getContext().getSystem().executionContext());

    return Behaviors.same();
  }
  
  private @NotNull Behavior<Command> onRetryApiClientCreation(@NotNull RetryApiClientCreation message) {
    logger.trace("Inexogy.onRetryApiClientCreation()");
    
    createApiClient();
    
    return Behaviors.same();
  }
  
  private void createApiClient() {
    logger.trace("Inexogy.createApiClient()");
    
    if (apiClient == null) {
      executor.execute(() -> {
        try {
          getContext().getSelf().tell(
                new ApiClientCreationSucceeded(
                      new InexogyApiClient(getObjectMapper(), clientId, getUrl(), email, password)));
        } catch (Exception e) {
          getContext().getSelf().tell(new ApiClientCreationFailed(e));
        }
      });
    }
  }
  
  private void startNextPollingTimer() {
    logger.trace("Inexogy.startNextPollingTimer()");
    
    getContext().getSystem().scheduler().scheduleOnce(
          pollingInterval,
          () -> getContext().getSelf().tell(ExecuteNextPolling.INSTANCE),
          getContext().getSystem().executionContext());
  }
  
  private void getLastReading() {
    executor.execute(() -> {
      try {
        OAuthRequest request = apiClient.createRequest(Verb.GET, "/last_reading");
        request.addParameter("meterId", meterId);
        request.addParameter("fields", fields);
        request.addParameter("each", readSubMeters ? "true" : "false");

        try (Response response = apiClient.executeRequest(request, 200)) {
          getContext().getSelf().tell(new GetLastReadingSucceeded(response.getBody()));
        } catch (Exception e) {
          getContext().getSelf().tell(new GetLastReadingFailed(e));
        }
      } catch (Exception e) {
        getContext().getSelf().tell(new GetLastReadingFailed(e));
      }
    });
  }
  
  public record ApiClientCreationSucceeded(
        @NotNull InexogyApiClient apiClient
  ) implements Command {}
  
  public record ApiClientCreationFailed(
        @NotNull Throwable failure
  ) implements Command {}
  
  public enum RetryApiClientCreation implements Command {
    INSTANCE
  }
  
  public record GetLastReadingSucceeded(
        @NotNull String responseBody
  ) implements Command {}
  
  public record GetLastReadingFailed(
        @NotNull Throwable failure
  ) implements Command {}

  public enum ExecuteNextPolling implements Command {
    INSTANCE
  }
}
