package com.deigmueller.uni_meter.input.device.common.http;

import com.deigmueller.uni_meter.input.InputDevice;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import lombok.AccessLevel;
import lombok.Getter;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.http.javadsl.Http;
import org.apache.pekko.stream.Materializer;
import org.jetbrains.annotations.NotNull;

@Getter(AccessLevel.PROTECTED)
public abstract class HttpInputDevice extends InputDevice {
    // Instance members
    private final Http http = Http.get(getContext().getSystem());
    private final Materializer materializer = Materializer.createMaterializer(getContext());
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String url = getConfig().getString("url");

    protected HttpInputDevice(@NotNull ActorContext<Command> context, 
                              @NotNull ActorRef<OutputDevice.Command> outputDevice, 
                              @NotNull Config config) {
        super(context, outputDevice, config);
    }
}
