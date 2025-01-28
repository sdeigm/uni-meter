package com.deigmueller.uni_meter.input.device.mqtt;

import com.deigmueller.uni_meter.input.device.common.generic.ValueChannelReader;
import com.typesafe.config.Config;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

@Getter
public class ValueTopicReader extends ValueChannelReader implements TopicReader {
  // Instance members
  private final String topic;

  public ValueTopicReader(@NotNull Config config) {
    super(config);
    this.topic = config.getString("topic");
  }
}
