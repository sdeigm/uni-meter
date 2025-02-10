package com.deigmueller.uni_meter.input.device.common.generic;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

public interface ChannelReader {
  @NotNull String getChannel();
  
  @Nullable Double getValue(@NotNull Logger logger, @NotNull String payload);
}
