package com.deigmueller.uni_meter.input.device.refoss.em06p;

import com.deigmueller.uni_meter.input.device.common.http.HttpInputDevice;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.fasterxml.jackson.databind.ObjectMapper;
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

public class RefossEm06p extends HttpInputDevice {
    public static final String TYPE = "RefossEm06p";

    private final ObjectMapper objectMapper = Rpc.createObjectMapper();
    private final String url = getConfig().getString("url");
    private final Duration pollingInterval = getConfig().getDuration("polling-interval");
    private final PhaseMode powerPhaseMode = getPhaseMode("power-phase-mode");
    private final String powerPhase = getConfig().getString("power-phase");
    private final int channelId = getConfig().getInt("channel-id");
    private final int channelIdL1 = getConfig().getInt("channel-id-l1");
    private final int channelIdL2 = getConfig().getInt("channel-id-l2");
    private final int channelIdL3 = getConfig().getInt("channel-id-l3");

    public static Behavior<Command> create(@NotNull ActorRef<OutputDevice.Command> outputDevice,
                                           @NotNull Config config) {
        return Behaviors.setup(context -> new RefossEm06p(context, outputDevice, config));
    }

    protected RefossEm06p(@NotNull ActorContext<Command> context,
                          @NotNull ActorRef<OutputDevice.Command> outputDevice,
                          @NotNull Config config) {
        super(context, outputDevice, config);
        executePolling();
    }

    @Override
    public ReceiveBuilder<Command> newReceiveBuilder() {
        return super.newReceiveBuilder()
                .onMessage(StatusRequestFailed.class, this::onStatusRequestFailed)
                .onMessage(StatusRequestSuccess.class, this::onStatusRequestSuccess)
                .onMessage(ExecuteNextPolling.class, this::onExecuteNextPolling);
    }

    protected Behavior<Command> onStatusRequestFailed(@NotNull StatusRequestFailed message) {
        logger.trace("RefossEm06p.onStatusRequestFailed()");
        logger.error("failed to execute status polling: {}", message.throwable().getMessage());
        startNextPollingTimer();
        return Behaviors.same();
    }

    protected Behavior<Command> onStatusRequestSuccess(@NotNull StatusRequestSuccess message) {
        logger.trace("RefossEm06p.onStatusRequestSuccess()");

        HttpResponse httpResponse = message.response();
        try {
            httpResponse.entity()
                    .toStrict(5000, getMaterializer())
                    .whenComplete((strictEntity, toStrictFailure) -> {
                        if (toStrictFailure != null) {
                            httpResponse.discardEntityBytes(getMaterializer());
                            getContext().getSelf().tell(new StatusRequestFailed(toStrictFailure));
                        } else {
                            if (httpResponse.status().isSuccess()) {
                                handleStatusEntity(strictEntity);
                            } else {
                                getContext().getSelf().tell(new StatusRequestFailed(new IOException(
                                        "http request failed with status " + httpResponse.status())));
                            }
                        }
                    });
        } catch (Exception e) {
            getContext().getSelf().tell(new StatusRequestFailed(e));
        }

        return Behaviors.same();
    }

    protected Behavior<Command> onExecuteNextPolling(@NotNull ExecuteNextPolling message) {
        logger.trace("RefossEm06p.onExecuteNextPolling()");
        executePolling();
        return Behaviors.same();
    }

    private void handleStatusEntity(@NotNull HttpEntity.Strict strictEntity) {
        logger.trace("RefossEm06p.handleStatusEntity()");

        try {
            logger.debug("Em.Status.Get response: {}", strictEntity.getData().utf8String());

            Rpc.EmStatusGetResponse response = objectMapper.readValue(strictEntity.getData().toArray(), Rpc.EmStatusGetResponse.class);

            if (response.status() != null) {
                if (powerPhaseMode == PhaseMode.MONO) {
                    double power = 0.0;
                    double apparentPower = 0.0;
                    double powerFactor = 1.0;
                    double current = 0.0;
                    double voltage = getDefaultVoltage();
                    double frequency = getDefaultFrequency();

                    for (Rpc.EmStatusGetStatus status : response.status()) {
                         if (status.id() == channelId) {
                             power = status.power();
                             current = status.current();
                             voltage = status.voltage();
                             powerFactor = status.pf();
                             break;
                         }
                    }
                    notifyPowerData(powerPhaseMode, powerPhase, power, apparentPower, powerFactor, current, voltage, frequency);
                } else {
                    double power1 = 0.0, power2 = 0.0, power3 = 0.0;
                    double current1 = 0.0, current2 = 0.0, current3 = 0.0;
                    double voltage1 = getDefaultVoltage(), voltage2 = getDefaultVoltage(), voltage3 = getDefaultVoltage();
                    double pf1 = 1.0, pf2 = 1.0, pf3 = 1.0;

                    for (Rpc.EmStatusGetStatus status : response.status()) {
                        if (status.id() == channelIdL1) {
                            power1 = status.power();
                            current1 = status.current();
                            voltage1 = status.voltage();
                            pf1 = status.pf();
                        }
                        if (status.id() == channelIdL2) {
                            power2 = status.power();
                            current2 = status.current();
                            voltage2 = status.voltage();
                            pf2 = status.pf();
                        }
                        if (status.id() == channelIdL3) {
                            power3 = status.power();
                            current3 = status.current();
                            voltage3 = status.voltage();
                            pf3 = status.pf();
                        }
                    }

                    getOutputDevice().tell(
                            new OutputDevice.NotifyPhasesPowerData(
                                    getNextMessageId(),
                                    new OutputDevice.PowerData(power1, 0.0, pf1, current1, voltage1, getDefaultFrequency()),
                                    new OutputDevice.PowerData(power2, 0.0, pf2, current2, voltage2, getDefaultFrequency()),
                                    new OutputDevice.PowerData(power3, 0.0, pf3, current3, voltage3, getDefaultFrequency()),
                                    getOutputDeviceAckAdapter()
                            )
                    );
                }
            }

        } catch (Exception e) {
            logger.error("Failed to parse Em.Status.Get response: {}", e.getMessage());
        }

        startNextPollingTimer();
    }

    private void executePolling() {
        logger.trace("RefossEm06p.executePolling()");

        String requestUrl = url + "/rpc/Em.Status.Get?id=" + (powerPhaseMode == PhaseMode.MONO ? channelId : 65535);

        getHttp().singleRequest(HttpRequest.create(requestUrl))
                .whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        getContext().getSelf().tell(new StatusRequestFailed(throwable));
                    } else {
                        getContext().getSelf().tell(new StatusRequestSuccess(response));
                    }
                });
    }

    private void startNextPollingTimer() {
        logger.trace("RefossEm06p.startNextPollingTimer()");

        getContext().getSystem().scheduler().scheduleOnce(
                pollingInterval,
                () -> getContext().getSelf().tell(ExecuteNextPolling.INSTANCE),
                getContext().getExecutionContext());
    }

    protected record StatusRequestFailed(@NotNull Throwable throwable) implements Command {}
    protected record StatusRequestSuccess(@NotNull HttpResponse response) implements Command {}
    protected enum ExecuteNextPolling implements Command { INSTANCE }
}
