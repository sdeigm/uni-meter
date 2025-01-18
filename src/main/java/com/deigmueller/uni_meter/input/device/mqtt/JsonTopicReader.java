package com.deigmueller.uni_meter.input.device.mqtt;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.typesafe.config.Config;
import org.apache.pekko.util.ByteString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class JsonTopicReader extends BaseTopicReader{
  // Instance members
  private final String jsonPath;
  
  public JsonTopicReader(@NotNull Config config) {
    super(config);
    this.jsonPath = config.getString("json-path");
  }

  @Override
  public @Nullable Double getValue(@NotNull ByteString payload) {
    try {
      Object value = JsonPath.read(payload.utf8String(), jsonPath);
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
}
