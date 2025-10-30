package com.deigmueller.uni_meter.application;

import com.deigmueller.uni_meter.common.shelly.Rpc;
import com.deigmueller.uni_meter.output.OutputDevice;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.http.javadsl.marshallers.jackson.Jackson;
import org.apache.pekko.http.javadsl.model.StatusCodes;
import org.apache.pekko.http.javadsl.server.AllDirectives;
import org.apache.pekko.http.javadsl.server.Route;
import org.apache.pekko.http.javadsl.unmarshalling.StringUnmarshallers;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Map;

public class UniMeterHttpRoute extends AllDirectives {
  private final ActorSystem<?> actorSystem;
  private final ActorRef<OutputDevice.Command> outputDevice;
  
  public UniMeterHttpRoute(@NotNull ActorSystem<?> actorSystem,
                           @NotNull ActorRef<OutputDevice.Command> outputDevice) {
    this.actorSystem = actorSystem;
    this.outputDevice = outputDevice;
  }

  public Route createRoute() {
    return concat(
          path("", () -> get(this::onGetStatus)),
          pathPrefix("api", () -> concat(
                path("no_charge", () ->
                      get(() ->
                            parameterOptional(StringUnmarshallers.INTEGER, "seconds", seconds ->
                                  onNoCharge(seconds.orElse(Integer.MAX_VALUE))
                            )
                      )
                ),
                path("no_discharge", () ->
                      get(() ->
                            parameterOptional(StringUnmarshallers.INTEGER, "seconds", seconds ->
                                  onNoDischarge(seconds.orElse(Integer.MAX_VALUE))
                            )
                      )
                ),
                path("set_parameters", () -> 
                      get(() -> parameterMap(this::onSetParameters))
                ),
                path("switch_on", () -> 
                      get(this::onSwitchOn)
                ),
                path("switch_off", () -> 
                      get(() ->
                            parameterOptional(StringUnmarshallers.INTEGER, "seconds", seconds -> 
                                  onSwitchOff(seconds.orElse(Integer.MAX_VALUE))
                            )
                      )
                )
          )
    ));
  }

  private Route onGetStatus() {
    return completeOKWithFuture(
          AskPattern.ask(
                outputDevice,
                OutputDevice.GetStatus::new,
                Duration.ofSeconds(15),
                actorSystem.scheduler()
          ),
          Jackson.marshaller()
    );
  }

  private Route onNoCharge(int seconds) {
    return completeWithFutureStatus(
          AskPattern.ask(
                outputDevice,
                (ActorRef<OutputDevice.NoChargeResponse> replyTo) -> new OutputDevice.NoCharge(Math.max(1, seconds), replyTo),
                Duration.ofSeconds(15),
                actorSystem.scheduler()
          ).thenApply(response -> StatusCodes.OK)
    );
  }

  private Route onNoDischarge(int seconds) {
    return completeWithFutureStatus(
          AskPattern.ask(
                outputDevice,
                (ActorRef<OutputDevice.NoDischargeResponse> replyTo) -> new OutputDevice.NoDischarge(Math.max(1, seconds), replyTo),
                Duration.ofSeconds(15),
                actorSystem.scheduler()
          ).thenApply(response -> StatusCodes.OK)
    );
  }

  private Route onSwitchOn() {
    return completeWithFutureStatus(
          AskPattern.ask(
                outputDevice, 
                OutputDevice.SwitchOn::new,
                Duration.ofSeconds(15),
                actorSystem.scheduler()
          ).thenApply(response -> StatusCodes.OK)
    );
  }
  
  private Route onSwitchOff(int seconds) {
    return completeWithFutureStatus(
          AskPattern.ask(
                outputDevice,
                (ActorRef<OutputDevice.SwitchOffResponse> replyTo) -> 
                      new OutputDevice.SwitchOff(Math.max(1, seconds), replyTo), 
                Duration.ofSeconds(15),
                actorSystem.scheduler()
          ).thenApply(response -> StatusCodes.OK)
    );
  }
  
  private Route onSetParameters(@NotNull Map<String, String> parameters) {
    return completeOKWithFuture(
          AskPattern.ask(
                outputDevice,
                (ActorRef<OutputDevice.SetParametersResponse> replyTo) -> 
                      new OutputDevice.SetParameters(parameters, replyTo),
                Duration.ofSeconds(15),
                actorSystem.scheduler()
          ).thenApply(response -> {
            if (response.failure() instanceof RuntimeException runtimeException) {
              throw runtimeException;
            }
            if (response.failure() instanceof Exception) {
              throw new RuntimeException(response.failure());
            }
            return response.parameter();
          }),
          Jackson.marshaller(Rpc.getObjectMapper())
    );
  }
}
