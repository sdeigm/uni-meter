package com.deigmueller.uni_meter.output.device.shelly;

import com.deigmueller.uni_meter.application.WebsocketInput;
import com.deigmueller.uni_meter.application.WebsocketOutput;
import com.deigmueller.uni_meter.common.shelly.Rpc;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pekko.NotUsed;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.http.javadsl.marshallers.jackson.Jackson;
import org.apache.pekko.http.javadsl.model.HttpEntity;
import org.apache.pekko.http.javadsl.model.RemoteAddress;
import org.apache.pekko.http.javadsl.model.StatusCodes;
import org.apache.pekko.http.javadsl.model.ws.Message;
import org.apache.pekko.http.javadsl.model.ws.WebSocketUpgrade;
import org.apache.pekko.http.javadsl.server.AllDirectives;
import org.apache.pekko.http.javadsl.server.Route;
import org.apache.pekko.http.javadsl.unmarshalling.StringUnmarshallers;
import org.apache.pekko.stream.Materializer;
import org.apache.pekko.stream.javadsl.Sink;
import org.apache.pekko.stream.javadsl.Source;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.time.Duration;
import java.util.UUID;

class HttpRoute extends AllDirectives {
  // Class members
  private static final InetAddress DEFAULT_ADDRESS = InetAddress.getLoopbackAddress();
  
  private final Logger logger;
  private final ActorSystem<?> system;
  private final Materializer materializer;
  private final ActorRef<OutputDevice.Command> shelly;
  private final ActorRef<WebsocketInput.Notification> websocketInput;
  private final Duration timeout = Duration.ofSeconds(5);
  private final ObjectMapper objectMapper = Rpc.createObjectMapper();

  public HttpRoute(Logger logger, 
                   ActorSystem<?> system, 
                   Materializer materializer,
                   ActorRef<OutputDevice.Command> shelly,
                   ActorRef<WebsocketInput.Notification> websocketInput) {
    this.logger = logger;
    this.system = system;
    this.materializer = materializer;
    this.shelly = shelly;
    this.websocketInput = websocketInput;
  }

  public Route createRoute() {
    return extractClientIP(remoteAddress -> 
          concat(
                path("shelly", () -> 
                      get(() -> onShellyGet(remoteAddress))
                ), 
                path("settings", () -> 
                      get(() -> onSettingsGet(remoteAddress))
                ), 
                path("status", () -> 
                      get(() -> onStatusGet(remoteAddress))
                ),
                path("rpc", () ->
                      concat(
                            post(() -> extractEntity(entity -> {
                              HttpEntity.Strict strict = (HttpEntity.Strict) entity;
                              strict.discardBytes(materializer);

                              logger.trace("POST RpcRequest: {}", strict.getData().utf8String());
                              return onRpcRequest(remoteAddress, Rpc.parseRequest(strict.getData().toArray()));
                            })),
                            extractWebSocketUpgrade(upgrade ->
                                  createWebsocketFlow(remoteAddress, upgrade)
                            )
                      )
                ),
                pathPrefix("rpc", () -> 
                      get(() ->
                            concat(
                                  path("Shelly.GetStatus" , () -> 
                                        onShellyGetStatus(remoteAddress)
                                  ),
                                  path("Sys.GetConfig" , () ->
                                        onSysGetConfig(remoteAddress)
                                  ),
                                  path("EM.GetConfig", () ->
                                        parameterOptional(StringUnmarshallers.INTEGER, "id", id -> onEmGetConfig(id.orElse(0)))
                                  ),
                                  path("EM.GetStatus", () ->
                                        parameterOptional(StringUnmarshallers.INTEGER, "id", id -> onEmGetStatus(remoteAddress, id.orElse(0)))
                                  ),
                                  path("EMData.GetStatus", () ->
                                        parameterOptional(StringUnmarshallers.INTEGER, "id", id -> onEmDataGetStatus(remoteAddress, id.orElse(0)))
                                  ),
                                  extractUnmatchedPath(unmatchedPath -> {
                                    logger.error("unknown RPC method: {}", unmatchedPath.substring(1));
                                    return complete(StatusCodes.NOT_FOUND);
                                  })
                            )
                      )
                ),
                extractUnmatchedPath(unmatchedPath -> {
                  logger.debug("unhandled HTTP path: {}", unmatchedPath.substring(1));
                  return complete(StatusCodes.NOT_FOUND);
                })
          )
    );
  }

  private Route onShellyGet(@NotNull RemoteAddress remoteAddress) {
    return completeOKWithFuture(
          AskPattern.ask(
                shelly,
                (ActorRef<Shelly.ShellyInfo> replyTo) -> new Shelly.ShellyGet(
                      remoteAddress.getAddress().orElse(DEFAULT_ADDRESS), 
                      replyTo),
                timeout, 
                system.scheduler()), 
          Jackson.marshaller(objectMapper));
  }

  private Route onSettingsGet(@NotNull RemoteAddress remoteAddress) {
    return completeOKWithFuture(
          AskPattern.ask(
                shelly,
                (ActorRef<Shelly.Settings> replyTo) -> new Shelly.SettingsGet(
                      remoteAddress.getAddress().orElse(DEFAULT_ADDRESS), 
                      replyTo), 
                timeout, 
                system.scheduler()), 
          Jackson.marshaller(objectMapper));
  }

