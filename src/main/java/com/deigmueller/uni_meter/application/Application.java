/*
 * Copyright (C) 2018-2023 layline.io GmbH <http://www.layline.io>
 */

package com.deigmueller.uni_meter.application;


import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.commons.lang3.StringUtils;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Application {
  public static final Logger LOGGER = LoggerFactory.getLogger("uni-meter");
  
    public static void main(String[] args) {
      Config config = ConfigFactory.load();
      
      logStartupBanner();
      
      try {
        LOGGER.info("initializing actor system");
        ActorSystem<UniMeter.Command> actorSystem = ActorSystem.create(
              Behaviors.setup(context -> UniMeter.create()), "uni-meter", config);
        
        actorSystem.getWhenTerminated().whenComplete((done, throwable) -> {
          LOGGER.info("actor system terminated");
        });
        
        Thread.sleep(1000);
      } catch (Exception e) {
        LOGGER.error("failed to initialize the actor system", e);
      }
    }

  private static void logStartupBanner() {
    String product   = "# Universal electric meter converter " + Version.getVersion() + " (" + Version.getBuildTime() + ") #";
    String hLine = StringUtils.repeat("#", product.length());

    LOGGER.info(hLine);
    LOGGER.info(product);
    LOGGER.info(hLine);
  }
}
