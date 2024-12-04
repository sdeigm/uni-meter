/*
 * Copyright (C) 2018-2023 layline.io GmbH <http://www.layline.io>
 */

package com.deigmueller.uni_meter.input.device.sma.energy_meter;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.Map;

public record Telegram(
      int susyId, 
      long serialNumber, 
      int version, 
      @NotNull Map<Long, BigInteger> values
) {
  public String toString() {
    return "EnergyMeterTelegram(susyId=" + this.susyId() + ", serialNumber=" + this.serialNumber() + ", version=" + 
          this.version() + ", values=" + this.values() + ")";
  }

  public @Nullable Double getActivePowerPhase1Plus() {
    return getValue(ObisChannel.CHANNEL_21_4_0);
  }

  public @Nullable Double getActivePowerPhase1Minus() {
    return getValue(ObisChannel.CHANNEL_22_4_0);
  }

  public @Nullable Double getApparentPowerPhase1Plus() {
    return getValue(ObisChannel.CHANNEL_29_4_0);
  }

  public @Nullable Double getApparentPowerPhase1Minus() {
    return getValue(ObisChannel.CHANNEL_30_4_0);
  }

  public @Nullable Double getCurrentPhase1() {
    return getValue(ObisChannel.CHANNEL_31_4_0);
  }

  public @Nullable Double getVoltagePhase1() {
    return getValue(ObisChannel.CHANNEL_32_4_0);
  }

  public @Nullable Double getConsumptionPhase1() {
    return getValue(ObisChannel.CHANNEL_21_8_0);
  }

  public @Nullable Double getProductionPhase1() {
    return getValue(ObisChannel.CHANNEL_22_8_0);
  }

  public @Nullable Double getActivePowerPhase2Plus() {
    return getValue(ObisChannel.CHANNEL_41_4_0);
  }

  public @Nullable Double getActivePowerPhase2Minus() {
    return getValue(ObisChannel.CHANNEL_42_4_0);
  }

  public @Nullable Double getApparentPowerPhase2Plus() {
    return getValue(ObisChannel.CHANNEL_49_4_0);
  }

  public @Nullable Double getApparentPowerPhase2Minus() {
    return getValue(ObisChannel.CHANNEL_50_4_0);
  }

  public @Nullable Double getCurrentPhase2() {
    return getValue(ObisChannel.CHANNEL_51_4_0);
  }

  public @Nullable Double getVoltagePhase2() {
    return getValue(ObisChannel.CHANNEL_52_4_0);
  }

  public @Nullable Double getConsumptionPhase2() {
    return getValue(ObisChannel.CHANNEL_41_8_0);
  }

  public @Nullable Double getProductionPhase2() {
    return getValue(ObisChannel.CHANNEL_42_8_0);
  }
  
  public @Nullable Double getActivePowerPhase3Plus() {
    Double result = getValue(ObisChannel.CHANNEL_61_8_0);
    if (result != null) {
      return result;
    }
    return getValue(ObisChannel.CHANNEL_61_4_0);
  }

  public @Nullable Double getActivePowerPhase3Minus() {
    return getValue(ObisChannel.CHANNEL_62_4_0);
  }

  public @Nullable Double getApparentPowerPhase3Plus() {
    return getValue(ObisChannel.CHANNEL_69_4_0);
  }

  public @Nullable Double getApparentPowerPhase3Minus() {
    return getValue(ObisChannel.CHANNEL_70_4_0);
  }

  public @Nullable Double getCurrentPhase3() {
    return getValue(ObisChannel.CHANNEL_71_4_0);
  }

  public @Nullable Double getVoltagePhase3() {
    return getValue(ObisChannel.CHANNEL_72_4_0);
  }

  public @Nullable Double getConsumptionPhase3() {
    return getValue(ObisChannel.CHANNEL_61_8_0);
  }

  public @Nullable Double getProductionPhase3() {
    return getValue(ObisChannel.CHANNEL_62_8_0);
  }

  public @Nullable Double getActivePowerTotalPlus() {
    return getValue(ObisChannel.CHANNEL_1_4_0);
  }

  public @Nullable Double getActivePowerTotalMinus() {
    return getValue(ObisChannel.CHANNEL_2_4_0);
  }

  public @Nullable Double getApparentPowerTotalPlus() {
    return getValue(ObisChannel.CHANNEL_9_4_0);
  }

  public @Nullable Double getApparentPowerTotalMinus() {
    return getValue(ObisChannel.CHANNEL_10_4_0);
  }
  
  public @Nullable Double getConsumptionTotal() {
    return getValue(ObisChannel.CHANNEL_1_8_0);
  }
  
  public @Nullable Double getProductionTotal() {
    return getValue(ObisChannel.CHANNEL_2_8_0);
  }
  
  private @Nullable Double getValue(long key) {
    Double result = null;

    BigInteger rawValue = this.values.get(key);
    if (rawValue != null) {
      ObisChannel obisChannel = ObisChannel.get(key);
      if (obisChannel != null) {
        result = obisChannel.convertRawValue(rawValue);
      }
    }

    return result;
  }

}
