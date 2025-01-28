package com.deigmueller.uni_meter.input.device.common.generic;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.typesafe.config.Config;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class JsonChannelReader extends BaseChannelReader {
  // Instance members
  private final String jsonPath;
  
  public JsonChannelReader(@NotNull Config config) {
    super(config);
    this.jsonPath = config.getString("json-path");
  }

  @Override
  public @Nullable Double getValue(@NotNull String payload) {
    try {
      Object value = JsonPath.read(payload, jsonPath);
      if (value == null) {
        return null;
      }
      
      if (value instanceof List<?> list) {
        if (list.isEmpty()) {
          return null;
        }
        
        value = list.get(0);
      }
      
      if (value instanceof Number number) {
        return number.doubleValue() * getScale();
      }
      
      return Double.parseDouble(value.toString()) * getScale();
    } catch (PathNotFoundException | NumberFormatException exception) {
      return null;
    }
  }

  public String toString() {
    return "JsonChannelReader(channel=" + this.getChannel() + ", scale=" + this.getScale() + ", jsonPath=" + this.jsonPath + ")";
  }
}
