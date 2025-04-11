package com.deigmueller.uni_meter.mdns;

import com.deigmueller.uni_meter.common.utils.NetUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.http.javadsl.Http;
import org.apache.pekko.http.javadsl.model.*;
import org.apache.pekko.http.javadsl.model.headers.HttpCredentials;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletionStage;

public class MDnsHomeAssistant implements MDnsKind {
  // Class members
  public static final String TYPE = "homeassistant";
  private final static Logger LOGGER = LoggerFactory.getLogger("uni-meter.mdns.ha");
  private static final ObjectMapper MAPPER = new ObjectMapper();
  
  // Instance members
  private final Http http;
  private final String url;
  private final HttpCredentials credentials;
  
  public MDnsHomeAssistant(@NotNull ActorSystem<?> actorSystem,
                           @NotNull Config config) {
    http = Http.get(actorSystem);
    url = config.getString("url");
    credentials = HttpCredentials.createOAuth2BearerToken(config.getString("access-token"));

    LOGGER.debug("using url {}", config.getString("url"));
    LOGGER.debug("using access token {}", config.getString("access-token"));
  }

  @Override
  public CompletionStage<MDnsHandle> register(@NotNull String type,
                                              @NotNull String name,
                                              int port,
                                              @NotNull Map<String,String> properties) {
    String path = url + "/api/services/pyscript/uni_meter_mdns_register";
    
    String publicIpAddress = NetUtils.detectPrimaryIpAddress();
    
    String data;
    try {
      data = MAPPER.writeValueAsString(new Payload(type, name, publicIpAddress, port, properties));
    } catch (JsonProcessingException e) {
      LOGGER.error("json serialization failed: {}", e.getMessage());
      throw new RuntimeException("json serialization failed: " + e.getMessage());
    }
    
    HttpRequest postRequest = HttpRequest.POST(path)
          .addCredentials(credentials)
          .withEntity(HttpEntities.create(ContentTypes.APPLICATION_JSON, data));
    
    return http.singleRequest(postRequest)
          .handleAsync((response, throwable) -> {
            if (throwable != null) {
              LOGGER.error("failed to register home assistant service: {}", throwable.getMessage());
              return null;
            } else {
              if (response.status() != StatusCodes.OK) {
                LOGGER.info("call of home assistant service pyscript.uni_meter_mdns_register failed with status: {}",
                      response.status());
                return null;
              } else {
                LOGGER.info("successfully registered mdns service {}", name);
                return new HaMDnsHandle(type, name, publicIpAddress, port, properties);
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
  
  public record HaMDnsHandle(
        @NotNull String type,
        @NotNull String name,
        @NotNull String ip,
        int port,
        @NotNull Map<String,String> properties
  ) implements MDnsHandle {}

  private record Payload(
        String type, 
        String name,
        String ip,
        int port,
        Map<String,String> properties
  ) {}
}
