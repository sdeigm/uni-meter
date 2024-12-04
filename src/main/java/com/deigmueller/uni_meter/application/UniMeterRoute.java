/*
 * Copyright (C) 2018-2023 layline.io GmbH <http://www.layline.io>
 */

package com.deigmueller.uni_meter.application;

import lombok.AllArgsConstructor;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.http.javadsl.server.AllDirectives;
import org.apache.pekko.http.javadsl.server.Route;
import com.deigmueller.uni_meter.application.Version;

@AllArgsConstructor(staticName = "create")
public class UniMeterRoute extends AllDirectives {
  private final ActorRef<UniMeter.Command> uniMeter;
  
  public Route createRoute() {
    return path("/", () ->
          get(this::onRootGet)
    );
  }
  
  private Route onRootGet() {
    return complete("UniMeter version " + Version.getVersion());
  }
}
