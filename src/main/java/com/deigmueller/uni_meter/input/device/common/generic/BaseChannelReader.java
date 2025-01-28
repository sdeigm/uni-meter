package com.deigmueller.uni_meter.input.device.common.generic;

import com.typesafe.config.Config;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

@Getter
public abstract class BaseChannelReader implements ChannelReader {
  // Instance members
  private final String channel;
  private final Double scale;
  
  public BaseChannelReader(@NotNull Config config) {
    this.channel = config.getString("channel");
    if (config.hasPath("scale")) {
      this.scale = config.getDouble("scale");
    } else {
      this.scale = 1.0;
    }
  }
  
  public String toString() {
    return "BaseChannelReader(channel=" + this.getChannel() + ", scale=" + this.getScale() + ")";
  }
}
