package com.deigmueller.uni_meter.input.device.homewizard;

import com.deigmueller.uni_meter.input.device.common.http.HttpInputDevice;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.typesafe.config.Config;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.apache.pekko.http.javadsl.model.HttpEntity;
import org.apache.pekko.http.javadsl.model.HttpRequest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Input device reading the local API (v1) of a HomeWizard Wi-Fi P1 meter.
 * The "Local API" has to be enabled in the HomeWizard Energy app.
 */
public class HomeWizardP1 extends HttpInputDevice {
  // Class members
  public static final String TYPE = "HomeWizardP1";

  // Instance members
  private final PhaseMode powerPhaseMode = getPhaseMode("power-phase-mode");
  private final String powerPhase = getConfig().getString("power-phase");
  private final PhaseMode energyPhaseMode = getPhaseMode("energy-phase-mode");
  private final String energyPhase = getConfig().getString("energy-phase");
  private final String requestUrl = getUrl() + "/api/v1/data";
  private final Duration pollingInterval = getConfig().getDuration("polling-interval");
  private final Duration requestTimeout = getConfig().getDuration("request-timeout");

  public static Behavior<Command> create(@NotNull ActorRef<OutputDevice.Command> outputDevice,
                                         @NotNull Config config) {
    return Behaviors.setup(context -> new HomeWizardP1(context, outputDevice, config));
  }

  protected HomeWizardP1(@NotNull ActorContext<Command> context,
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
    logger.trace("HomeWizardP1.onHttpRequestFailed()");

    logger.error("failed to execute http polling: {}", message.throwable().getMessage());

    startNextPollingTimer();

    return Behaviors.same();
  }

  protected Behavior<Command> onHttpRequestSuccess(@NotNull HttpRequestSuccess message) {
    logger.trace("HomeWizardP1.onHttpRequestSuccess()");

    try {
      String body = message.entity().getData().utf8String();
      logger.debug("http response: {}", body);

      Response response = getObjectMapper().readValue(body, Response.class);

      notifyPower(response);
      notifyEnergy(response);
    } catch (Exception e) {
      logger.error("Failed to parse http response: {}", e.getMessage());
    }

    startNextPollingTimer();

    return Behaviors.same();
  }

  protected Behavior<Command> onExecuteNextPolling(@NotNull ExecuteNextPolling message) {
    logger.trace("HomeWizardP1.onExecuteNextPolling()");

    executePolling();

    return Behaviors.same();
  }

  private void notifyPower(@NotNull Response response) {
    double frequency = response.frequency() != null ? response.frequency() : getDefaultFrequency();

    boolean hasPhaseValues = response.powerL1() != null && response.powerL2() != null && response.powerL3() != null;

    if (powerPhaseMode == PhaseMode.TRI && hasPhaseValues) {
      getOutputDevice().tell(new OutputDevice.NotifyPhasesPowerData(
            getNextMessageId(),
            toPowerData(response.powerL1(), response.voltageL1(), frequency),
            toPowerData(response.powerL2(), response.voltageL2(), frequency),
            toPowerData(response.powerL3(), response.voltageL3(), frequency),
            getOutputDeviceAckAdapter()));
    } else if (response.power() != null) {
      double voltage = response.voltageL1() != null ? response.voltageL1() : getDefaultVoltage();
      double power = response.power();
      notifyPowerData(powerPhaseMode, powerPhase, power, Math.abs(power), 1.0, power / voltage, voltage, frequency);
    } else {
      logger.debug("no power data available");
    }
  }

  private void notifyEnergy(@NotNull Response response) {
    if (response.importKwh() == null && response.exportKwh() == null) {
      logger.debug("no energy data available");
      return;
    }

    // HomeWizard reports kWh, uni-meter expects Wh
    notifyEnergyData(
          energyPhaseMode,
          energyPhase,
          response.importKwh() != null ? response.importKwh() * 1000.0 : 0.0,
          response.exportKwh() != null ? response.exportKwh() * 1000.0 : 0.0);
  }

  private @NotNull OutputDevice.PowerData toPowerData(double power, @Nullable Double voltage, double frequency) {
    double v = voltage != null && voltage > 0.0 ? voltage : getDefaultVoltage();
    return new OutputDevice.PowerData(power, Math.abs(power), 1.0, power / v, v, frequency);
  }

  private void executePolling() {
    logger.trace("HomeWizardP1.executePolling()");

    HttpRequest request = HttpRequest.create(requestUrl);
    ActorRef<Command> self = getContext().getSelf();

    getHttp()
          .singleRequest(request)
          .toCompletableFuture()
          .orTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
          .whenComplete((response, throwable) -> {
            if (throwable != null) {
              self.tell(new HttpRequestFailed(throwable instanceof TimeoutException
                    ? new IOException("no response from " + requestUrl + " within " + requestTimeout.toMillis() + "ms")
                    : throwable));
            } else {
              try {
                response.entity()
                      .toStrict(requestTimeout.toMillis(), getMaterializer())
                      .whenComplete((strictEntity, toStrictFailure) -> {
                        if (toStrictFailure != null) {
                          response.discardEntityBytes(getMaterializer());
                          self.tell(new HttpRequestFailed(toStrictFailure));
                        } else if (response.status().isSuccess()) {
                          self.tell(new HttpRequestSuccess(strictEntity));
                        } else {
                          self.tell(new HttpRequestFailed(new IOException(
                                "http request to " + requestUrl + " failed with status " + response.status())));
                        }
                      });
              } catch (Exception e) {
                self.tell(new HttpRequestFailed(e));
              }
            }
          });
  }

  private void startNextPollingTimer() {
    ActorRef<Command> self = getContext().getSelf();
    getContext().getSystem().scheduler().scheduleOnce(
          pollingInterval,
          () -> self.tell(ExecuteNextPolling.INSTANCE),
          getContext().getExecutionContext());
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
  protected record Response(
        @JsonProperty("active_power_w") Double power,
        @JsonProperty("active_power_l1_w") Double powerL1,
        @JsonProperty("active_power_l2_w") Double powerL2,
        @JsonProperty("active_power_l3_w") Double powerL3,
        @JsonProperty("active_voltage_l1_v") Double voltageL1,
        @JsonProperty("active_voltage_l2_v") Double voltageL2,
        @JsonProperty("active_voltage_l3_v") Double voltageL3,
        @JsonProperty("active_frequency_hz") Double frequency,
        @JsonProperty("total_power_import_kwh") Double importKwh,
        @JsonProperty("total_power_export_kwh") Double exportKwh
  ) {}
}
