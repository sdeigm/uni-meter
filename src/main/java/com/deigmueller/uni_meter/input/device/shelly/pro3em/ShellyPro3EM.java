package com.deigmueller.uni_meter.input.device.shelly.pro3em;

import com.deigmueller.uni_meter.common.shelly.Rpc;
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
import org.apache.pekko.http.javadsl.model.HttpResponse;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.time.Duration;

public class ShellyPro3EM extends HttpInputDevice {
    // Class members
    public static final String TYPE = "ShellyPro3EM";
    
    // Instance members
    private final Duration emStatusPollingInterval = getConfig().getDuration("em-status-polling-interval");
    private final Duration emDataStatusPollingInterval = getConfig().getDuration("em-data-status-polling-interval");
    private final String emStatusUrl = getUrl() + "/rpc/EM.GetStatus?id=0";
    private final String emDataStatusUrl = getUrl() + "/rpc/EMData.GetStatus?id=0";
    
    public static Behavior<Command> create(@NotNull ActorRef<OutputDevice.Command> outputDevice,
                                           @NotNull Config config) {
        return Behaviors.setup(context -> new ShellyPro3EM(context, outputDevice, config));
    }

    protected ShellyPro3EM(@NotNull ActorContext<Command> context,
                           @NotNull ActorRef<OutputDevice.Command> outputDevice,
                           @NotNull Config config) {
        super(context, outputDevice, config);

        executeEmStatusPolling();
        executeEmDataStatusPolling();
    }

    @Override
    public ReceiveBuilder<Command> newReceiveBuilder() {
        return super.newReceiveBuilder()
                .onMessage(EmStatusRequestFailed.class, this::onEmStatusRequestFailed)
                .onMessage(EmStatusRequestSuccess.class, this::onEmStatusRequestSuccess)
                .onMessage(ExecuteNextEmStatusPolling.class, this::onExecuteNextEmStatusPolling)
                .onMessage(EmDataStatusRequestFailed.class, this::onEmDataStatusRequestFailed)
                .onMessage(EmDataStatusRequestSuccess.class, this::onEmDataStatusRequestSuccess)
                .onMessage(ExecuteNextEmDataStatusPolling.class, this::onExecuteNextEmDataStatusPolling);
    }

    protected Behavior<Command> onEmStatusRequestFailed(@NotNull EmStatusRequestFailed message) {
        logger.trace("ShellyPro3EM.onEmStatusRequestFailed()");

        logger.error("failed to execute EM.GetStatus polling: {}", message.throwable().getMessage());

        startNextEmStatusPollingTimer();

        return Behaviors.same();
    }

    protected Behavior<Command> onEmStatusRequestSuccess(@NotNull EmStatusRequestSuccess message) {
        logger.trace("ShellyPro3EM.onEmStatusRequestSuccess()");

        HttpResponse httpResponse = message.response();

        try {
            httpResponse.entity()
                  .toStrict(5000, getMaterializer())
                  .whenComplete((strictEntity, toStrictFailure) -> {
                      if (toStrictFailure != null) {
                          httpResponse.discardEntityBytes(getMaterializer());
                          getContext().getSelf().tell(new EmStatusRequestFailed(toStrictFailure));
                      } else {
                          if (httpResponse.status().isSuccess()) {
                              handleEmStatusEntity(strictEntity);
                          } else {
                              getContext().getSelf().tell(new EmStatusRequestFailed(new IOException(
                                    "http request to " + emStatusUrl + " failed with status " + httpResponse.status())));
                          }
                      }
                  });
        } catch (Exception e) {
            // Failed to get a strict entity
            getContext().getSelf().tell(new EmDataStatusRequestFailed(e));
        }

        return Behaviors.same();
    }

    protected Behavior<Command> onExecuteNextEmDataStatusPolling(@NotNull ExecuteNextEmDataStatusPolling message) {
        logger.trace("ShellyPro3EM.onExecuteNextEmDataStatusPolling()");

        executeEmDataStatusPolling();

        return Behaviors.same();
    }

    protected Behavior<Command> onEmDataStatusRequestFailed(@NotNull EmDataStatusRequestFailed message) {
        logger.trace("ShellyPro3EM.onEmDataStatusRequestFailed()");

        logger.error("failed to execute EMData.GetStatus polling: {}", message.throwable().getMessage());

        startNextEmDataStatusPollingTimer();

        return Behaviors.same();
    }

    protected Behavior<Command> onEmDataStatusRequestSuccess(@NotNull EmDataStatusRequestSuccess message) {
        logger.trace("ShellyPro3EM.onEmDataStatusRequestSuccess()");

        HttpResponse httpResponse = message.response();

        try {
            httpResponse.entity()
                    .toStrict(5000, getMaterializer())
                    .whenComplete((strictEntity, toStrictFailure) -> {
                        if (toStrictFailure != null) {
                            httpResponse.discardEntityBytes(getMaterializer());
                            getContext().getSelf().tell(new EmDataStatusRequestFailed(toStrictFailure));
                        } else {
                            if (httpResponse.status().isSuccess()) {
                                handleEmDataStatusEntity(strictEntity);
                            } else {
                                getContext().getSelf().tell(new EmDataStatusRequestFailed(new IOException(
                                      "http request to " + emDataStatusUrl + " failed with status " + httpResponse.status())));
                            }
                        }
                    });
        } catch (Exception e) {
            // Failed to get a strict entity
            getContext().getSelf().tell(new EmDataStatusRequestFailed(e));
        }

        return Behaviors.same();
    }

