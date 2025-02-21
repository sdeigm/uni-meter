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
import org.apache.pekko.http.javadsl.model.StatusCodes;
import org.apache.pekko.http.javadsl.model.ws.Message;
import org.apache.pekko.http.javadsl.model.ws.WebSocketUpgrade;
import org.apache.pekko.http.javadsl.server.AllDirectives;
import org.apache.pekko.http.javadsl.server.Route;
import org.apache.pekko.http.javadsl.unmarshalling.StringUnmarshallers;
import org.apache.pekko.stream.Materializer;
import org.apache.pekko.stream.javadsl.Sink;
import org.apache.pekko.stream.javadsl.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.UUID;

class HttpRoute extends AllDirectives {
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
    return concat(
          path("shelly", () -> 
                get(this::onShellyGet)
          ), 
          path("settings", () -> 
                get(this::onSettingsGet)
          ), 
          path("status", () -> 
                get(this::onStatusGet)
          ),
          path("rpc", () ->
                concat(
                      post(() -> extractEntity(entity -> {
                        HttpEntity.Strict strict = (HttpEntity.Strict) entity;
                        strict.discardBytes(materializer);

                        logger.trace("POST RpcRequest: {}", strict.getData().utf8String());
                        return onRpcRequest(Rpc.parseRequest(strict.getData().toArray()));
                      })),
                      extractWebSocketUpgrade(upgrade -> {
                        logger.trace("incoming websocket upgrade");
                        return createWebsocketFlow(upgrade);
                      })
                )
          ),
          pathPrefix("rpc", () -> 
                get(() ->
                      concat(
                            path("Shelly.GetStatus" ,
                                  this::onShellyGetStatus
                            ),
                            path("Sys.GetConfig" ,  
                                  this::onSysGetConfig
                            ),
                            path("EM.GetConfig", () ->
                                  parameterOptional(StringUnmarshallers.INTEGER, "id", id -> onEmGetConfig(id.orElse(0)))
                            ),
                            path("EM.GetStatus", () ->
                                  parameterOptional(StringUnmarshallers.INTEGER, "id", id -> onEmGetStatus(id.orElse(0)))
                            ),
                            path("EMData.GetStatus", () ->
                                  parameterOptional(StringUnmarshallers.INTEGER, "id", id -> onEmDataGetStatus(id.orElse(0)))
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
    );
  }

  private Route onShellyGet() {
    return completeOKWithFuture(AskPattern.ask(shelly, Shelly.ShellyGet::new, timeout, system.scheduler()), Jackson.marshaller(objectMapper));
  }

  private Route onSettingsGet() {
    return completeOKWithFuture(AskPattern.ask(shelly, Shelly.SettingsGet::new, timeout, system.scheduler()), Jackson.marshaller(objectMapper));
  }

  private Route onStatusGet() {
    return completeOKWithFuture(AskPattern.ask(shelly, Shelly.StatusGet::new, timeout, system.scheduler()), Jackson.marshaller(objectMapper));
  }

  private Route onRpcRequest(Rpc.Request request) {
    return completeOKWithFuture(AskPattern.ask(
                shelly, 
                (ActorRef<Rpc.ResponseFrame> replyTo) -> new Shelly.HttpRpcRequest(request, replyTo), timeout, 
                system.scheduler()
          ), 
          Jackson.marshaller(objectMapper));
  }

  private Route onShellyGetStatus() {
    return completeOKWithFuture(
          AskPattern.ask(
                shelly,
                ShellyPro3EM.ShellyGetStatus::new,
                timeout,
                system.scheduler()
          ),
          Jackson.marshaller(objectMapper));
  }


  private Route onSysGetConfig() {
    return completeOKWithFuture(
          AskPattern.ask(
                shelly, 
                ShellyPro3EM.SysGetConfig::new, 
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

  private Route onEmGetStatus(int id) {
    return completeOKWithFuture(
          AskPattern.ask(
                shelly, 
                (ActorRef<ShellyPro3EM.EmGetStatusOrFailureResponse> replyTo) -> new ShellyPro3EM.EmGetStatus(id, replyTo), 
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

  private Route onEmDataGetStatus(int id) {
    return completeOKWithFuture(
          AskPattern.ask(
                shelly,
                (ActorRef<ShellyPro3EM.EmDataGetStatusOrFailureResponse> replyTo) -> new ShellyPro3EM.EmDataGetStatus(id, replyTo),
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
   *
   * @return Route of the WebSocket connection
   */
  private Route createWebsocketFlow(WebSocketUpgrade upgrade) {
    final String connectionId = UUID.randomUUID().toString();
    
    final Logger connectionLogger = LoggerFactory.getLogger("uni-meter.websocket." + connectionId);

    Source<Message, NotUsed> source = 
          WebsocketOutput.createSource(
                connectionLogger,
                materializer, 
                (sourceActor) -> shelly.tell(new Shelly.WebsocketOutputOpened(connectionId, sourceActor)));

    Sink<Message, NotUsed> sink = 
          WebsocketInput.createSink(
                connectionLogger,
                connectionId, 
                materializer, 
                websocketInput);

    return complete(upgrade.handleMessagesWith(sink, source));
  }
}
