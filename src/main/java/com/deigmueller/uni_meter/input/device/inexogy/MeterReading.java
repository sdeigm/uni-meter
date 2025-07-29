package com.deigmueller.uni_meter.input.device.inexogy;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MeterReading(
    @JsonProperty("time") long time,
    @JsonProperty("values") MeterValues values
) {}
