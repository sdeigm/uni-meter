package com.deigmueller.uni_meter.output.device.eco_tracker;

import com.deigmueller.uni_meter.common.shelly.Rpc;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.http.javadsl.marshallers.jackson.Jackson;
import org.apache.pekko.http.javadsl.model.RemoteAddress;
import org.apache.pekko.http.javadsl.model.StatusCodes;
import org.apache.pekko.http.javadsl.server.AllDirectives;
import org.apache.pekko.http.javadsl.server.Route;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.net.InetAddress;
import java.time.Duration;

public class HttpRoute extends AllDirectives {
  // Class members
  private static final InetAddress DEFAULT_ADDRESS = InetAddress.getLoopbackAddress();

  // Instance members
  private final Logger logger;
  private final Duration timeout = Duration.ofSeconds(5);
  private final ActorSystem<?> system;
  private final ActorRef<OutputDevice.Command> ecoTracker;
  private final ObjectMapper objectMapper = Rpc.createObjectMapper();

  public HttpRoute(Logger logger,
                   ActorSystem<?> system,
                   ActorRef<OutputDevice.Command> ecoTracker) {
    this.logger = logger;
    this.system = system;
    this.ecoTracker = ecoTracker;
  }
  
  public Route createRoute() {
    return extractClientIP(remoteAddress ->
          concat(
                pathPrefix("v1", () -> concat(
                      path("json", () -> concat(
                            get(() -> {
                              return onV1JsonGet(remoteAddress);
                            })
                      ))
                )),
                extractUnmatchedPath(unmatchedPath ->
                      extractMethod(method -> {
                        logger.debug("unhandled HTTP method {} path: {}", method.name(), unmatchedPath.substring(1));
                        return complete(StatusCodes.NOT_FOUND);
                      })
                )
                
          )
    );
  }

  private Route onV1JsonGet(@NotNull RemoteAddress remoteAddress) {
    return completeOKWithFuture(
          AskPattern.ask(
                ecoTracker,
                (ActorRef<EcoTracker.V1GetJsonResponse> replyTo) -> new EcoTracker.V1GetJson(
                      remoteAddress.getAddress().orElse(DEFAULT_ADDRESS),
                      replyTo),
                timeout,
                system.scheduler()),
          Jackson.marshaller(objectMapper));
  }
}
