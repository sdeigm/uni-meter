package com.deigmueller.uni_meter.common.utils;

import com.jayway.jsonpath.JsonPath;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Json {
  public static @Nullable Double readDoubleValue(@NotNull String jsonPath, 
                                                 @NotNull String payload,
                                                 double scale) {
    try {
      Object value = JsonPath.read(payload, jsonPath);
      if (value == null) {
        return null;
      }
      
      if (value instanceof java.util.List<?> list) {
        if (list.isEmpty()) {
          return null;
        }
        
        value = list.get(0);
      }
      
      if (value instanceof Number number) {
        return number.doubleValue() * scale;
      }
      
      return Double.parseDouble(value.toString()) * scale;
    } catch (com.jayway.jsonpath.PathNotFoundException | NumberFormatException exception) {
      return null;
    }
  }
}
