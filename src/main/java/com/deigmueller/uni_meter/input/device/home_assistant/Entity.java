package com.deigmueller.uni_meter.input.device.home_assistant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Entity(
      @JsonProperty("entity_id") String entityId,
      @JsonProperty("state") String state
) {}
