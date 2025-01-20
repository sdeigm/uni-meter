package com.deigmueller.uni_meter.input.device.mqtt;

import com.typesafe.config.Config;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

@Getter
public abstract class BaseTopicReader implements TopicReader {
  // Instance members
  private final String topic;
  private final String channel;
  private final Double scale;
  
  public BaseTopicReader(@NotNull Config config) {
    this.topic = config.getString("topic");
    this.channel = config.getString("channel");
    if (config.hasPath("scale")) {
      this.scale = config.getDouble("scale");
    } else {
      this.scale = 1.0;
    }
  }
}
