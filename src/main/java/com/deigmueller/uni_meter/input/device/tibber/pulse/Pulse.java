package com.deigmueller.uni_meter.input.device.tibber.pulse;

import com.deigmueller.uni_meter.input.device.common.http.HttpInputDevice;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.digitalpetri.modbus.internal.util.Hex;
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

import org.jetbrains.annotations.Nullable;
import org.openmuc.jsml.transport.Transport;
import org.openmuc.jsml.structures.SmlFile;
import org.openmuc.jsml.structures.SmlMessage;
import org.openmuc.jsml.structures.EMessageBody;
import org.openmuc.jsml.structures.responses.SmlGetListRes;
import org.openmuc.jsml.structures.SmlListEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Pulse extends HttpInputDevice {
    // Class members
    public static final String TYPE = "TibberPulse";
    private static final Pattern POWER_PATTERN = Pattern.compile("^1-0:16\\.7\\.0\\*255\\(([\\d.]+)\\*W\\)$");
    private static final Pattern ENERGY_IMPORT_PATTERN = Pattern.compile("^1-0:1\\.8\\.0\\*255\\(([\\d.]+)\\*kWh\\)$");
    private static final Pattern ENERGY_EXPORT_PATTERN = Pattern.compile("^1-0:2\\.8\\.0\\*255\\(([\\d.]+)\\*kWh\\)$");
    
    // Instance members
    private final PhaseMode powerPhaseMode = getPhaseMode("power-phase-mode");
    private final String powerPhase = getConfig().getString("power-phase");
    private final PhaseMode energyPhaseMode = getPhaseMode("energy-phase-mode");
    private final String energyPhase = getConfig().getString("energy-phase");
    private final String nodeId = getConfig().getString("node-id");
    private final String userId = getConfig().getString("user-id");
    private final String password = getConfig().getString("password");
    private final Duration pulseStatusPollingInterval = getConfig().getDuration("polling-interval");
    private final Duration jsmlTimeout = getConfig().getDuration("jsml-timeout");
    private final boolean textMode = getConfig().getBoolean("text-mode");
    private final String requestUrl = getUrl() + "/data.json?node_id=" + nodeId;
    private final HttpCredentials credentials = BasicHttpCredentials.createBasicHttpCredentials(userId, password);
    
    private int nParseErrorsLogged = 0;
    private Instant lastParseErrorLogged = Instant.MIN;

    /**
     * Create a new Pulse actor instance.
     * @param outputDevice The output device actor reference
     * @param config The configuration
     * @return Behavior of the created actor
     */
    public static Behavior<Command> create(@NotNull ActorRef<OutputDevice.Command> outputDevice,
                                           @NotNull Config config) {
        return Behaviors.setup(context -> new Pulse(context, outputDevice, config));
    }

    /**
     * Protected constructor called by the static factory method.
     * @param context The actor context
     * @param outputDevice The output device actor reference
     * @param config The configuration
     */
    protected Pulse(@NotNull ActorContext<Command> context,
                           @NotNull ActorRef<OutputDevice.Command> outputDevice,
                           @NotNull Config config) {
        super(context, outputDevice, config);

        executePulseStatusPolling();
    }

    /**
     * Create the actor's ReceiveBuilder.
     * @return The actor's ReceiveBuilder
     */
    @Override
    public ReceiveBuilder<Command> newReceiveBuilder() {
        return super.newReceiveBuilder()
              .onMessage(PulseStatusRequestFailed.class, this::onPulseStatusRequestFailed)
              .onMessage(PulseStatusRequestSuccess.class, this::onPulseStatusRequestSuccess)
              .onMessage(StrictEntity.class, this::onStrictEntity)
              .onMessage(ExecuteNextPulseStatusPolling.class, this::onExecuteNextPulseStatusPolling);
    }

    /**
     * Handle the notification that the pulse status request failed.
     * @param message PulseStatusRequestFailed message containing the failure information
     * @return Same behavior to continue processing
     */
    protected Behavior<Command> onPulseStatusRequestFailed(@NotNull PulseStatusRequestFailed message) {
        logger.trace("Pulse.onPulseStatusRequestFailed()");

        logger.error("failed to execute status polling: {}", message.throwable().getMessage());
        
        startNextPulseStatusPollingTimer();

        return Behaviors.same();
    }

    /**
     * Handle the successful response of the pulse status request.
     * @param message PulseStatusRequestSuccess message containing the HTTP response
     * @return Same behavior to continue processing
     */
    protected Behavior<Command> onPulseStatusRequestSuccess(@NotNull PulseStatusRequestSuccess message) {
        logger.trace("Pulse.onPulseStatusRequestSuccess()");

        HttpResponse httpResponse = message.response();

        try {
            httpResponse.entity()
                    .toStrict(5000, getMaterializer())
                    .whenComplete((strictEntity, toStrictFailure) -> {
                        if (toStrictFailure != null) {
                            httpResponse.discardEntityBytes(getMaterializer());
                            getContext().getSelf().tell(new PulseStatusRequestFailed(toStrictFailure));
                        } else {
                            if (httpResponse.status().isSuccess()) {
                                getContext().getSelf().tell(new StrictEntity(strictEntity));
                            } else {
                                getContext().getSelf().tell(new PulseStatusRequestFailed(new IOException(
                                      "http request to " + requestUrl + " failed with status " + httpResponse.status())));
                            }
                        }
                    });
        } catch (Exception e) {
            // Failed to get a strict entity
            getContext().getSelf().tell(new PulseStatusRequestFailed(e));
        }

        return Behaviors.same();
    }
    
    /**
     * Handle the strict entity message containing the parsed SML data.
     * @param message StrictEntity message containing the HTTP entity
     * @return Same behavior to continue processing
     */
    protected Behavior<Command> onStrictEntity(@NotNull StrictEntity message) {
        logger.trace("Pulse.onStrictEntity()");

        HttpEntity.Strict strictEntity = message.entity();

        try {
            if (textMode) {
                parseTextMode(strictEntity);
            } else {
                parseBinaryMode(strictEntity);
            }
        } catch (IOException e) {
            logParseError(e);
        } catch (Exception e) {
            logger.error("failure: {}", e.getMessage());
        }

        startNextPulseStatusPollingTimer();

        return Behaviors.same();
    }
    
    /**
     * Handle the notification to execute the next pulse status polling.
     * @param message Notification message
     * @return Same behavior to continue processing
     */
    protected Behavior<Command> onExecuteNextPulseStatusPolling(@NotNull ExecuteNextPulseStatusPolling message) {
        logger.trace("Pulse.onExecuteNextPulseStatusPolling()");

        executePulseStatusPolling();

        return Behaviors.same();
    }
    
    private void parseBinaryMode(HttpEntity.Strict strictEntity) throws IOException {
        logger.trace("Pulse.parseBinaryMode()");

        byte[] rawData = strictEntity.getData().toArray();
        if (logger.isDebugEnabled()) {
          logger.debug("Received raw data: {}", Hex.format(rawData));
        }
        DataInputStream dataStream = new DataInputStream(new ByteArrayInputStream(rawData));

        Transport smlTransport = new Transport();
        smlTransport.setTimeout((int) jsmlTimeout.toMillis());

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
    }

    private void parseTextMode(HttpEntity.Strict strictEntity) throws IOException {
        logger.trace("Pulse.parseTextMode()");

        String[] lines = new String(strictEntity.getData().toArray(), StandardCharsets.US_ASCII).split("\n");
       

        Double power = findPowerEntry(lines);
        if (power != null) {
            logger.debug("Power found (W): {}", power);
            notifyPowerData(powerPhaseMode, powerPhase, power);
        }

        Double energyImport = findEnergyImportEntry(lines);
        Double energyExport = findEnergyExportEntry(lines);
        if (energyImport != null && energyExport != null) {
            logger.debug("Energy Import found (Wh): {}", energyImport);
            logger.debug("Energy Export found (Wh): {}", energyExport);
            notifyEnergyData(energyPhaseMode, energyPhase, energyImport, energyExport);
        }
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

    static @Nullable Double findEnergyImportEntry(String[] lines) {
        for (String line : lines) {
            Matcher matcher = ENERGY_IMPORT_PATTERN.matcher(line);
            if (matcher.matches()) {
                return Double.parseDouble(matcher.group(1));
            }
        }
        return null;
    }

    double findEnergyImportEntry(SmlGetListRes response) {
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
    
    static @Nullable Double findEnergyExportEntry(String[] lines) {
        for (String line : lines) {
            Matcher matcher = ENERGY_EXPORT_PATTERN.matcher(line);
            if (matcher.matches()) {
                return Double.parseDouble(matcher.group(1));
            }
        }
        return null;
    }

    static double findEnergyExportEntry(SmlGetListRes response) {
        for(SmlListEntry entry : response.getValList().getValListEntry()) {
            if(entry.getObjName().toHexString().equals("01 00 02 08 00 FF")) {
                int energyScaler = entry.getScaler().getIntVal();
                long energyValue = Long.parseLong(entry.getValue().toString());
                return energyValue * Math.pow(10, energyScaler);
            }
        }
        return 0.0;
    }

    static @Nullable Double findPowerEntry(String[] lines) {
        for (String line : lines) {
            Matcher matcher = POWER_PATTERN.matcher(line);
            if (matcher.matches()) {
                return Double.parseDouble(matcher.group(1));
            }
        }
        return null;
    }

    static double findPowerEntry(SmlGetListRes response) {
        for(SmlListEntry entry : response.getValList().getValListEntry()) {
            if(entry.getObjName().toHexString().equals("01 00 10 07 00 FF")) {
                int powerScaler = entry.getScaler().getIntVal();
                long powerValue = Long.parseLong(entry.getValue().toString());
                return powerValue * Math.pow(10, powerScaler);
            }
        }
        return 0.0;
    }

    private void executePulseStatusPolling() {
        logger.trace("Pulse.executePulseStatusPolling()");

        getHttp()
              .singleRequest(HttpRequest.create(requestUrl).addCredentials(credentials))
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

    private void logParseError(@NotNull IOException e) {
        if (nParseErrorsLogged >= 5) {
            if (Instant.now().isAfter(lastParseErrorLogged.plusSeconds(86400))) {
                nParseErrorsLogged = 0;
            } else {
                logger.debug("failed to parse status response: {}", e.getMessage());
            }
        }

        if (nParseErrorsLogged < 5) {
            logger.error("failed to parse status response: {}", e.getMessage());
            nParseErrorsLogged++;
            lastParseErrorLogged = Instant.now();
            if (nParseErrorsLogged == 5) {
                logger.info("omitting further parse errors for the next 24 hours");
            }
        }
    }

    protected record PulseStatusRequestFailed(
            @NotNull Throwable throwable
    ) implements Command {}

    protected record PulseStatusRequestSuccess(
            @NotNull HttpResponse response
    ) implements Command {}
    
    protected record StrictEntity(
            @NotNull HttpEntity.Strict entity
    ) implements Command {}

    protected enum ExecuteNextPulseStatusPolling implements Command {
        INSTANCE
    }
}