  private Route onStatusGet(@NotNull RemoteAddress remoteAddress) {
    return completeOKWithFuture(
          AskPattern.ask(
                shelly,
                (ActorRef<Shelly.Status> replyTo) -> new Shelly.StatusGet(
                      remoteAddress.getAddress().orElse(DEFAULT_ADDRESS), 
                      replyTo),
                timeout, 
                system.scheduler()), 
          Jackson.marshaller(objectMapper));
  }

  private Route onRpcRequest(@NotNull RemoteAddress remoteAddress,
                             @NotNull Rpc.Request request) {
    return completeOKWithFuture(
          AskPattern.ask(
                shelly, 
                (ActorRef<Rpc.ResponseFrame> replyTo) -> 
                      new Shelly.HttpRpcRequest(
                            remoteAddress.getAddress().orElse(DEFAULT_ADDRESS), 
                            request, 
                            replyTo), 
                timeout, 
                system.scheduler()), 
          Jackson.marshaller(objectMapper));
  }

  private Route onShellyGetStatus(@NotNull RemoteAddress remoteAddress) {
    return completeOKWithFuture(
          AskPattern.ask(
                shelly,
                (ActorRef<Shelly.Status> replyTo) -> new ShellyPro3EM.ShellyGetStatus(
                      remoteAddress.getAddress().orElse(DEFAULT_ADDRESS),
                      replyTo),
                timeout,
                system.scheduler()
          ),
          Jackson.marshaller(objectMapper));
  }
  
  private Route onSysGetConfig(@NotNull RemoteAddress remoteAddress) {
    return completeOKWithFuture(
          AskPattern.ask(
                shelly,
                (ActorRef<Rpc.SysGetConfigResponse> replyTo) -> new ShellyPro3EM.SysGetConfig(
                      remoteAddress.getAddress().orElse(DEFAULT_ADDRESS), 
                      replyTo), 
                timeout, 
                system.scheduler()
          ),
          Jackson.marshaller(objectMapper));
  }

  private Route onEmGetConfig(int id) {
    return completeOKWithFuture(
          AskPattern.ask(
                shelly,
                (ActorRef<ShellyPro3EM.EmGetConfigOrFailureResponse> replyTo) -> new ShellyPro3EM.EmGetConfig(id, replyTo),
                timeout,
                system.scheduler()
          ).thenApply(response -> {
            if (response.failure() != null) {
              throw response.failure();
            }

            return response.status();
          }),
          Jackson.marshaller(objectMapper));
  }

  private Route onEmGetStatus(@NotNull RemoteAddress remoteAddress,
                              int id) {
    return completeOKWithFuture(
          AskPattern.ask(
                shelly, 
                (ActorRef<ShellyPro3EM.EmGetStatusOrFailureResponse> replyTo) -> 
                      new ShellyPro3EM.EmGetStatus(
                            remoteAddress.getAddress().orElse(DEFAULT_ADDRESS), 
                            id, 
                            replyTo), 
                timeout, 
                system.scheduler()
          ).thenApply(response -> {
            if (response.failure() != null) {
              throw response.failure();
            }
            
            return response.status();
          }),
          Jackson.marshaller(objectMapper));
  }

  private Route onEmDataGetStatus(@NotNull RemoteAddress remoteAddress,
                                  int id) {
    return completeOKWithFuture(
          AskPattern.ask(
                shelly,
                (ActorRef<ShellyPro3EM.EmDataGetStatusOrFailureResponse> replyTo) -> 
                      new ShellyPro3EM.EmDataGetStatus(
                            remoteAddress.getAddress().orElse(DEFAULT_ADDRESS), 
                            id, 
                            replyTo),
                timeout,
                system.scheduler()
          ).thenApply(response -> {
            if (response.failure() != null) {
              throw response.failure();
            }

            return response.status();
          }),
          Jackson.marshaller(objectMapper));
  }
  
  /**
   * Create a Pekko flow which handles the WebSocket connection
   * @return Route of the WebSocket connection
   */
  private Route createWebsocketFlow(@NotNull RemoteAddress remoteAddress,
                                    @NotNull WebSocketUpgrade upgrade) {
    final String connectionId = UUID.randomUUID().toString();
    
    final Logger connectionLogger = LoggerFactory.getLogger("uni-meter.websocket." + connectionId);

    Source<Message, NotUsed> source = 
          WebsocketOutput.createSource(
                connectionLogger,
                materializer, 
                (sourceActor) -> shelly.tell(
                      new Shelly.WebsocketOutputOpened(connectionId, remoteAddress.getAddress().orElse(DEFAULT_ADDRESS), sourceActor)));

    Sink<Message, NotUsed> sink = 
          WebsocketInput.createSink(
                connectionLogger,
                connectionId,
                remoteAddress.getAddress().orElse(DEFAULT_ADDRESS),
                materializer, 
                websocketInput);

    return complete(upgrade.handleMessagesWith(sink, source));
  }
}
