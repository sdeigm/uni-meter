package com.deigmueller.uni_meter.output.device.mqtt;

import org.jetbrains.annotations.NotNull;

/**
 * Interface for classes that write to a topic.
 */
public interface TopicWriter {
  @NotNull String getTopic();
}
