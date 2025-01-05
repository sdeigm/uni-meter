package com.deigmueller.uni_meter.input.device.shrdzm;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public record ShrDzmPacket(
      @JsonProperty("id") String id,
      @JsonProperty("data") Map<String,String> data
) {}
