package com.deigmueller.uni_meter.input.device.mqtt;

import com.typesafe.config.Config;
import org.apache.pekko.util.ByteString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ValueTopicReader extends BaseTopicReader {
  public ValueTopicReader(@NotNull Config config) {
    super(config);
  }
  
  @Override
  public @Nullable Double getValue(@NotNull ByteString payload) {
    try {
      return Double.parseDouble(payload.utf8String()) * getScale();
    } catch (NumberFormatException exception) {
      return null;
    }
  }
}
