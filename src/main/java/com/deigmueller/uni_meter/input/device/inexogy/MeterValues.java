/*
 * Copyright (C) 2018-2023 layline.io GmbH <http://www.layline.io>
 */

package com.deigmueller.uni_meter.input.device.inexogy;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MeterValues (
    @JsonProperty("energy") Double energy,
    @JsonProperty("energyOut") Double energyOut,
    @JsonProperty("voltage1") Double voltage1,
    @JsonProperty("voltage2") Double voltage2,
    @JsonProperty("voltage3") Double voltage3,
    @JsonProperty("power1") Double power1,
    @JsonProperty("power2") Double power2,
    @JsonProperty("power3") Double power3,
    @JsonProperty("power") double power
) {}
