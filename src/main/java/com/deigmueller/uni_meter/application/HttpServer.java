package com.deigmueller.uni_meter.application;

import com.deigmueller.uni_meter.common.shelly.RpcException;
import com.deigmueller.uni_meter.output.BadRequestException;
import com.deigmueller.uni_meter.output.TemporaryNotAvailableException;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.*;
import org.apache.pekko.http.javadsl.Http;
import org.apache.pekko.http.javadsl.ServerBinding;
import org.apache.pekko.http.javadsl.ServerBuilder;
import org.apache.pekko.http.javadsl.model.HttpResponse;
import org.apache.pekko.http.javadsl.model.StatusCodes;
import org.apache.pekko.http.javadsl.server.*;
import org.apache.pekko.http.javadsl.settings.ServerSettings;
import org.apache.pekko.http.scaladsl.model.headers.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jetbrains.annotations.NotNull;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

public class HttpServer extends AbstractBehavior<HttpServer.Command> {
  // Instance members
  private final Logger logger;
  private final List<Route> registeredRoutes = new ArrayList<>();
  private final String bindInterface;
  private final int bindPort;
  private ServerBinding httpServerBinding;
  private volatile Route innerRoute;
  
  public static Behavior<Command> create(@NotNull String bindInterface,
                                         int bindPort) {
    return Behaviors.setup(context -> new HttpServer(context, bindInterface, bindPort));
  }
  
  protected HttpServer(@NotNull ActorContext<Command> context,
                       @NotNull String bindInterface,
                       int bindPort) {
    super(context);
    
    this.logger = LoggerFactory.getLogger("uni-meter.http.port-" + bindPort);
    
    this.bindInterface = bindInterface;
    this.bindPort = bindPort;
  }

  @Override
  public @NotNull Receive<Command> createReceive() {
    return newReceiveBuilder().build();
  }

