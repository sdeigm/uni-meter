package com.deigmueller.uni_meter.input.device.tibber.pulse;

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
import org.apache.pekko.http.javadsl.model.headers.HttpCredentials;
import org.apache.pekko.http.scaladsl.model.headers.BasicHttpCredentials;
import org.jetbrains.annotations.NotNull;
import java.io.DataInputStream;
import java.io.ByteArrayInputStream;
import org.openmuc.jsml.transport.Transport;
import org.openmuc.jsml.structures.SmlFile;
import org.openmuc.jsml.structures.SmlMessage;
import org.openmuc.jsml.structures.EMessageBody;
import org.openmuc.jsml.structures.responses.SmlGetListRes;
import org.openmuc.jsml.structures.SmlListEntry;
import java.util.List;
import java.time.Duration;

public class Pulse extends HttpInputDevice {
    // Class members
    public static final String TYPE = "TibberPulse";
    
    // Instance members
    private final PhaseMode powerPhaseMode = getPhaseMode("power-phase-mode");
    private final String powerPhase = getConfig().getString("power-phase");
    private final PhaseMode energyPhaseMode = getPhaseMode("energy-phase-mode");
    private final String energyPhase = getConfig().getString("energy-phase");
    private final String nodeId = getConfig().getString("node-id");
    private final String userId = getConfig().getString("user-id");
    private final String password = getConfig().getString("password");
    private final Duration pulseStatusPollingInterval = getConfig().getDuration("polling-interval");
    
    private final HttpCredentials credentials = BasicHttpCredentials.createBasicHttpCredentials(userId, password);

    public static Behavior<Command> create(@NotNull ActorRef<OutputDevice.Command> outputDevice,
                                           @NotNull Config config) {
        return Behaviors.setup(context -> new Pulse(context, outputDevice, config));
    }

    protected Pulse(@NotNull ActorContext<Command> context,
                           @NotNull ActorRef<OutputDevice.Command> outputDevice,
                           @NotNull Config config) {
        super(context, outputDevice, config);

        executePulseStatusPolling();
    }

    @Override
    public ReceiveBuilder<Command> newReceiveBuilder() {
        return super.newReceiveBuilder()
                .onMessage(PulseStatusRequestFailed.class, this::onPulseStatusRequestFailed)
                .onMessage(PulseStatusRequestSuccess.class, this::onPulseStatusRequestSuccess)
                .onMessage(ExecuteNextPulseStatusPolling.class, this::onExecuteNextPulseStatusPolling);
    }

    protected Behavior<Command> onPulseStatusRequestFailed(@NotNull PulseStatusRequestFailed message) {
        logger.trace("Pulse.onPulseStatusRequestFailed()");

        logger.error("failed to execute status polling: {}", message.throwable().getMessage());
        
        startNextPulseStatusPollingTimer();

        return Behaviors.same();
    }

    protected Behavior<Command> onPulseStatusRequestSuccess(@NotNull PulseStatusRequestSuccess message) {
        logger.trace("Pulse.onPulseStatusRequestSuccess()");

        HttpResponse httpResponse = message.response();

        try {
            httpResponse.entity()
                    .toStrict(5000, getMaterializer())
                    .whenComplete((strictEntity, toStrictFailure) -> {
                        if (toStrictFailure != null) {
                            getContext().getSelf().tell(new PulseStatusRequestFailed(toStrictFailure));
                        } else {
                            handlePulseStatusEntity(strictEntity);
                        }
                    });
        } catch (Exception e) {
            // Failed to get a strict entity
            getContext().getSelf().tell(new PulseStatusRequestFailed(e));
        }

        return Behaviors.same();
    }

    protected Behavior<Command> onExecuteNextPulseStatusPolling(@NotNull ExecuteNextPulseStatusPolling message) {
        logger.trace("Pulse.onExecuteNextPulseStatusPolling()");

        executePulseStatusPolling();

        return Behaviors.same();
    }

