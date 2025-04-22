/*
 * Copyright (C) 2018-2023 layline.io GmbH <http://www.layline.io>
 */

package com.deigmueller.uni_meter.application;

import com.deigmueller.uni_meter.output.OutputDevice;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.http.javadsl.model.StatusCodes;
import org.apache.pekko.http.javadsl.server.AllDirectives;
import org.apache.pekko.http.javadsl.server.Route;
import org.apache.pekko.http.javadsl.unmarshalling.StringUnmarshallers;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;

public class UniMeterHttpRoute extends AllDirectives {
  private final ActorSystem<?> actorSystem;
  private final ActorRef<OutputDevice.Command> outputDevice;
  
  public UniMeterHttpRoute(@NotNull ActorSystem<?> actorSystem,
                           @NotNull ActorRef<OutputDevice.Command> outputDevice) {
    this.actorSystem = actorSystem;
    this.outputDevice = outputDevice;
  }

  public Route createRoute() {
    return pathPrefix("api", () -> concat(
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
    ));
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
                (ActorRef<OutputDevice.SwitchOffResponse> replyTo) -> new OutputDevice.SwitchOff(Math.max(1, seconds), replyTo), 
                Duration.ofSeconds(15),
                actorSystem.scheduler()
          ).thenApply(response -> StatusCodes.OK)
    );
  }
}
