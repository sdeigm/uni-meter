package com.deigmueller.uni_meter.common.shelly;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;

public class Rpc {
  private static final Logger LOGGER = LoggerFactory.getLogger("uni-meter.rpc"); 
  @Getter private static final ObjectMapper objectMapper = createObjectMapper();
  public static ObjectMapper createObjectMapper() {
    ObjectMapper objectMapper = new ObjectMapper();
    SimpleModule simpleModule = new SimpleModule("RpcModule", new Version(1,0,0, "", "com.deigmueller", "uni-meter"));
    simpleModule.addSerializer(Float.class, new FloatSerializer());
    simpleModule.addSerializer(Double.class, new DoubleSerializer());
    simpleModule.addSerializer(RpcNull.class, new RpcNullSerializer());
    simpleModule.addSerializer(RpcStringOrNull.class, new RpcStringOrNullSerializer());
    objectMapper.registerModule(simpleModule);    
    return objectMapper.findAndRegisterModules();
  }
  
  public static String toString(@Nullable Object object) {
    LOGGER.debug("Rpc.toString({})", object != null ? object.getClass().getSimpleName() : "null");
    try {
      return objectMapper.writeValueAsString(object);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }
  
  public static Request parseRequest(byte@NotNull[] data) {
    try {
      return treeToRequest(objectMapper.readTree(data));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static Request parseRequest(@NotNull String data) throws JsonProcessingException {
    return treeToRequest(objectMapper.readTree(data));
  }

  public static String notificationToString(NotificationFrame notification) {
    try {
      return objectMapper.writeValueAsString(notification);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  public static String responseToString(ResponseFrame response) {
    try {
      return objectMapper.writeValueAsString(response);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  public static byte[] responseToBytes(ResponseFrame response) {
    try {
      return objectMapper.writeValueAsBytes(response);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }
  
  private static Request treeToRequest(@NotNull JsonNode tree) throws JsonProcessingException {
    JsonNode methodNode = tree.get("method");
    if (methodNode != null) {
      String method = tree.get("method").asText();
      return switch (method) {
        case "EM.GetConfig" -> objectMapper.treeToValue(tree, EmGetConfig.class);
        case "EM.GetStatus" -> objectMapper.treeToValue(tree, EmGetStatus.class);
        case "EMData.GetStatus" -> objectMapper.treeToValue(tree, EmDataGetStatus.class);
        case "Shelly.GetStatus" -> objectMapper.treeToValue(tree, ShellyGetStatus.class);
        case "Shelly.GetDeviceInfo" -> objectMapper.treeToValue(tree, GetDeviceInfo.class);
        case "Sys.GetConfig" -> objectMapper.treeToValue(tree, SysGetConfig.class);
        case "Ws.GetConfig" -> objectMapper.treeToValue(tree, WsGetConfig.class);
        case "Ws.SetConfig" -> objectMapper.treeToValue(tree, WsSetConfig.class);
        default -> throw new IllegalArgumentException("unhandled RPC method '" + method + "'");
      };
    } else {
      throw new IllegalArgumentException("missing 'method' property in RPC request");
    }
  }

  public interface Request {
    String method();
    Integer id();
    String src();
    String dest();
  }

  public interface Response {
  }
  
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonPropertyOrder({"id", "src", "dst", "result"})
  public record ResponseFrame(
      @JsonProperty("id") long id,
      @JsonProperty("src") String src,
      @JsonProperty("dst") String dst,
      @JsonProperty("result") Response result
  ) {
    @Override public String toString() { return Rpc.toString(this); }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonPropertyOrder({"src", "dest", "method", "params"})
  public record EventFrame(
      @JsonProperty("src") String src,
      @JsonProperty("dest") String dest,
      @JsonProperty("method") String method,
      @JsonProperty("params") EventParams params
  ) {
    @Override public String toString() { return Rpc.toString(this); }
  }

  public record EventParams(
      @JsonProperty("ts") Double ts,
      @JsonProperty("events") List<EventData> events
  ) {}
  
  public record EventData(
      @JsonProperty("component") String component,
      @JsonProperty("id") long id,
      @JsonProperty("event") String event,
      @JsonProperty("ts") Double ts,
      @JsonProperty("data") List<EventDataItem> data
  ) {}
  
  public record EventDataItem(
        @JsonProperty("ts") Double ts,
        @JsonProperty("period") int period,
        @JsonProperty("values") List<Object> values
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonPropertyOrder({"src", "dst", "method", "params"})
  public record NotificationFrame(
        @JsonProperty("src") String src,
        @JsonProperty("dst") String dst,
        @JsonProperty("method") String method,
        @JsonProperty("params") NotificationParam param
  ) {
    @Override public String toString() { return Rpc.toString(this); }
  }

  public interface NotificationParam {
    Double ts();
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ShellyGetStatus(
        @JsonProperty("id") Integer id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dest") String dest
  ) implements Request {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record GetDeviceInfo(
        @JsonProperty("id") Integer id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dest") String dest
  ) implements Request {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonPropertyOrder({"name", "id", "mac", "slot", "model", "gen", "fw_id", "ver", "app", "auth_en", "auth_domain", "profile"})
  public record GetDeviceInfoResponse(
        @JsonProperty("name") String name,
        @JsonProperty("id") String id,
        @JsonProperty("mac") String mac,
        @JsonProperty("slot") int slot,
        @JsonProperty("model") String model,
        @JsonProperty("gen") long gen,
        @JsonProperty("fw_id") String fw_id,
        @JsonProperty("ver") String ver,
        @JsonProperty("app") String app,
        @JsonProperty("auth_en") boolean auth_en,
        @JsonProperty("auth_domain") String auth_domain,
        @JsonProperty("profile") String profile
  ) implements Response {
    @Override
    public String toString() {
      try {
        return objectMapper.writeValueAsString(this);
      } catch (JsonProcessingException e) {
        throw new RuntimeException(e);
      }
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record EmGetStatus(
        @JsonProperty("id") Integer id, 
        @JsonProperty("method") String method,
        @JsonProperty("src") String src, 
        @JsonProperty("dest") String dest,
        @JsonProperty("params") EmGetStatusParams params
  ) implements Request {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record EmGetStatusParams(
        @JsonProperty("id") int id
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonPropertyOrder({"id", "a_current", "a_voltage", "a_act_power", "a_aprt_power", "a_pf", "a_freq", "a_errors",
                     "b_current", "b_voltage", "b_act_power", "b_aprt_power", "b_pf", "b_freq", "b_errors",
                     "c_current", "c_voltage", "c_act_power", "c_aprt_power", "c_pf", "c_freq", "c_errors",
                     "n_current", "n_errors", "total_current", "total_act_power", "total_aprt_power", 
                     "user_calibrated_phase", "errors"})
  public record EmGetStatusResponse(
        @JsonProperty("id") Integer id,
        @JsonProperty("a_current") Double a_current,
        @JsonProperty("a_voltage") Double a_voltage,
        @JsonProperty("a_act_power") Double a_act_power,
        @JsonProperty("a_aprt_power") Double a_aprt_power,
        @JsonProperty("a_pf") Double a_pf,
        @JsonProperty("a_freq") Double a_freq,
        @JsonProperty("a_errors") List<String> a_errors,
        @JsonProperty("b_current") Double b_current,
        @JsonProperty("b_voltage") Double b_voltage,
        @JsonProperty("b_act_power") Double b_act_power,
        @JsonProperty("b_aprt_power") Double b_aprt_power,
        @JsonProperty("b_pf") Double b_pf,
        @JsonProperty("b_freq") Double b_freq,
        @JsonProperty("b_errors") List<String> b_errors,
        @JsonProperty("c_current") Double c_current,
        @JsonProperty("c_voltage") Double c_voltage,
        @JsonProperty("c_act_power") Double c_act_power,
        @JsonProperty("c_aprt_power") Double c_aprt_power,
        @JsonProperty("c_pf") Double c_pf,
        @JsonProperty("c_freq") Double c_freq,
        @JsonProperty("c_errors") List<String> c_errors,
        @JsonProperty("n_current") Double n_current,
        @JsonProperty("n_errors") List<String> n_errors,
        @JsonProperty("total_current") Double total_current,
        @JsonProperty("total_act_power") Double total_act_power,
        @JsonProperty("total_aprt_power") Double total_aprt_power,
        @JsonProperty("user_calibrated_phase") List<String> user_calibrated_phase,
        @JsonProperty("errors") List<String> errors
  ) implements Response {
    @Override public String toString() { return Rpc.toString(this); }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record EmGetConfig(
        @JsonProperty("id") Integer id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dest") String dest,
        @JsonProperty("params") EmGetConfigParams params
  ) implements Request {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record EmGetConfigParams(
        @JsonProperty("id") int id
  ) {}
  
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonPropertyOrder({"id", "name", "blink_mode_selector", "phase_selector", "monitor_phase_sequence", "reverse", "ct_type"})
  public record EmGetConfigResponse(
        @JsonProperty("id") Integer id,
        @JsonProperty("name") RpcStringOrNull name,
        @JsonProperty("blink_mode_selector") String blink_mode_selector,
        @JsonProperty("phase_selector") String phase_selector,
        @JsonProperty("monitor_phase_sequence") Boolean monitor_phase_sequence,
        @JsonProperty("reverse") ReverseConfig reverse,
        @JsonProperty("ct_type") String ct_type
  ) implements Response {
    @Override public String toString() { return Rpc.toString(this); }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ReverseConfig(
        @JsonProperty("a") Boolean a,
        @JsonProperty("b") Boolean b,
        @JsonProperty("c") Boolean c
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonPropertyOrder({"ts", "em:0"})
  public record EmGetStatusNotification(
        @JsonProperty("ts") Double ts,
        @JsonProperty("em:0") EmGetStatusResponse em0
  ) implements NotificationParam {}
  
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonPropertyOrder({"id", "method", "src", "dst", "params"})
  public record EmDataGetStatus(
        @JsonProperty("id") Integer id, 
        @JsonProperty("method") String method,
        @JsonProperty("src") String src, 
        @JsonProperty("dest") String dest,
        @JsonProperty("params") EmDataGetStatusParams params) implements Request {
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record EmDataGetStatusParams(
        @JsonProperty("id") int id
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonPropertyOrder({"id", "a_total_act_energy", "a_total_act_ret_energy", "b_total_act_energy", "b_total_act_ret_energy",
                     "c_total_act_energy", "c_total_act_ret_energy", "total_act", "total_act_ret", "errors"})
  public record EmDataGetStatusResponse(
        @JsonProperty("id") long id,
        @JsonProperty("a_total_act_energy") Double a_total_act_energy,
        @JsonProperty("a_total_act_ret_energy") Double a_total_act_ret_energy,
        @JsonProperty("b_total_act_energy") Double b_total_act_energy,
        @JsonProperty("b_total_act_ret_energy") Double b_total_act_ret_energy,
        @JsonProperty("c_total_act_energy") Double c_total_act_energy,
        @JsonProperty("c_total_act_ret_energy") Double c_total_act_ret_energy,
        @JsonProperty("total_act") Double total_act,
        @JsonProperty("total_act_ret") Double total_act_ret,
        @JsonProperty("errors") String[] errors
  ) implements Response {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonPropertyOrder({"ts", "emdata:0"})
  public record EmDataGetStatusNotification(
        @JsonProperty("ts") Double ts,
        @JsonProperty("emdata:0") EmDataGetStatusResponse emdata0
  ) implements NotificationParam {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonPropertyOrder({"ts", "em:0", "emdata:0"})
  public record FullStatusNotification(
        @JsonProperty("ts") Double ts,
        @JsonProperty("em:0") EmGetStatusResponse em0,
        @JsonProperty("emdata:0") EmDataGetStatusResponse emdata0
  ) implements NotificationParam {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SysGetConfig(
        @JsonProperty("id") Integer id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dest") String dest,
        @JsonProperty("params") SysGetConfigParams params
  ) implements Request {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SysGetConfigParams(
  ) {}
  
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SysGetConfigResponse(
        @JsonProperty("device") Device device,
        @JsonProperty("location") Location location,
        @JsonProperty("debug") Debug debug,
        @JsonProperty("ui_data") UiData ui_data,
        @JsonProperty("rpc_udp") RpcUdp rpc_udp,
        @JsonProperty("sntp") Sntp sntp,
        @JsonProperty("cfg_rev") int cfg_rev
  ) implements Response {
    @Override public String toString() { return Rpc.toString(this); }
  }
  
  public record Device(
        @JsonProperty("name") String name,
        @JsonProperty("mac") String mac,
        @JsonProperty("fw_id") String fw_id,
        @JsonProperty("eco_mode") boolean eco_mode,
        @JsonProperty("profile") String profile,
        @JsonProperty("discoverable") boolean discoverable
  ) {}
  
  public record Location(
        @JsonProperty("tz") String tz,
        @JsonProperty("lat") float lat,
        @JsonProperty("lon") float lon
  ) {}
  
  public record Debug(
        @JsonProperty("mqtt") Mqtt mqtt,
        @JsonProperty("websocket") Websocket websocket,
        @JsonProperty("udp") Udp udp
  ) {}
  
  public record Mqtt(
        @JsonProperty("enable") boolean enable
  ) {}
  
  public record Websocket(
        @JsonProperty("enable") boolean enable
  ) {}
  
  public record Udp(
        @JsonProperty("addr") RpcNull addr
  ) {}
  
  public record UiData() {}
  
  public record RpcUdp(
        @JsonProperty("dst_addr") String dst_addr,
        @JsonProperty("listen_port") Integer listen_port
  ) {}
  
  public record Sntp(
        @JsonProperty("server") String server
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record WsGetConfig(
        @JsonProperty("id") Integer id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dest") String dest
  ) implements Request {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record WsGetConfigResponse(
        @JsonProperty("enable") Boolean enable,
        @JsonProperty("server") RpcStringOrNull server,
        @JsonProperty("ssl_ca") String ssl_ca
  ) implements Response {
    public @NotNull WsGetConfigResponse withEnable(boolean enable) {
      return new WsGetConfigResponse(enable, server, ssl_ca);
    }
    public @NotNull WsGetConfigResponse withServer(@Nullable String server) {
      return new WsGetConfigResponse(enable, RpcStringOrNull.of(server), ssl_ca);
    }
    public @NotNull WsGetConfigResponse withSslCa(@Nullable String ssl_ca) {
      return new WsGetConfigResponse(enable, server, ssl_ca);
    }
    
    @Override public String toString() { return Rpc.toString(this); }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record WsSetConfig(
        @JsonProperty("id") Integer id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dest") String dest,
        @JsonProperty("params") WsSetConfigParams params
  ) implements Request {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record WsSetConfigParams(
        @JsonProperty("enable") Boolean enable,
        @JsonProperty("server") String server,
        @JsonProperty("ssl_ca") String ssl_ca
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record WsSetConfigResponse(
        @JsonProperty("restart_required") Boolean restart_required
  ) implements Response {
    @Override public String toString() { return Rpc.toString(this); }
  }

  public record RpcNull() {}
  
  public record RpcStringOrNull(
        String value
  ) {
    public static RpcStringOrNull of(@Nullable String value) {
      return new RpcStringOrNull(value);
    }
  }

  private static class LongSerializer extends JsonSerializer<Long> {
    @Override
    public void serialize(Long value, JsonGenerator jsonGenerator, SerializerProvider provider) throws IOException {
      if (null == value) {
        //write the word 'null' if there's no value available
        jsonGenerator.writeNull();
      } else {
        jsonGenerator.writeNumber(Long.toString(value));
      }
    }
  }

  private static class FloatSerializer extends JsonSerializer<Float> {
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#.##", DecimalFormatSymbols.getInstance(Locale.ENGLISH));

    @Override
    public void serialize(Float value, JsonGenerator jsonGenerator, SerializerProvider provider) throws IOException {
      if (null == value) {
        jsonGenerator.writeNull();
      } else {
        jsonGenerator.writeNumber(DECIMAL_FORMAT.format(value));
      }
    }
  }

  private static class DoubleSerializer extends JsonSerializer<Double> {
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#.##", DecimalFormatSymbols.getInstance(Locale.ENGLISH));

    @Override
    public void serialize(Double value, JsonGenerator jsonGenerator, SerializerProvider provider) throws IOException {
      if (null == value) {
        jsonGenerator.writeNull();
      } else {
        jsonGenerator.writeNumber(DECIMAL_FORMAT.format(value));
      }
    }
  }

  private static class RpcNullSerializer extends JsonSerializer<RpcNull> {
    @Override
    public void serialize(RpcNull value, JsonGenerator jsonGenerator, SerializerProvider provider) throws IOException {
      jsonGenerator.writeNull();
    }
  }

  private static class RpcStringOrNullSerializer extends JsonSerializer<RpcStringOrNull> {
    @Override
    public void serialize(RpcStringOrNull value, JsonGenerator jsonGenerator, SerializerProvider provider) throws IOException {
      if (value != null && value.value() != null) {
        jsonGenerator.writeString(value.value());
      } else {
        jsonGenerator.writeNull();
      }
    }
  }
}
