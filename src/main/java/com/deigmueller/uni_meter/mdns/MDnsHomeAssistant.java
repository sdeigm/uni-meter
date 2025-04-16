package com.deigmueller.uni_meter.mdns;

import com.deigmueller.uni_meter.common.utils.NetUtils;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.typesafe.config.Config;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.PostStop;
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

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MDnsHomeAssistant extends MDnsKind {
  // Class members
  public static final String TYPE = "homeassistant";
  private final static Logger LOGGER = LoggerFactory.getLogger("uni-meter.mdns.ha");
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String UNI_METER_MDNS_REGISTER = "uni_meter_mdns_register";
  private static final String UNI_METER_MDNS_UNREGISTER = "uni_meter_mdns_unregister";
  
  // Instance members
  private final Http http = Http.get(getContext().getSystem());
  private final String url = getConfig().getString("url");
  private final HttpCredentials credentials = HttpCredentials.createOAuth2BearerToken(getConfig().getString("access-token"));
  private final Materializer materializer = Materializer.createMaterializer(getContext().getSystem());
  private final Set<RegisterService> pendingRegistrations = new HashSet<>();
  private final Set<RegisterService> registeredServices = new HashSet<>();
  private final String publicIpAddress = NetUtils.detectPrimaryIpAddress();
  private boolean serviceDetectionErrorShown = false;
  private boolean serviceRegistrationErrorShown = false;
  private boolean registrationRunning = false;
  private boolean uniMeterMdnsRegisterAvailable = false;
  private boolean uniMeterMdnsUnregisterAvailable = false;
  private Duration serviceDetectionBackoff = Duration.ofSeconds(15);
  private Duration retryRegistrationBackoff = Duration.ofSeconds(15);

  public static Behavior<Command> create(@NotNull Config config) {
    return Behaviors.setup(context -> new MDnsHomeAssistant(context, config));
  }

  public MDnsHomeAssistant(@NotNull ActorContext<Command> context,
                           @NotNull Config config) {
    super(context, config);
    
    LOGGER.debug("using url {}", config.getString("url"));
    LOGGER.debug("using access token {}", config.getString("access-token"));
    
    startServiceDetection();
  }

  @Override
  public ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onSignal(PostStop.class, this::onPostStop)
          .onMessage(ServiceDetectionSucceeded.class, this::onServiceDetectionSucceeded)
          .onMessage(ServiceDetectionFailed.class, this::onServiceDetectionFailed)
          .onMessage(StartNextServiceDetection.class, this::onStartNextServiceDetection)
          .onMessage(ServiceRegistrationSucceeded.class, this::onServiceRegistrationSucceeded)
          .onMessage(ServiceRegistrationFailed.class, this::onServiceRegistrationFailed)
          .onMessage(StartRetryRegistration.class, this::onStartRetryRegistration)
          .onMessage(RegisterService.class, this::onRegisterService);
  }
  
  private Behavior<Command> onPostStop(PostStop postStop) {
    if (canUnregisterService()) {
      for (RegisterService service : registeredServices) {
        unregister(service.type(), service.name(), publicIpAddress, service.port(), service.properties());
      }
    }
    return Behaviors.same();
  }

  /**
   * Handle the successful detection of available services
   * @param message Message containing the result of the web request
   * @return Same behavior
   */
  private Behavior<Command> onServiceDetectionSucceeded(ServiceDetectionSucceeded message) {
    LOGGER.trace("MDnsHomeAssistant.onServiceDetectionSucceeded");

    try {
      if (message.response.status() == StatusCodes.OK) {
        LOGGER.debug("list services returned: {}", message.entity.getData().utf8String());
        
        List<Domain> domains = MAPPER.readValue(message.entity.getData().toArray(), CollectionsTypeFactory.listOfDomain());
        for (Domain domain : domains) {
          if (domain.domain().equals("pyscript")) {
            uniMeterMdnsRegisterAvailable = domain.services().containsKey(UNI_METER_MDNS_REGISTER);
            uniMeterMdnsUnregisterAvailable = domain.services().containsKey(UNI_METER_MDNS_UNREGISTER);
            
            break;
          }
        }

        if (canRegisterNextService()) {
          registerNextService();
        }
      } else {
        if (! serviceDetectionErrorShown) {
          LOGGER.warn("failed to lookup available services, http request returned with status {}. retrying ...", message.response.status());
          serviceDetectionErrorShown = true;
        } else {
          LOGGER.debug("failed to lookup available services, http request returned with status {}. retrying ...", message.response.status());
        }
      }
    } catch (Exception e) {
      if (! serviceDetectionErrorShown) {
        LOGGER.error("service detection failed: {}", e.getMessage());
        serviceDetectionErrorShown = true;
      } else {
        LOGGER.debug("service detection failed: {}", e.getMessage());
      }
    }

    if (!uniMeterMdnsRegisterAvailable) {
      startServiceDetectionTimer();
    }

    return Behaviors.same();
  }

  /**
   * Handles the failure to detect the available services.
   * @param message the message containing details of the service detection failure, including the associated throwable.
   * @return the same behavior to continue processing subsequent messages.
   */
  private Behavior<Command> onServiceDetectionFailed(ServiceDetectionFailed message) {
    LOGGER.trace("MDnsHomeAssistant.onServiceDetectionFailed");

    if (! serviceDetectionErrorShown) {
      LOGGER.error("failed to lookup available services: {}", message.throwable().getMessage());
      serviceDetectionErrorShown = true;
    } else {
      LOGGER.debug("failed to lookup available services: {}", message.throwable().getMessage());
    }

    startServiceDetectionTimer();
    
    return Behaviors.same();
  }
  
  private Behavior<Command> onStartNextServiceDetection(StartNextServiceDetection message) {
    LOGGER.trace("MDnsHomeAssistant.onStartNextServiceDetection");
    startServiceDetection();
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
      serviceRegistrationErrorShown = false;
      pendingRegistrations.remove(message.registerService);
      registeredServices.add(message.registerService);
      
      if (canRegisterNextService()) {
        registerNextService();
      }
    } else {
      if (! serviceRegistrationErrorShown) {
        LOGGER.warn("failed to register mdns service {}: http request returned with status {}. retrying ...", 
              message.registerService.name(), message.response.status());
        serviceRegistrationErrorShown = true;
      } else {
        LOGGER.debug("failed to register mdns service {}: http request returned with status {}. retrying ...",
              message.registerService.name(), message.response.status());
      }
      startRetryRegistrationTimer();
    }
    
    return Behaviors.same();
  }
  
  protected Behavior<Command> onServiceRegistrationFailed(@NotNull ServiceRegistrationFailed message) {
    LOGGER.trace("MDnsHomeAssistant.onServiceRegistrationFailed()");
    
    registrationRunning = false;

    if (! serviceRegistrationErrorShown) {
      LOGGER.error("mdns registration of service {} failed: {}", message.registerService.name(), 
            message.throwable().getMessage());
      serviceRegistrationErrorShown = true;
    } else {
      LOGGER.debug("mdns registration of service {} failed: {}", message.registerService.name(),
            message.throwable().getMessage());
    }
    
    return Behaviors.same();
  }
  
  protected Behavior<Command> onStartRetryRegistration(StartRetryRegistration message) {
    LOGGER.trace("MDnsHomeAssistant.onStartRetryRegistration()");
    
    if (canRegisterNextService()) { 
      registerNextService();
    }
    
    return Behaviors.same();   
  }

  @Override
  protected Behavior<Command> onRegisterService(@NotNull RegisterService registerService) {
    LOGGER.trace("MDnsHomeAssistant.onRegisterService()");
    
    pendingRegistrations.add(registerService);
    
    if (uniMeterMdnsRegisterAvailable && !registrationRunning) {
      registerNextService();
    }
    
    return Behaviors.same();
  }

  /**
   * Try to detect the pyscript service
   */
  private void startServiceDetection() {
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
              getContext().getSelf().tell(new ServiceRegistrationFailed(registerService, throwable));
            } else {
              try {
                response.entity()
                      .toStrict(5000, materializer)
                      .whenComplete((strictEntity, toStrictFailure) -> {
                        if (toStrictFailure != null) {
                          getContext().getSelf().tell(new ServiceRegistrationFailed(registerService, toStrictFailure));
                        } else {
                          getContext().getSelf().tell(new ServiceRegistrationSucceeded(registerService, response, strictEntity));
                        }
                      });
              } catch (Exception e) {
                // Failed to get a strict entity
                getContext().getSelf().tell(new ServiceRegistrationFailed(registerService, e));
              }
            }
          });
  }

  public void unregister(@NotNull String type,
                         @NotNull String name,
                         @NotNull String ip,
                         int port,
                         @NotNull Map<String,String> properties) {
    String path = url + "/api/services/pyscript/" ;

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
  
  private boolean canRegisterNextService() {
    return uniMeterMdnsRegisterAvailable && !pendingRegistrations.isEmpty() && !registrationRunning;
  }
  
  private boolean canUnregisterService() {
    return uniMeterMdnsUnregisterAvailable;
  }
  
  private void startServiceDetectionTimer() {
    getContext().getSystem().scheduler().scheduleOnce(serviceDetectionBackoff,
          () -> getContext().getSelf().tell(StartNextServiceDetection.INSTANCE),
          getContext().getExecutionContext());
    serviceDetectionBackoff = serviceDetectionBackoff.plus(Duration.ofSeconds(15));
    if (serviceDetectionBackoff.getSeconds() > 300) {
      serviceDetectionBackoff = Duration.ofSeconds(300);
    }
  }
  
  private void startRetryRegistrationTimer() {
    getContext().getSystem().scheduler().scheduleOnce(retryRegistrationBackoff,
          () -> getContext().getSelf().tell(StartRetryRegistration.INSTANCE),
          getContext().getExecutionContext());
    retryRegistrationBackoff = retryRegistrationBackoff.plus(Duration.ofSeconds(15));
    if (retryRegistrationBackoff.getSeconds() > 60) {
      retryRegistrationBackoff = Duration.ofSeconds(60);
    }
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
        @NotNull RegisterService registerService,
        @NotNull Throwable throwable
  ) implements Command {}
  
  public record ServiceRegistrationSucceeded(
        @NotNull RegisterService registerService,
        @NotNull HttpResponse response,
        @NotNull HttpEntity.Strict entity
  ) implements Command {}
  
  public enum StartNextServiceDetection implements Command {
    INSTANCE
  }

  public enum StartRetryRegistration implements Command {
    INSTANCE
  }
  
  private record Payload(
        @JsonProperty("type") String type,
        @JsonProperty("name") String name,
        @JsonProperty("ip") String ip,
        @JsonProperty("port") int port,
        @JsonProperty("properties") Map<String,String> properties
  ) {}
  
  private record Domain(
        @JsonProperty("domain") String domain,
        @JsonProperty("services") Map<String,Object> services
  ) {}

  private static class CollectionsTypeFactory {
    static JavaType listOfDomain() {
      return TypeFactory.defaultInstance().constructCollectionType(List.class, Domain.class);
    }
  }
}