  @Override
  public @NotNull ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onMessage(RegisterRoute.class, this::onRegisterRoute)
          .onMessage(NotifyBindFailed.class, this::onNotifyBindFailed)
          .onMessage(NotifyBindSuccess.class, this::onNotifyBindSuccess)
          .onMessage(RetryStartHttpServer.class, this::onRetryStartHttpServer);          
  }
  
  private @NotNull Behavior<Command> onRegisterRoute(@NotNull RegisterRoute message) {
    logger.trace("HttpServer.onRegisterRoute()");
    
    if (! registeredRoutes.contains(message.route())) {
      registeredRoutes.add(message.route());
      
      Route newRoute = registeredRoutes.get(0);
      for (int i=1; i<registeredRoutes.size(); i++) {
        newRoute = newRoute.orElse(registeredRoutes.get(i));
      }
      
      innerRoute = newRoute;

      if (registeredRoutes.size() == 1) {
        start();
      }
    }
    
    return Behaviors.same();
  }
  
  private @NotNull Behavior<Command> onNotifyBindFailed(@NotNull NotifyBindFailed message) {
    logger.trace("HttpServer.onNotifyBindFailed()");

    logger.error("failed to bind HTTP server: {}", message.throwable().getMessage());

    getContext().getSystem().scheduler().scheduleOnce(
          getContext().getSystem().settings().config().getDuration("uni-meter.http-server.bind-retry-backoff"),
          () -> getContext().getSelf().tell(RetryStartHttpServer.INSTANCE),
          getContext().getSystem().executionContext());

    return Behaviors.same();
  }

  /**
   * Handle the successful binding of the HTTP server
   * @param message Notification that the HTTP server has been successfully bound
   * @return Same behavior
   */
  private @NotNull Behavior<Command> onNotifyBindSuccess(@NotNull NotifyBindSuccess message) {
    logger.trace("HttpServer.onNotifyBindSuccess()");

    logger.info("HTTP server is listening on {}", message.binding().localAddress());
    httpServerBinding = message.binding();

    return Behaviors.same();
  }

  /**
   * Handle the request to retry starting the HTTP server
   * @param message Request to retry starting the HTTP server
   * @return Same behavior
   */
  private @NotNull Behavior<Command> onRetryStartHttpServer(@NotNull RetryStartHttpServer message) {
    logger.trace("HttpServer.onRetryStartHttpServer()");
    
    start();

    return Behaviors.same();
  }

  /**
   * Start the HTTP server
   */
  private void start() {
    logger.trace("HttpServer.start()");
    assert httpServerBinding == null;
    
    if (! registeredRoutes.isEmpty()) {
      final Http http = Http.get(getContext().getSystem());

      ServerSettings serverSettings = ServerSettings
            .create(Adapter.toClassic(getContext().getSystem()))
            .withServerHeader(Optional.of(Server.apply("ShellyHTTP/1.0.0")));
      
      ServerBuilder serverBuilder = http.newServerAt(bindInterface, bindPort)
            .withSettings(serverSettings);
      
  //    if (useHttps()) {
  //      serverBuilder = serverBuilder.enableHttps(createHttpsConnectionContext());
  //    }
  
      serverBuilder
            .bind(new RouteContainer().createMainRoute(innerRoute))
            .whenComplete((binding, throwable) -> {
              if (throwable != null) {
                getContext().getSelf().tell(new NotifyBindFailed(throwable));
              } else {
                getContext().getSelf().tell(new NotifyBindSuccess(binding));
              }
            });
    }

//      CompletionStage<ServerBinding> serverBindingFuture =
//            serverSource
//                  .to(Sink.foreach(connection -> {
//                    System.out.println("Accepted new connection from " + connection.remoteAddress());
//              
//                    connection.handleWith(new RouteContainer().createMainRoute(innerRoute), Materializer.createMaterializer(getContext()));
//
//                    // this is equivalent to
//                    //connection.handleWith(Flow.of(HttpRequest.class).map(requestHandler), materializer);
//                  }))
//                  .run(Materializer.createMaterializer(getContext()));
//    }
  }
  
  private Route getInnerRoute() {
    return innerRoute;
  }
  
  public interface Command {}
  
  public record RegisterRoute(
        @NotNull Route route
  ) implements Command {}

  protected record NotifyBindFailed(
        @NotNull Throwable throwable
  ) implements Command {}

  protected record NotifyBindSuccess(
        @NotNull ServerBinding binding
  ) implements Command {}

  protected enum RetryStartHttpServer implements Command {
    INSTANCE
  }

  protected class RouteContainer extends AllDirectives {
    public @NotNull Route createMainRoute(@NotNull Route innerRoute) {
      return handleExceptions(createExceptionHandler(), () ->
            handleRejections(createRejectionHandler(), 
                  HttpServer.this::getInnerRoute
            )
      );
    }

    /**
     * Create the exception handler
     * @return ExceptionHandler instance
     */
    private ExceptionHandler createExceptionHandler() {
      return ExceptionHandler.newBuilder()
            .match(NoSuchElementException.class, e -> {
              logger.debug("no such element: {}", e.getMessage());
              return complete(StatusCodes.NOT_FOUND, e.getMessage());
            })
            .match(RpcException.class, e -> {
              logger.debug("rpc exception: {}", e.getMessage());
              return complete(StatusCodes.SERVICE_UNAVAILABLE, e.getMessage());
            })
            .match(TemporaryNotAvailableException.class, e -> {
              logger.debug("temporary not available: {}", e.getMessage());
              return complete(StatusCodes.SERVICE_UNAVAILABLE, e.getMessage());
            })
            .match(BadRequestException.class, e -> {
              logger.debug("bad request: {}", e.getMessage());
              return complete(StatusCodes.BAD_REQUEST, e.getMessage());
            })
            .matchAny(e -> {
              logger.error("exception in HTTP server: {}", e.getMessage());
              return complete(HttpResponse.create().withStatus(500));
            })
            .build();
    }

    /**
     * Create the rejection handler
     * @return RejectionHandler instance
     */
    private RejectionHandler createRejectionHandler() {
      return RejectionHandler.newBuilder()
            .handleNotFound(
                  extractUnmatchedPath(path -> {
                    logger.debug("requested url {} not found", path);
                    return complete(StatusCodes.NOT_FOUND, "Resource not found!");
                  })
            )
            .build();
    }
  }
}
