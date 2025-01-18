package com.deigmueller.uni_meter.input.device.mqtt;

import org.apache.pekko.util.ByteString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface TopicReader {
  @NotNull String getTopic();
  
  @NotNull String getChannel();
  
  @Nullable Double getValue(@NotNull ByteString payload);
}
