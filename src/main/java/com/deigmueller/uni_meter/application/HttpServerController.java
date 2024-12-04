/*
 * Copyright (C) 2018-2023 layline.io GmbH <http://www.layline.io>
 */

package com.deigmueller.uni_meter.application;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.*;
import org.apache.pekko.http.javadsl.server.Route;

import org.jetbrains.annotations.NotNull;
import java.util.HashMap;
import java.util.Map;

public class HttpServerController extends AbstractBehavior<HttpServerController.Command> {
  // Instance members
  private final Map<Integer, ActorRef<HttpServer.Command>> servers = new HashMap<>();
  private final String bindInterface = "0.0.0.0";
  
  public static Behavior<Command> create() {
    return Behaviors.setup(HttpServerController::new);
  }

  protected HttpServerController(@NotNull ActorContext<Command> context) {
    super(context);
  }

  @Override
  public Receive<Command> createReceive() {
    return newReceiveBuilder().build();
  }
  
  @Override
  public ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onMessage(RegisterHttpRoute.class, this::onRegisterHttpRoute);
  }
  
  private @NotNull Behavior<Command> onRegisterHttpRoute(@NotNull RegisterHttpRoute command) {
    ActorRef<HttpServer.Command> server = servers.get(command.port);
    if (server == null) {
      server = getContext().spawn(HttpServer.create(bindInterface, command.port), "http-server-" + command.port);
      getContext().watch(server);
      
      servers.put(command.port, server);
    }
    
    server.tell(new HttpServer.RegisterRoute(command.route));
    
    return this;
  }

  public interface Command {}
  
  public record RegisterHttpRoute(
        @NotNull int port,
        @NotNull Route route
  ) implements Command {}
}
