package com.deigmueller.uni_meter.input.device.mqtt;

import com.deigmueller.uni_meter.input.device.common.generic.ChannelReader;
import org.jetbrains.annotations.NotNull;

public interface TopicReader extends ChannelReader {
  @NotNull String getTopic();
}
