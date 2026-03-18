package com.deigmueller.uni_meter.common.shelly;

import com.deigmueller.uni_meter.output.OutputDevice;
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
import java.time.Duration;
import java.util.List;
import java.util.Locale;

@SuppressWarnings("SpellCheckingInspection")
public class Rpc {
  private static final Logger LOGGER = LoggerFactory.getLogger("uni-meter.rpc"); 
  @Getter private static final ObjectMapper objectMapper = createObjectMapper();

  public static ObjectMapper createObjectMapper() {
    ObjectMapper objectMapper = new ObjectMapper();
    SimpleModule simpleModule = new SimpleModule("RpcModule", new Version(1, 0, 0, "", "com.deigmueller", "uni-meter"));
    simpleModule.addSerializer(Float.class, new FloatSerializer());
    simpleModule.addSerializer(Double.class, new DoubleSerializer());
    simpleModule.addSerializer(RpcNull.class, new RpcNullSerializer());
    simpleModule.addSerializer(RpcDoubleOrNull.class, new RpcDoubleOrNullSerializer());
    simpleModule.addSerializer(RpcStringOrNull.class, new RpcStringOrNullSerializer());
    simpleModule.addSerializer(Duration.class, new DurationSerializer());
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
      return switch (method.toLowerCase()) {
        case "cloud.getconfig" -> objectMapper.treeToValue(tree, CloudGetConfig.class);
        case "cloud.getstatus" -> objectMapper.treeToValue(tree, CloudGetStatus.class);
        case "cloud.setconfig" -> objectMapper.treeToValue(tree, CloudSetConfig.class);
        case "em.getconfig" -> objectMapper.treeToValue(tree, EmGetConfig.class);
        case "em.getstatus" -> objectMapper.treeToValue(tree, EmGetStatus.class);
        case "emdata.getconfig" -> objectMapper.treeToValue(tree, EmDataGetConfig.class);
        case "emdata.getstatus" -> objectMapper.treeToValue(tree, EmDataGetStatus.class);
        case "script.list" -> objectMapper.treeToValue(tree, ScriptList.class);
        case "script.getcode" -> objectMapper.treeToValue(tree, ScriptGetCode.class);
        case "shelly.getcomponents" -> objectMapper.treeToValue(tree, ShellyGetComponents.class);
        case "shelly.getconfig" -> objectMapper.treeToValue(tree, ShellyGetConfig.class);
        case "shelly.getstatus" -> objectMapper.treeToValue(tree, ShellyGetStatus.class);
        case "shelly.getdeviceinfo" -> objectMapper.treeToValue(tree, GetDeviceInfo.class);
        case "shelly.reboot" -> objectMapper.treeToValue(tree, ShellyReboot.class);
        case "sys.getconfig" -> objectMapper.treeToValue(tree, SysGetConfig.class);
        case "sys.getstatus" -> objectMapper.treeToValue(tree, SysGetStatus.class);
        case "wifi.getconfig" -> objectMapper.treeToValue(tree, WifiGetConfig.class);
        case "wifi.getstatus" -> objectMapper.treeToValue(tree, WifiGetStatus.class);
        case "ws.getconfig" -> objectMapper.treeToValue(tree, WsGetConfig.class);
        case "ws.setconfig" -> objectMapper.treeToValue(tree, WsSetConfig.class);
        default -> throw new IllegalArgumentException("unhandled RPC method '" + method + "'");
      };
    } else {
      throw new IllegalArgumentException("missing 'method' property in RPC request");
    }
  }

  public interface Request {
    String method();
    Long id();
    String src();
    String dst();
  }

  public interface Response {}
  
  public record Error(
        @JsonProperty("code") int code,
        @JsonProperty("messge") String message
  ) {}
  
  public interface Status {}
  
  public interface Config {}
  
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonPropertyOrder({"id", "src", "dst", "result"})
  public record ResponseFrame(
      @JsonProperty("id") long id,
      @JsonProperty("src") String src,
      @JsonProperty("dst") String dst,
      @JsonProperty("result") Response result,
      @JsonProperty("error") Error error
  ) {
    @Override public @NotNull String toString() { return Rpc.toString(this); }
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
    @Override public @NotNull String toString() { return Rpc.toString(this); }
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
    @Override public @NotNull String toString() { return Rpc.toString(this); }
  }

  public interface NotificationParam {
    Double ts();
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ScriptList(
        @JsonProperty("id") Long id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dst") String dst
  ) implements Request {}
  
  public record ScriptListResponse(
        @JsonProperty("scripts") List<Script> scripts
  ) implements Response {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Script(
        @JsonProperty("id") int id,
        @JsonProperty("name") String name,
        @JsonProperty("enable") boolean enable,
        @JsonProperty("running") boolean running
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ScriptGetCode(
        @JsonProperty("id") Long id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dst") String dst,
        @JsonProperty("params") ScriptGetCodeParams params
  ) implements Request {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ScriptGetCodeParams(
        @JsonProperty("id") int id
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ScriptGetCodeResponse(
        @JsonProperty("data") String data,
        @JsonProperty("left") long left
  ) implements Rpc.Response {
    @Override public @NotNull String toString() { return Rpc.toString(this); }
  }
  
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ShellyGetComponents(
        @JsonProperty("id") Long id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dst") String dst,
        @JsonProperty("params") ShellyGetComponentsParams params
  ) implements Request {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ShellyGetComponentsParams(
        @JsonProperty("offset") Integer offset,
        @JsonProperty("include") List<String> include,
        @JsonProperty("keys") List<String> keys,
        @JsonProperty("dynamic_only") Boolean dynamic_only
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ShellyGetComponentsResponse(
        @JsonProperty("components") List<Rpc.Component> component,
        @JsonProperty("cfg_rev") int cfg_reg,
        @JsonProperty("offset") int offset,
        @JsonProperty("total") int total
  ) implements Rpc.Response {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Component(
        @JsonProperty("key") String key,
        @JsonProperty("status") Status status,
        @JsonProperty("config") Config config
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ShellyGetConfig(
        @JsonProperty("id") Long id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dst") String dst
  ) implements Request {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ShellyGetStatus(
        @JsonProperty("id") Long id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dst") String dst
  ) implements Request {}
  
  public record ShellyGetStatusResponse(
        @JsonProperty("ble") BleGetStatusResponse ble,
        @JsonProperty("bthome") BtHomeGetStatusResponse bthome,
        @JsonProperty("cloud") CloudGetStatusResponse cloud,
        @JsonProperty("em:0") EmGetStatusResponse em0,
        @JsonProperty("emdata:0") EmDataGetStatusResponse emdata0,
        @JsonProperty("eth") EthGetStatusResponse eth,
        @JsonProperty("modbus") ModbusGetStatusResponse modbus,
        @JsonProperty("mqtt") MqttGetStatusResponse mqtt,
        @JsonProperty("sys") SysGetStatusResponse sys,
        @JsonProperty("temperature:0") TemperatureGetStatusResponse temperature0,
        @JsonProperty("wifi") WifiGetStatusResponse wifi,
        @JsonProperty("ws") WsGetStatusResponse ws
  ) implements Rpc.Response {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonPropertyOrder({"id", "method", "src", "dst"})
  public record TemperatureGetConfig(
        @JsonProperty("id") Long id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dst") String dst) implements Request {
  }

  public record TemperatureGetStatusResponse(
        @JsonProperty("id") long id,
        @JsonProperty("tC") double tC,
        @JsonProperty("tF") double tF
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonPropertyOrder({"id", "method", "src", "dst"})
  public record TemperatureGetStatus(
        @JsonProperty("id") Long id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dst") String dst) implements Request {
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TemperatureGetConfigResponse(
        @JsonProperty("id") long id,
        @JsonProperty("method") RpcStringOrNull name,
        @JsonProperty("report_thr_C") double reportThrC,
        @JsonProperty("offset_C") double offsetC
  ) {}

  public record EMeterStatus(
        @JsonProperty("power") double power,
        @JsonProperty("pf") double pf,
        @JsonProperty("current") double current,
        @JsonProperty("voltage") double voltage,
        @JsonProperty("is_valid") boolean is_valid,
        @JsonProperty("total") double total,
        @JsonProperty("total_returned") double total_returned
  ) {
    public static EMeterStatus of(OutputDevice.PowerData data, double clientPowerFactor, OutputDevice.EnergyData energyData) {
      return new EMeterStatus(
            data.power() * clientPowerFactor,
            data.powerFactor(),
            data.current() * clientPowerFactor,
            data.voltage(),
            true,
            energyData.totalConsumption(),
            energyData.totalProduction());
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ShellyReboot(
        @JsonProperty("id") Long id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dst") String dst,
        @JsonProperty("params") ShellyRebootParams params
  ) implements Request {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ShellyRebootParams(
        @JsonProperty("delay_ms") int delay_ms
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ShellyRebootResponse(
  ) implements Response {
    @Override
    public @NotNull String toString() {
      try {
        return objectMapper.writeValueAsString(this);
      } catch (JsonProcessingException e) {
        throw new RuntimeException(e);
      }
    }
  }
  
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record GetDeviceInfo(
        @JsonProperty("id") Long id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dst") String dst
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
        @JsonProperty("auth_domain") RpcStringOrNull auth_domain,
        @JsonProperty("profile") String profile
  ) implements Response {
    @Override
    public @NotNull String toString() {
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
        @JsonProperty("id") Long id, 
        @JsonProperty("method") String method,
        @JsonProperty("src") String src, 
        @JsonProperty("dst") String dst,
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
        @JsonProperty("n_current") RpcDoubleOrNull n_current,
        @JsonProperty("n_errors") List<String> n_errors,
        @JsonProperty("total_current") Double total_current,
        @JsonProperty("total_act_power") Double total_act_power,
        @JsonProperty("total_aprt_power") Double total_aprt_power,
        @JsonProperty("user_calibrated_phase") List<String> user_calibrated_phase,
        @JsonProperty("errors") List<String> errors
  ) implements Response, Status {
    @Override public @NotNull String toString() { return Rpc.toString(this); }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record EmGetConfig(
        @JsonProperty("id") Long id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dst") String dst,
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
  ) implements Response, Config {
    @Override public @NotNull String toString() { return Rpc.toString(this); }
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
  public record EmDataGetConfig(
        @JsonProperty("id") Long id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dst") String dst,
        @JsonProperty("params") EmDataGetStatusParams params) implements Request {
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record EmDataGetConfigResponse(
  ) implements Response, Config {}
  
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonPropertyOrder({"id", "method", "src", "dst", "params"})
  public record EmDataGetStatus(
        @JsonProperty("id") Long id, 
        @JsonProperty("method") String method,
        @JsonProperty("src") String src, 
        @JsonProperty("dst") String dst,
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
  ) implements Response, Status {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonPropertyOrder({"id", "method", "src", "dst"})
  public record EthGetStatus(
        @JsonProperty("id") Long id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dst") String dst) implements Request {
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record EthGetStatusResponse(
        @JsonProperty("ip") RpcStringOrNull ip,
        @JsonProperty("ip6") RpcStringOrNull ip6
  ) implements Response, Config {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonPropertyOrder({"id", "method", "src", "dst"})
  public record ModbusGetStatus(
        @JsonProperty("id") Long id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dst") String dst) implements Request {
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ModbusGetStatusResponse(
  ) implements Response, Config {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonPropertyOrder({"id", "method", "src", "dst"})
  public record MqttGetStatus(
        @JsonProperty("id") Long id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dst") String dst) implements Request {
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record MqttGetStatusResponse(
        @JsonProperty("connected") boolean connected
  ) implements Response, Config {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SysGetConfig(
        @JsonProperty("id") Long id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dst") String dst,
        @JsonProperty("params") SysGetConfigParams params
  ) implements Request {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SysGetStatus(
        @JsonProperty("id") Long id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dst") String dst
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
    @Override public @NotNull String toString() { return Rpc.toString(this); }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SysGetStatusResponse(
        @JsonProperty("mac") String mac,
        @JsonProperty("restart_required") boolean restartRequired,
        @JsonProperty("time") String time,
        @JsonProperty("unixtime") long unixTime,
        @JsonProperty("last_sync_ts") long lastSyncTs,
        @JsonProperty("uptime") long upTime,
        @JsonProperty("ram_size") long ramSize,
        @JsonProperty("ram_free") long ramFree,
        @JsonProperty("ram_min_free") long ramMinFree,
        @JsonProperty("fs_size") long fsSize,
        @JsonProperty("fs_free") long fsFree,
        @JsonProperty("cfg_rev") int cfgRev,
        @JsonProperty("kvs_rev") int kvsRev,
        @JsonProperty("schedule_rev") int scheduleRev,
        @JsonProperty("webhook_rev") int webhookRev,
        @JsonProperty("btrelay_ref") int btRelayRev,
        @JsonProperty("available_updates") AvailableUpdates availableUpdates,
        @JsonProperty("reset_reason") int reasetReason,
        @JsonProperty("utc_offset") int utcOffset
  ) implements Response {
    @Override public @NotNull String toString() { return Rpc.toString(this); }
  }
  
  public record VersionInfo(
        @JsonProperty("version") String version
  ) {}
  
  public record AvailableUpdates(
        @JsonProperty("beta") VersionInfo beta
  ) {}

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
  public record BleGetConfig(
        @JsonProperty("id") Long id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dst") String dst
  ) implements Request {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record BleGetConfigResponse(
        @JsonProperty("enable") Boolean enable,
        @JsonProperty("rpc") BleGetConfigResponseRpc rpc,
        @JsonProperty("observer") BleGetConfigResponseObserver observer
  ) implements Config {}

  public record BleGetConfigResponseRpc(
        @JsonProperty("enable") Boolean enable
  ) {}

  public record BleGetConfigResponseObserver(
        @JsonProperty("enable") Boolean enable
  ) {}


  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record BleGetStatus(
        @JsonProperty("id") Long id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dst") String dst
  ) implements Request {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record BleGetStatusResponse(
  ) implements Response, Status {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record BtHomeGetStatus(
        @JsonProperty("id") Long id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dst") String dst
  ) implements Request {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record BtHomeGetStatusResponse(
  ) implements Response {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CloudGetStatus(
        @JsonProperty("id") Long id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dst") String dst
  ) implements Request {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CloudGetStatusResponse(
        @JsonProperty("enabled") Boolean enabled,
        @JsonProperty("connected") Boolean connected
  ) implements Response, Status {
    public CloudGetStatusResponse(com.typesafe.config.Config config) {
      this(
            config.getBoolean("connected") ? null : config.getBoolean("enabled"),
            config.getBoolean("enabled") ? config.getBoolean("connected") : null);
    }
  }
  
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CloudGetConfig(
        @JsonProperty("id") Long id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dst") String dst
  ) implements Request {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CloudGetConfigResponse(
        @JsonProperty("enable") Boolean enable,
        @JsonProperty("server") RpcStringOrNull server
  ) implements Response {
    public @NotNull CloudGetConfigResponse withEnable(boolean enable) {
      return new CloudGetConfigResponse(enable, server);
    }
    public @NotNull CloudGetConfigResponse withServer(@Nullable String server) {
      return new CloudGetConfigResponse(enable, RpcStringOrNull.of(server));
    }
    @Override public @NotNull String toString() { return Rpc.toString(this); }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CloudSetConfig(
        @JsonProperty("id") Long id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dst") String dst,
        @JsonProperty("params") CloudSetConfigParams params
  ) implements Request {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CloudSetConfigParams(
        @JsonProperty("config") CloudSetConfigParamsValues config
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CloudSetConfigParamsValues(
        @JsonProperty("enable") Boolean enable,
        @JsonProperty("server") String server
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CloudSetConfigResponse(
        @JsonProperty("restart_required") Boolean restart_required
  ) implements Response {
    @Override public @NotNull String toString() { return Rpc.toString(this); }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AccessPointRangeExtender (
        @JsonProperty("enable") boolean enable
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AccessPointConfig (
        @JsonProperty("ssid") String ssid,
        @JsonProperty("is_open") boolean isOpen,
        @JsonProperty("enable") boolean enable,
        @JsonProperty("range_extender") AccessPointRangeExtender rangeExtender
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record InterfaceConfig (
        @JsonProperty("ssid") RpcStringOrNull ssid,
        @JsonProperty("is_open") boolean isOpen,
        @JsonProperty("enable") boolean enable,
        @JsonProperty("ipv4mode") String ipV4Mode,
        @JsonProperty("ip") RpcStringOrNull ip,
        @JsonProperty("netmask") RpcStringOrNull netmask,
        @JsonProperty("gw") RpcStringOrNull gw,
        @JsonProperty("nameserver") RpcStringOrNull nameserver
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RoamConfig(
        @JsonProperty("rssi_thr") int rssiThr,
        @JsonProperty("interval") int interval
  ) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record WifiGetConfig(
        @JsonProperty("id") Long id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dst") String dst
  ) implements Request {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record WifiGetConfigResponse(
        @JsonProperty("ap") AccessPointConfig ap,
        @JsonProperty("sta") InterfaceConfig sta,
        @JsonProperty("sta1") InterfaceConfig sta1,
        @JsonProperty("roam") RoamConfig roam
  ) implements Response {}
  
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record WifiGetStatus(
        @JsonProperty("id") Long id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dst") String dst
  ) implements Request {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record WifiGetStatusResponse(
        @JsonProperty("sta_ip") String sta_ip,
        @JsonProperty("status") String status,
        @JsonProperty("ssid") String ssid,
        @JsonProperty("bssid") String bssid,
        @JsonProperty("rssi") int rssi,
        @JsonProperty("sta_ip6") List<String> sta_ip6
  ) implements Response {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record WsGetStatus(
        @JsonProperty("id") Long id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dst") String dst
  ) implements Request {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record WsGetStatusResponse(
        @JsonProperty("connected") Boolean connected
  ) implements Response {}
  
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record WsGetConfig(
        @JsonProperty("id") Long id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dst") String dst
  ) implements Request {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record WsGetConfigResponse(
        @JsonProperty("enable") Boolean enable,
        @JsonProperty("server") RpcStringOrNull server,
        @JsonProperty("ssl_ca") String ssl_ca
  ) implements Response {
    public WsGetConfigResponse(com.typesafe.config.Config config) {
      this(
            config.getBoolean("enabled"), 
            RpcStringOrNull.of(config.getString("server").isEmpty() ? null : config.getString("server")), 
            config.getString("ssl_ca"));
    }
    public @NotNull WsGetConfigResponse withEnable(boolean enable) {
      return new WsGetConfigResponse(enable, server, ssl_ca);
    }
    public @NotNull WsGetConfigResponse withServer(@Nullable String server) {
      return new WsGetConfigResponse(enable, RpcStringOrNull.of(server), ssl_ca);
    }
    public @NotNull WsGetConfigResponse withSslCa(@Nullable String ssl_ca) {
      return new WsGetConfigResponse(enable, server, ssl_ca);
    }
    
    @Override public @NotNull String toString() { return Rpc.toString(this); }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record WsSetConfig(
        @JsonProperty("id") Long id,
        @JsonProperty("method") String method,
        @JsonProperty("src") String src,
        @JsonProperty("dst") String dst,
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
    @Override public @NotNull String toString() { return Rpc.toString(this); }
  }

  public record RpcNull() {}
  
  public record RpcStringOrNull(
        String value
  ) {
    public static RpcStringOrNull of() {
      return new RpcStringOrNull(null);
    }
    public static RpcStringOrNull of(@Nullable String value) {
      return new RpcStringOrNull(value);
    }
  }

  public record RpcDoubleOrNull(
        Double value
  ) {
    public static RpcDoubleOrNull of() {
      return new RpcDoubleOrNull(null);
    }
    public static RpcDoubleOrNull of(@Nullable Double value) {
      return new RpcDoubleOrNull(value);
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
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#0.00", DecimalFormatSymbols.getInstance(Locale.ENGLISH));

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

  private static class RpcDoubleOrNullSerializer extends JsonSerializer<RpcDoubleOrNull> {
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#0.00", DecimalFormatSymbols.getInstance(Locale.ENGLISH));

    @Override
    public void serialize(RpcDoubleOrNull value, JsonGenerator jsonGenerator, SerializerProvider provider) throws IOException {
      if (value != null && value.value() != null) {
        jsonGenerator.writeNumber(DECIMAL_FORMAT.format(value));
      } else {
        jsonGenerator.writeNull();
      }
    }
  }

  private static class DurationSerializer extends JsonSerializer<Duration> {
    @Override
    public void serialize(Duration value, JsonGenerator jsonGenerator, SerializerProvider provider) throws IOException {
      if (null == value) {
        jsonGenerator.writeNull();
      } else {
        jsonGenerator.writeString(value.toString());
      }
    }
  }
}
