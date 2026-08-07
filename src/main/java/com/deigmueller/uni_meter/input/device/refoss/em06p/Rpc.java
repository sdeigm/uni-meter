package com.deigmueller.uni_meter.input.device.refoss.em06p;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class Rpc {
    public static @NotNull ObjectMapper createObjectMapper() {
        return new ObjectMapper();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmStatusGetStatus(
            @JsonProperty("id") int id,
            @JsonProperty("current") double current,
            @JsonProperty("voltage") double voltage,
            @JsonProperty("power") double power,
            @JsonProperty("pf") double pf,
            @JsonProperty("month_energy") double monthEnergy,
            @JsonProperty("month_ret_energy") double monthRetEnergy,
            @JsonProperty("week_energy") double weekEnergy,
            @JsonProperty("week_ret_energy") double weekRetEnergy,
            @JsonProperty("day_energy") double dayEnergy,
            @JsonProperty("day_ret_energy") double dayRetEnergy
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmStatusGetResponse(
            @JsonProperty("status") List<EmStatusGetStatus> status
    ) {}
}
