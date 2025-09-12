package com.deigmueller.uni_meter.output.device.shelly;

import com.deigmueller.uni_meter.application.WebsocketInput;
import com.deigmueller.uni_meter.application.WebsocketOutput;
import com.deigmueller.uni_meter.common.shelly.Rpc;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pekko.NotUsed;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.http.javadsl.marshallers.jackson.Jackson;
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
import scala.concurrent.duration.FiniteDuration;

import java.net.InetAddress;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

class HttpRoute extends AllDirectives {
  // Class members
  private static final InetAddress DEFAULT_ADDRESS = InetAddress.getLoopbackAddress();
  
  private final Logger logger;
  private final ActorSystem<?> system;
  private final Materializer materializer;
  private final ActorRef<OutputDevice.Command> shelly;
  private final ActorRef<WebsocketInput.Notification> websocketInput;
  private final Duration timeout = Duration.ofSeconds(5);
  private final FiniteDuration finiteTimeout = FiniteDuration.apply(5, TimeUnit.SECONDS);
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
                path("reset_data", this::onResetData),
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
                            post(() -> extractStrictEntity(finiteTimeout, strict -> {
                              logger.trace("POST RpcRequest: {}", strict.getData().utf8String());
                              return onRpcRequest(remoteAddress, Rpc.parseRequest(strict.getData().toArray()));
                            })),
                            extractWebSocketUpgrade(upgrade ->
                                  createWebsocketFlow(remoteAddress, upgrade)
                            )
                      )
                ),
                pathPrefix("rpc", () -> concat(
                      get(() ->
                            concat(
                                  path("Cloud.GetConfig", this::onCloudGetConfig),
                                  path("Cloud.SetConfig", () ->
                                        parameter(StringUnmarshallers.STRING, "config", this::onCloudSetConfig)
                                  ),
                                  path("EM.GetConfig", () ->
                                        parameterOptional(StringUnmarshallers.INTEGER, "id", id -> 
                                              onEmGetConfig(id.orElse(0)))
                                  ),
                                  path("EM.GetStatus", () ->
                                        parameterOptional(StringUnmarshallers.INTEGER, "id", id -> 
                                              onEmGetStatus(remoteAddress, id.orElse(0)))
                                  ),
                                  path("EMData.GetStatus", () ->
                                        parameterOptional(StringUnmarshallers.INTEGER, "id", id -> 
                                              onEmDataGetStatus(remoteAddress, id.orElse(0)))
                                  ),
                                  path("Script.List", () ->
                                        onScripList(remoteAddress)
                                  ),
                                  path("Script.GetCode", () ->
                                        onScriptGetCode(remoteAddress)
                                  ),
                                  path("Shelly.GetComponents", () ->
                                        onShellyGetComponents(remoteAddress)
                                  ),
                                  path("Shelly.GetConfig", () ->
                                        onShellyGetConfig(remoteAddress)
                                  ),
                                  path("Shelly.GetDeviceInfo", () ->
                                        onShellyGetDeviceInfo(remoteAddress)      
                                  ),
                                  path("Shelly.GetStatus" , () ->
                                        onShellyGetStatus(remoteAddress)
                                  ),
                                  path("Shelly.Reboot", () ->
                                        parameterOptional(StringUnmarshallers.INTEGER, "delay_ms", delayMs -> 
                                              onShellyReboot(remoteAddress, delayMs.orElse(1000)))
                                  ),
                                  path("Sys.GetConfig" , () ->
                                        onSysGetConfig(remoteAddress)
                                  ),
                                  path("Ws.GetConfig", this::onWsGetConfig),
                                  path("Ws.SetConfig", () -> 
                                        parameter(StringUnmarshallers.STRING, "config", this::onWsSetConfig)
                                  ),
                                  extractUnmatchedPath(unmatchedPath -> {
                                    logger.error("unknown RPC method: {}", unmatchedPath.substring(1));
                                    return complete(StatusCodes.NOT_FOUND);
                                  })
                            )
                      ),
                      post(() -> extractStrictEntity(finiteTimeout, strictEntity ->
                            extractUnmatchedPath(unmatchedPath -> {
                              logger.trace("POST RpcRequest /rpc/{}: {}", unmatchedPath, strictEntity.getData().utf8String());
                              return onRpcRequest(remoteAddress, Rpc.parseRequest(strictEntity.getData().toArray()));
                            })
                      )),
                      extractWebSocketUpgrade(upgrade ->
                            createWebsocketFlow(remoteAddress, upgrade)
                      )
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

  private Route onResetData() {
    return completeOKWithFuture(
          AskPattern.ask(
                shelly,
                ShellyPro3EM.ResetData::new,
                timeout,
                system.scheduler()
          ),
          Jackson.marshaller()
    );
  }

  private Route onShellyGet(@NotNull RemoteAddress remoteAddress) {
    return completeOKWithFuture(
          AskPattern.ask(
                shelly,
                (ActorRef<Rpc.GetDeviceInfoResponse> replyTo) -> new Shelly.ShellyGet(
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

  private Route onScripList(@NotNull RemoteAddress remoteAddress) {
    return completeOKWithFuture(
          AskPattern.ask(
                shelly,
                (ActorRef<Rpc.ScriptListResponse> replyTo) -> new ShellyPro3EM.ScriptList(
                      remoteAddress.getAddress().orElse(DEFAULT_ADDRESS),
                      replyTo),
                timeout,
                system.scheduler()
          ),
          Jackson.marshaller(objectMapper));
  }

  private Route onScriptGetCode(@NotNull RemoteAddress remoteAddress) {
    return completeOKWithFuture(
          AskPattern.ask(
                shelly,
                (ActorRef<Rpc.ScriptGetCodeResponse> replyTo) -> new ShellyPro3EM.ScriptGetCode(
                      remoteAddress.getAddress().orElse(DEFAULT_ADDRESS),
                      replyTo),
                timeout,
                system.scheduler()
          ),
          Jackson.marshaller(objectMapper));
  }

  private Route onShellyGetComponents(@NotNull RemoteAddress remoteAddress) {
    return completeOKWithFuture(
          AskPattern.ask(
                shelly,
                (ActorRef<Rpc.ShellyGetComponentsResponse> replyTo) -> new ShellyPro3EM.ShellyGetComponents(
                      remoteAddress.getAddress().orElse(DEFAULT_ADDRESS),
                      replyTo),
                timeout,
                system.scheduler()
          ),
          Jackson.marshaller(objectMapper));
  }

  private Route onShellyGetConfig(@NotNull RemoteAddress remoteAddress) {
    return completeOKWithFuture(
          AskPattern.ask(
                shelly,
                (ActorRef<ShellyPro3EM.ShellyConfig> replyTo) -> new ShellyPro3EM.ShellyGetConfig(
                      remoteAddress.getAddress().orElse(DEFAULT_ADDRESS),
                      replyTo),
                timeout,
                system.scheduler()
          ),
          Jackson.marshaller(objectMapper));
  }

  private Route onShellyGetDeviceInfo(@NotNull RemoteAddress remoteAddress) {
    return completeOKWithFuture(
          AskPattern.ask(
                shelly,
                (ActorRef<Rpc.GetDeviceInfoResponse> replyTo) -> new ShellyPro3EM.ShellyGetDeviceInfo(
                      remoteAddress.getAddress().orElse(DEFAULT_ADDRESS),
                      replyTo),
                timeout,
                system.scheduler()
          ),
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

  private Route onShellyReboot(@NotNull RemoteAddress remoteAddress,
                               int delayMs) {
    return completeOKWithFuture(
          AskPattern.ask(
                shelly,
                (ActorRef<Rpc.ShellyRebootResponse> replyTo) -> new ShellyPro3EM.ShellyReboot(
                      remoteAddress.getAddress().orElse(DEFAULT_ADDRESS),
                      delayMs,
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

  private Route onCloudGetConfig() {
    return completeOKWithFuture(
          AskPattern.ask(
                shelly,
                ShellyPro3EM.CloudGetConfig::new,
                timeout,
                system.scheduler()
          ),
          Jackson.marshaller(objectMapper));
  }

  private Route onCloudSetConfig(@NotNull String config) {
    Rpc.CloudSetConfigParams setConfigParams;
    try {
      setConfigParams = Rpc.getObjectMapper().readValue(config, Rpc.CloudSetConfigParams.class);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(e);
    }

    return completeOKWithFuture(
          AskPattern.ask(
                shelly,
                (ActorRef<Rpc.CloudSetConfigResponse> replyTo) -> new ShellyPro3EM.CloudSetConfig(setConfigParams, replyTo),
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
              return CompletableFuture.failedFuture(response.failure());
            } else {
              return response.status();
            }
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

  private Route onWsGetConfig() {
    return completeOKWithFuture(
          AskPattern.ask(
                shelly, 
                ShellyPro3EM.WsGetConfig::new,
                timeout,
                system.scheduler()
          ),
          Jackson.marshaller(objectMapper));
  }
  
  private Route onWsSetConfig(@NotNull String config) {
    Rpc.WsSetConfigParams setConfigParams;
    try {
      setConfigParams = Rpc.getObjectMapper().readValue(config, Rpc.WsSetConfigParams.class);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(e);
    }

    return completeOKWithFuture(
          AskPattern.ask(
                shelly,
                (ActorRef<Rpc.WsSetConfigResponse> replyTo) -> new ShellyPro3EM.WsSetConfig(setConfigParams, replyTo),
                timeout,
                system.scheduler()
          ),
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
                sourceActor -> shelly.tell(
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
