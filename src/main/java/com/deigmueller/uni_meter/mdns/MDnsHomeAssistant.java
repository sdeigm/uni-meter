package com.deigmueller.uni_meter.mdns;

import com.deigmueller.uni_meter.common.utils.NetUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.apache.pekko.http.javadsl.Http;
import org.apache.pekko.http.javadsl.model.*;
import org.apache.pekko.http.javadsl.model.headers.HttpCredentials;
import org.apache.pekko.stream.Materializer;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class MDnsHomeAssistant extends MDnsKind {
  // Class members
  public static final String TYPE = "homeassistant";
  private final static Logger LOGGER = LoggerFactory.getLogger("uni-meter.mdns.ha");
  private static final ObjectMapper MAPPER = new ObjectMapper();
  
  // Instance members
  private final Http http = Http.get(getContext().getSystem());
  private final String url = getConfig().getString("url");
  private final HttpCredentials credentials = HttpCredentials.createOAuth2BearerToken(getConfig().getString("access-token"));
  private final Materializer materializer = Materializer.createMaterializer(getContext());
  private final Set<RegisterService> pendingRegistrations = new HashSet<>();
  private boolean registrationRunning = false;
  private boolean pyscriptAddonAvailable = false;

  public static Behavior<Command> create(@NotNull Config config) {
    return Behaviors.setup(context -> new MDnsHomeAssistant(context, config));
  }

  public MDnsHomeAssistant(@NotNull ActorContext<Command> context,
                           @NotNull Config config) {
    super(context, config);
    
    LOGGER.debug("using url {}", config.getString("url"));
    LOGGER.debug("using access token {}", config.getString("access-token"));
    
    detectPyscriptService();
  }

  @Override
  public ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onMessage(ServiceDetectionSucceeded.class, this::onServiceDetectionSucceeded)
          .onMessage(ServiceDetectionFailed.class, this::onServiceDetectionFailed)
          .onMessage(ServiceRegistrationSucceeded.class, this::onServiceRegistrationSucceeded)
          .onMessage(ServiceRegistrationFailed.class, this::onServiceRegistrationFailed);
  }

  /**
   * Handle the successful detection of available services
   * @param message Message containing the result of the web request
   * @return Same behavior
   */
  private Behavior<Command> onServiceDetectionSucceeded(ServiceDetectionSucceeded message) {
    LOGGER.trace("MDnsHomeAssistant.onServiceDetectionSucceeded");
    
    return Behaviors.same();
  }

  /**
   * Handles the failure to detect the available services.
   * @param message the message containing details of the service detection failure, including the associated throwable.
   * @return the same behavior to continue processing subsequent messages.
   */
  private Behavior<Command> onServiceDetectionFailed(ServiceDetectionFailed message) {
    LOGGER.trace("MDnsHomeAssistant.onServiceDetectionFailed");
    return Behaviors.same();
  }

  /**
   * Handle a succeeded service registration request
   * @param message Message containing the result of the web request
   * @return Same behavior
   */
  protected Behavior<Command> onServiceRegistrationSucceeded(@NotNull ServiceRegistrationSucceeded message) {
    LOGGER.trace("MDnsHomeAssistant.onServiceRegistrationSucceeded()");
    
    registrationRunning = false;
    
    if (message.response.status() == StatusCodes.OK) {
      LOGGER.info("successfully registered mdns service {}", message.registerService.name());
      pendingRegistrations.remove(message.registerService);
    } else {
        
    }
    
    return Behaviors.same();
  }
  
  protected Behavior<Command> onServiceRegistrationFailed(@NotNull ServiceRegistrationFailed message) {
    LOGGER.trace("MDnsHomeAssistant.onServiceRegistrationFailed()");
    
    registrationRunning = false;
    
    return Behaviors.same();
  }

  @Override
  protected Behavior<Command> onRegisterService(@NotNull RegisterService registerService) {
    LOGGER.trace("MDnsHomeAssistant.onRegisterService()");
    
    pendingRegistrations.add(registerService);
    
    if (pyscriptAddonAvailable && !registrationRunning) {
      registerNextService();
    }
    
    return Behaviors.same();
  }

  /**
   * Try to detect the pyscript service
   */
  private void detectPyscriptService() {
    LOGGER.debug("MDnsHomeAssistant.detectPyscriptService()");
    
    String path = url + "/api/services";

    HttpRequest getRequest = HttpRequest.GET(path)
          .addCredentials(credentials);

    http.singleRequest(getRequest)
          .whenComplete((response, throwable) -> {
            if (throwable != null) {
              LOGGER.error("failed to look up the available services: {}", throwable.getMessage());
              getContext().getSelf().tell(new ServiceDetectionFailed(throwable));
            } else {
              try {
                response.entity()
                      .toStrict(5000, materializer)
                      .whenComplete((strictEntity, toStrictFailure) -> {
                        if (toStrictFailure != null) {
                          getContext().getSelf().tell(new ServiceDetectionFailed(toStrictFailure));
                        } else {
                          getContext().getSelf().tell(new ServiceDetectionSucceeded(response, strictEntity));
                        }
                      });
              } catch (Exception e) {
                // Failed to get a strict entity
                getContext().getSelf().tell(new ServiceDetectionFailed(e));
              }
            }
          });
    
  }
  
  private void registerNextService() {
    LOGGER.debug("MDnsHomeAssistant.registerNextService()");

    if (!registrationRunning && !pendingRegistrations.isEmpty()) {
      registrationRunning = true;
      
      registerService(pendingRegistrations.iterator().next());
    }
  }

  /**
   * Register the specified service
   * @param registerService Service to register
   */
  private void registerService(@NotNull RegisterService registerService) {
    String path = url + "/api/services/pyscript/uni_meter_mdns_register";
    
    String publicIpAddress = NetUtils.detectPrimaryIpAddress();
    
    String data;
    try {
      data = MAPPER.writeValueAsString(
            new Payload(
                  registerService.type(), 
                  registerService.name(), 
                  publicIpAddress, 
                  registerService.port(), 
                  registerService.properties()));
    } catch (JsonProcessingException e) {
      LOGGER.error("json serialization failed: {}", e.getMessage());
      throw new RuntimeException("json serialization failed: " + e.getMessage());
    }
    
    HttpRequest postRequest = HttpRequest.POST(path)
          .addCredentials(credentials)
          .withEntity(HttpEntities.create(ContentTypes.APPLICATION_JSON, data));
    
    http.singleRequest(postRequest)
          .whenComplete((response, throwable) -> {
            if (throwable != null) {
              LOGGER.error("failed to register home assistant service: {}", throwable.getMessage());
              getContext().getSelf().tell(new ServiceRegistrationFailed(throwable));
            } else {
              try {
                response.entity()
                      .toStrict(5000, materializer)
                      .whenComplete((strictEntity, toStrictFailure) -> {
                        if (toStrictFailure != null) {
                          getContext().getSelf().tell(new ServiceRegistrationFailed(toStrictFailure));
                        } else {
                          getContext().getSelf().tell(new ServiceRegistrationSucceeded(registerService, response, strictEntity));
                        }
                      });
              } catch (Exception e) {
                // Failed to get a strict entity
                getContext().getSelf().tell(new ServiceRegistrationFailed(e));
              }
            }
          });
  }

  @Override
  public void unregister(@NotNull MDnsHandle handle) {
    if (handle instanceof HaMDnsHandle haMDnsHandle) {
      unregister(
            haMDnsHandle.type(), 
            haMDnsHandle.name(), 
            haMDnsHandle.ip(), 
            haMDnsHandle.port(), 
            haMDnsHandle.properties());
    }
  }

  public void unregister(@NotNull String type,
                         @NotNull String name,
                         @NotNull String ip,
                         int port,
                         @NotNull Map<String,String> properties) {
    String path = url + "/api/services/pyscript/uni_meter_mdns_unregister";

    HttpRequest postRequest = HttpRequest.POST(path)
          .addCredentials(credentials)
          .withEntity(HttpEntities.create(ContentTypes.APPLICATION_JSON, getPayload(type, name, ip, port, properties)));

    http.singleRequest(postRequest)
          .whenComplete((response, throwable) -> {
            if (throwable != null) {
              LOGGER.error("failed to unregister home assistant service: {}", throwable.getMessage());
            } else {
              if (response.status() != StatusCodes.OK) {
                LOGGER.info("call of home assistant service pyscript.uni_meter_mdns_unregister failed with status: {}",
                      response.status());
              } else {
                LOGGER.info("successfully unregistered mdns service {}", name);
              }
            }
          });
  }
  
  private @NotNull String getPayload(String type,
                                     String name,
                                     String ip,
                                     int port,
                                     @NotNull Map<String,String> properties) {
    try {
      return MAPPER.writeValueAsString(new Payload(type, name, ip, port, properties));
    } catch (JsonProcessingException e) {
      return "";
    }
  }
  
  public record ServiceDetectionFailed(
        @NotNull Throwable throwable
  ) implements Command {}

  public record ServiceDetectionSucceeded(
        @NotNull HttpResponse response,
        @NotNull HttpEntity.Strict entity
  ) implements Command {}

  public record ServiceRegistrationFailed(
        @NotNull Throwable throwable
  ) implements Command {}
  
  public record ServiceRegistrationSucceeded(
        @NotNull RegisterService registerService,
        @NotNull HttpResponse response,
        @NotNull HttpEntity.Strict entity
  ) implements Command {}
  
  private record Payload(
        String type, 
        String name,
        String ip,
        int port,
        Map<String,String> properties
  ) {}
}