    private SmlGetListRes findListResponse(List<SmlMessage> messages) {
        for(SmlMessage message : messages) {
            logger.debug("Found tag {}", message.getMessageBody().getTag());
            if(message.getMessageBody().getTag() == EMessageBody.GET_LIST_RESPONSE) {
                return message.getMessageBody().getChoice();
            }
        }
        return null;
    }

    private double findEnergyImportEntry(SmlGetListRes response) {
        for(SmlListEntry entry : response.getValList().getValListEntry()) {
            logger.debug("Found OBIS code {}", entry.getObjName().toHexString());
            if(entry.getObjName().toHexString().equals("01 00 01 08 00 FF")) {
                int energyScaler = entry.getScaler().getIntVal();
                long energyValue = Long.parseLong(entry.getValue().toString());
                return energyValue * Math.pow(10, energyScaler);
            }
        }
        return 0.0;
    }

    private double findEnergyExportEntry(SmlGetListRes response) {
        for(SmlListEntry entry : response.getValList().getValListEntry()) {
            if(entry.getObjName().toHexString().equals("01 00 02 08 00 FF")) {
                int energyScaler = entry.getScaler().getIntVal();
                long energyValue = Long.parseLong(entry.getValue().toString());
                return energyValue * Math.pow(10, energyScaler);
            }
        }
        return 0.0;
    }

    private double findPowerEntry(SmlGetListRes response) {
        for(SmlListEntry entry : response.getValList().getValListEntry()) {
            if(entry.getObjName().toHexString().equals("01 00 10 07 00 FF")) {
                int powerScaler = entry.getScaler().getIntVal();
                long powerValue = Long.parseLong(entry.getValue().toString());
                return powerValue * Math.pow(10, powerScaler);
            }
        }
        return 0.0;
    }

    private void handlePulseStatusEntity(@NotNull HttpEntity.Strict strictEntity) {
        logger.trace("Pulse.handlePulseStatusEntity()");

        try {
            byte[] rawData = strictEntity.getData().toArray();
            DataInputStream dataStream = new DataInputStream(new ByteArrayInputStream(rawData));
            Transport smlTransport = new Transport();
            SmlFile smlData = smlTransport.getSMLFile(dataStream);
            List<SmlMessage> messages = smlData.getMessages();

            SmlGetListRes listResponse = findListResponse(messages);
            if(listResponse != null) {
                double energyImport = findEnergyImportEntry(listResponse);
                double energyExport = findEnergyExportEntry(listResponse);
                double power        = findPowerEntry(listResponse);

                logger.debug("Energy Import (Wh): {}", energyImport);
                logger.debug("Energy Export (Wh): {}", energyExport);
                logger.debug("Power (W): {}", power);
                
                notifyPowerData(powerPhaseMode, powerPhase, power);

                notifyEnergyData(energyPhaseMode, energyPhase, energyImport, energyExport);
            }
        } catch (Exception e) {
            logger.error("Failed to parse status response: {}", e.getMessage());
        }

        startNextPulseStatusPollingTimer();
    }
    
    private void executePulseStatusPolling() {
        logger.trace("Pulse.executePulseStatusPolling()");

        getHttp().singleRequest(HttpRequest.create(getUrl() + "/data.json?node_id=" + nodeId).addCredentials(credentials))
                .whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        getContext().getSelf().tell(new PulseStatusRequestFailed(throwable));
                    } else {
                        getContext().getSelf().tell(new PulseStatusRequestSuccess(response));
                    }
                });
    }

    private void startNextPulseStatusPollingTimer() {
        logger.trace("Pulse.startNextPulseStatusPollingTimer()");

        getContext().getSystem().scheduler().scheduleOnce(
                pulseStatusPollingInterval,
                () -> getContext().getSelf().tell(ExecuteNextPulseStatusPolling.INSTANCE),
                getContext().getExecutionContext());
    }

    protected record PulseStatusRequestFailed(
            @NotNull Throwable throwable
    ) implements Command {}

    protected record PulseStatusRequestSuccess(
            @NotNull HttpResponse response
    ) implements Command {}

    protected enum ExecuteNextPulseStatusPolling implements Command {
        INSTANCE
    }
}
