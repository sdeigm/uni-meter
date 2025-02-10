package com.deigmueller.uni_meter.output.device.mqtt;

import com.typesafe.config.Config;
import lombok.Getter;
import org.apache.commons.text.StringSubstitutor;
import org.apache.commons.text.lookup.StringLookup;
import org.jetbrains.annotations.NotNull;

/**
 * This class writes a value to a topic.
 */
@Getter
public class TopicWriter {
  // Instance members
  private final String topic;
  private final String value;
  
  public TopicWriter(@NotNull Config config) {
    this.topic = config.getString("topic");
    this.value = config.getString("value");
  }
  
  public String getValue(@NotNull StringSubstitutor stringSubstitutor) {
    return stringSubstitutor.replace(value);
  }
  
  
}