    protected Behavior<Command> onExecuteNextEmStatusPolling(@NotNull ExecuteNextEmStatusPolling message) {
        logger.trace("ShellyPro3EM.onExecuteNextEmStatusPolling()");

        executeEmStatusPolling();

        return Behaviors.same();
    }
    
    private void handleEmStatusEntity(@NotNull HttpEntity.Strict strictEntity) {
        logger.trace("ShellyPro3EM.handleEmStatusEntity()");

        try {
            logger.debug("EM.GetStatus response: {}", strictEntity.getData().utf8String());

            Rpc.EmGetStatusResponse response = 
                    getObjectMapper().readValue(strictEntity.getData().toArray(), Rpc.EmGetStatusResponse.class);
            
            getOutputDevice().tell(
                  new OutputDevice.NotifyPhasesPowerData(
                        getNextMessageId(),
                        new OutputDevice.PowerData(
                              response.a_act_power(),
                              response.a_aprt_power(),
                              response.a_pf(),
                              response.a_current(),
                              response.a_voltage(),
                              response.a_freq()),
                        new OutputDevice.PowerData(
                              response.b_act_power(),
                              response.b_aprt_power(),
                              response.b_pf(),
                              response.b_current(),
                              response.b_voltage(),
                              response.b_freq()),
                        new OutputDevice.PowerData(
                              response.c_act_power(),
                              response.c_aprt_power(),
                              response.c_pf(),
                              response.c_current(),
                              response.c_voltage(),
                              response.c_freq()),
                        getOutputDeviceAckAdapter()
                  )
            );
        } catch (Exception e) {
            logger.error("Failed to parse EM.GetStatus response: {}", e.getMessage());
        }

        startNextEmStatusPollingTimer();
    }

    private void executeEmStatusPolling() {
        logger.trace("ShellyPro3EM.executeEmStatusPolling()");

        getHttp().singleRequest(HttpRequest.create(emStatusUrl))
                .whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        getContext().getSelf().tell(new EmStatusRequestFailed(throwable));
                    } else {
                        getContext().getSelf().tell(new EmStatusRequestSuccess(response));
                    }
                });
    }

    private void startNextEmStatusPollingTimer() {
        logger.trace("ShellyPro3EM.startNextPollingTimer()");

        getContext().getSystem().scheduler().scheduleOnce(
                emStatusPollingInterval,
                () -> getContext().getSelf().tell(ExecuteNextEmStatusPolling.INSTANCE),
                getContext().getExecutionContext());
    }

    private void executeEmDataStatusPolling() {
        logger.trace("ShellyPro3EM.executeEmDataStatusPolling()");

        getHttp().singleRequest(HttpRequest.create(emDataStatusUrl))
                .whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        getContext().getSelf().tell(new EmDataStatusRequestFailed(throwable));
                    } else {
                        getContext().getSelf().tell(new EmDataStatusRequestSuccess(response));
                    }
                });
    }

    private void startNextEmDataStatusPollingTimer() {
        logger.trace("ShellyPro3EM.startNextEmDataStatusPollingTimer()");

        getContext().getSystem().scheduler().scheduleOnce(
                emDataStatusPollingInterval,
                () -> getContext().getSelf().tell(ExecuteNextEmDataStatusPolling.INSTANCE),
                getContext().getExecutionContext());
    }

    private void handleEmDataStatusEntity(@NotNull HttpEntity.Strict strictEntity) {
        logger.trace("ShellyPro3EM.handleEmDataStatusEntity()");

        try {
            logger.debug("EMData.GetStatus response: {}", strictEntity.getData().utf8String());

            Rpc.EmDataGetStatusResponse response =
                    getObjectMapper().readValue(strictEntity.getData().toArray(), Rpc.EmDataGetStatusResponse.class);
            
            getOutputDevice().tell(
                    new OutputDevice.NotifyPhaseEnergyData(
                            getNextMessageId(), 
                            0,
                            new OutputDevice.EnergyData(
                                    response.a_total_act_energy(),
                                    response.a_total_act_ret_energy()),
                            getOutputDeviceAckAdapter()));

            getOutputDevice().tell(
                    new OutputDevice.NotifyPhaseEnergyData(
                            getNextMessageId(),
                            1,
                            new OutputDevice.EnergyData(
                                    response.b_total_act_energy(),
                                    response.b_total_act_ret_energy()),
                            getOutputDeviceAckAdapter()));

            getOutputDevice().tell(
                    new OutputDevice.NotifyPhaseEnergyData(
                            getNextMessageId(),
                            2,
                            new OutputDevice.EnergyData(
                                    response.c_total_act_energy(),
                                    response.c_total_act_ret_energy()),
                            getOutputDeviceAckAdapter()));
        } catch (Exception e) {
            logger.error("Failed to parse EMData.GetStatus response: {}", e.getMessage());
        }

        startNextEmDataStatusPollingTimer();
    }
    
    protected record EmStatusRequestFailed(
            @NotNull Throwable throwable
    ) implements Command {}

    protected record EmStatusRequestSuccess(
            @NotNull HttpResponse response
    ) implements Command {}

    protected record EmDataStatusRequestFailed(
            @NotNull Throwable throwable
    ) implements Command {}

    protected record EmDataStatusRequestSuccess(
            @NotNull HttpResponse response
    ) implements Command {}

    protected enum ExecuteNextEmStatusPolling implements Command {
        INSTANCE
    }

    protected enum ExecuteNextEmDataStatusPolling implements Command {
        INSTANCE
    }
}
