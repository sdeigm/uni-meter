package com.deigmueller.uni_meter.input.device.sma.energy_meter;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

@Getter
@AllArgsConstructor(staticName = "of")
public class ObisChannel {
  // Class members
  public static final long CHANNEL_1_4_0 = 0x00010400L;
  public static final long CHANNEL_1_8_0 = 0x00010800L;
  public static final long CHANNEL_2_4_0 = 0x00020400L;
  public static final long CHANNEL_2_8_0 = 0x00020800L;
  public static final long CHANNEL_3_4_0 = 0x00030400L;
  public static final long CHANNEL_3_8_0 = 0x00030800L;
  public static final long CHANNEL_4_4_0 = 0x00040400L;
  public static final long CHANNEL_4_8_0 = 0x00040800L;
  public static final long CHANNEL_9_4_0 = 0x00090400L;
  public static final long CHANNEL_9_8_0 = 0x00090800L;
  public static final long CHANNEL_10_4_0 = 0x000A0400L;
  public static final long CHANNEL_10_8_0 = 0x000A0800L;
  public static final long CHANNEL_13_4_0 = 0x000D0400L;
  public static final long CHANNEL_14_4_0 = 0x000E0400L;
  public static final long CHANNEL_21_4_0 = 0x00150400L;
  public static final long CHANNEL_21_8_0 = 0x00150800L;
  public static final long CHANNEL_22_4_0 = 0x00160400L;
  public static final long CHANNEL_22_8_0 = 0x00160800L;
  public static final long CHANNEL_23_4_0 = 0x00170400L;
  public static final long CHANNEL_23_8_0 = 0x00170800L;
  public static final long CHANNEL_24_4_0 = 0x00180400L;
  public static final long CHANNEL_24_8_0 = 0x00180800L;
  public static final long CHANNEL_29_4_0 = 0x001D0400L;
  public static final long CHANNEL_29_8_0 = 0x001D0800L;
  public static final long CHANNEL_30_4_0 = 0x001E0400L;
  public static final long CHANNEL_30_8_0 = 0x001E0800L;
  public static final long CHANNEL_31_4_0 = 0x001F0400L;
  public static final long CHANNEL_32_4_0 = 0x00200400L;
  public static final long CHANNEL_33_4_0 = 0x00210400L;
  public static final long CHANNEL_41_4_0 = 0x00290400L;
  public static final long CHANNEL_41_8_0 = 0x00290800L;
  public static final long CHANNEL_42_4_0 = 0x002A0400L;
  public static final long CHANNEL_42_8_0 = 0x002A0800L;
  public static final long CHANNEL_43_4_0 = 0x002B0400L;
  public static final long CHANNEL_43_8_0 = 0x002B0800L;
  public static final long CHANNEL_44_4_0 = 0x002C0400L;
  public static final long CHANNEL_44_8_0 = 0x002C0800L;
  public static final long CHANNEL_49_4_0 = 0x00310400L;
  public static final long CHANNEL_49_8_0 = 0x00310800L;
  public static final long CHANNEL_50_4_0 = 0x00320400L;
  public static final long CHANNEL_50_8_0 = 0x00320800L;
  public static final long CHANNEL_51_4_0 = 0x00330400L;
  public static final long CHANNEL_52_4_0 = 0x00340400L;
  public static final long CHANNEL_53_4_0 = 0x00350400L;
  public static final long CHANNEL_61_4_0 = 0x003D0400L;
  public static final long CHANNEL_61_8_0 = 0x003D0800L;
  public static final long CHANNEL_62_4_0 = 0x003E0400L;
  public static final long CHANNEL_62_8_0 = 0x003E0800L;
  public static final long CHANNEL_63_4_0 = 0x003F0400L;
  public static final long CHANNEL_63_8_0 = 0x003F0800L;
  public static final long CHANNEL_64_4_0 = 0x00400400L;
  public static final long CHANNEL_64_8_0 = 0x00400800L;
  public static final long CHANNEL_69_4_0 = 0x00450400L;
  public static final long CHANNEL_69_8_0 = 0x00450800L;
  public static final long CHANNEL_70_4_0 = 0x00460400L;
  public static final long CHANNEL_70_8_0 = 0x00460800L;
  public static final long CHANNEL_71_4_0 = 0x00470400L;
  public static final long CHANNEL_72_4_0 = 0x00480400L;
  public static final long CHANNEL_73_4_0 = 0x00490400L;
  public static final long CHANNEL_144_0_0 = 0x90000000L;
  public static final long CHANNEL_0_0_0 = 0x00000000L;
  
  private static final Map<Long, ObisChannel> CHANNELS = new HashMap<>();
  static {
    CHANNELS.put( CHANNEL_1_4_0, ObisChannel.of("1:1.4.0", "P-active power +", 4, 10));
    CHANNELS.put( CHANNEL_1_8_0, ObisChannel.of("1:1.8.0", "Meter P-active work +", 8, 3600000));
    CHANNELS.put( CHANNEL_2_4_0, ObisChannel.of("1:2.4.0", "P-active power -", 4, 10));
    CHANNELS.put( CHANNEL_2_8_0, ObisChannel.of("1:2.8.0", "Meter P-active work -", 8, 3600000));
    CHANNELS.put( CHANNEL_3_4_0, ObisChannel.of("1:3.4.0", "Q-reactive power +", 4, 10));
    CHANNELS.put( CHANNEL_3_8_0, ObisChannel.of("1:3.8.0", "Meter Q-reactive work +", 8, 3600000));
    CHANNELS.put( CHANNEL_4_4_0, ObisChannel.of("1:4.4.0", "Q-reactive power -", 4, 10));
    CHANNELS.put( CHANNEL_4_8_0, ObisChannel.of("1:4.8.0", "Meter Q-reactive work -", 8, 3600000));
    CHANNELS.put( CHANNEL_9_4_0, ObisChannel.of("1:9.4.0", "S-apparent power +", 4, 10));
    CHANNELS.put( CHANNEL_9_8_0, ObisChannel.of("1:9.8.0", "Meter S-apparent work +", 8, 3600000));
    CHANNELS.put(CHANNEL_10_4_0, ObisChannel.of("1:10.4.0", "S-apparent power -", 4, 10));
    CHANNELS.put(CHANNEL_10_8_0, ObisChannel.of("1:10.8.0", "Meter S-apparent work -", 8, 3600000));
    CHANNELS.put(CHANNEL_13_4_0, ObisChannel.of("1:13.4.0", "Power factor", 4, 1000));
    CHANNELS.put(CHANNEL_14_4_0, ObisChannel.of("1:14.4.0", "Grid frequency", 4, 1000));
    CHANNELS.put(CHANNEL_21_4_0, ObisChannel.of("1:21.4.0", "L1 P-active power +", 4, 10));
    CHANNELS.put(CHANNEL_21_8_0, ObisChannel.of("1:21.8.0", "L1 Meter P-active work +", 8, 3600000));
    CHANNELS.put(CHANNEL_22_4_0, ObisChannel.of("1:22.4.0", "L1 P-active power -", 4, 10));
    CHANNELS.put(CHANNEL_22_8_0, ObisChannel.of("1:22.8.0", "L1 Meter P-active work -", 8, 3600000));
    CHANNELS.put(CHANNEL_23_4_0, ObisChannel.of("1:23.4.0", "L1 Q-reactive power +", 4, 10));
    CHANNELS.put(CHANNEL_23_8_0, ObisChannel.of("1:23.8.0", "L1 Meter Q-reactive work +", 8, 3600000));
    CHANNELS.put(CHANNEL_24_4_0, ObisChannel.of("1:24.4.0", "L1 Q-reactive power -", 4, 10));
    CHANNELS.put(CHANNEL_24_8_0, ObisChannel.of("1:24.8.0", "L1 Meter Q-reactive work -", 8, 3600000));
    CHANNELS.put(CHANNEL_29_4_0, ObisChannel.of("1:29.4.0", "L1 S-apparent power +", 4, 10));
    CHANNELS.put(CHANNEL_29_8_0, ObisChannel.of("1:29.8.0", "L1 Meter S-apparent work +", 8, 3600000));
    CHANNELS.put(CHANNEL_30_4_0, ObisChannel.of("1:30.4.0", "L1 S-apparent power -", 4, 10));
    CHANNELS.put(CHANNEL_30_8_0, ObisChannel.of("1:30.8.0", "L1 Meter S-apparent work -", 8, 3600000));
    CHANNELS.put(CHANNEL_31_4_0, ObisChannel.of("1:31.4.0", "L1 Amperage", 4, 1000));
    CHANNELS.put(CHANNEL_32_4_0, ObisChannel.of("1:32.4.0", "L1 Voltage", 4, 1000));
    CHANNELS.put(CHANNEL_33_4_0, ObisChannel.of("1:33.4.0", "L1 Power factor", 4, 1000));
    CHANNELS.put(CHANNEL_41_4_0, ObisChannel.of("1:41.4.0", "L2 P-active power +", 4, 10));
    CHANNELS.put(CHANNEL_41_8_0, ObisChannel.of("1:41.8.0", "L2 Meter P-active work +", 8, 3600000));
    CHANNELS.put(CHANNEL_42_4_0, ObisChannel.of("1:42.4.0", "L2 P-active power -", 4, 10));
    CHANNELS.put(CHANNEL_42_8_0, ObisChannel.of("1:42.8.0", "L2 Meter P-active work -", 8, 3600000));
    CHANNELS.put(CHANNEL_43_4_0, ObisChannel.of("1:43.4.0", "L2 Q-reactive power +", 4, 10));
    CHANNELS.put(CHANNEL_43_8_0, ObisChannel.of("1:43.8.0", "L2 Meter Q-reactive work +", 8, 3600000));
    CHANNELS.put(CHANNEL_44_4_0, ObisChannel.of("1:44.4.0", "L2 Q-reactive power -", 4, 10));
    CHANNELS.put(CHANNEL_44_8_0, ObisChannel.of("1:44.8.0", "L2 Meter Q-reactive work -", 8, 3600000));
    CHANNELS.put(CHANNEL_49_4_0, ObisChannel.of("1:49.4.0", "L2 S-apparent power +", 4, 10));
    CHANNELS.put(CHANNEL_49_8_0, ObisChannel.of("1:49.8.0", "L2 Meter S-apparent work +", 8, 3600000));
    CHANNELS.put(CHANNEL_50_4_0, ObisChannel.of("1:50.4.0", "L2 S-apparent power -", 4, 10));
    CHANNELS.put(CHANNEL_50_8_0, ObisChannel.of("1:50.8.0", "L2 Meter S-apparent work -", 8, 3600000));
    CHANNELS.put(CHANNEL_51_4_0, ObisChannel.of("1:51.4.0", "L2 Amperage", 4, 1000));
    CHANNELS.put(CHANNEL_52_4_0, ObisChannel.of("1:52.4.0", "L2 Voltage", 4, 1000));
    CHANNELS.put(CHANNEL_53_4_0, ObisChannel.of("1:53.4.0", "L2 Power factor", 4, 1000));
    CHANNELS.put(CHANNEL_61_4_0, ObisChannel.of("1:61.4.0", "L3 P-active power +", 4, 10));
    CHANNELS.put(CHANNEL_61_8_0, ObisChannel.of("1:61.8.0", "L3 Meter P-active work +", 8, 3600000));
    CHANNELS.put(CHANNEL_62_4_0, ObisChannel.of("1:62.4.0", "L3 P-active power-", 4, 10));
    CHANNELS.put(CHANNEL_62_8_0, ObisChannel.of("1:62.8.0", "L3 Meter P-active work -", 8, 3600000));
    CHANNELS.put(CHANNEL_63_4_0, ObisChannel.of("1:63.4.0", "L3 Q-reactive power +", 4, 10));
    CHANNELS.put(CHANNEL_63_8_0, ObisChannel.of("1:63.8.0", "L3 Meter Q-reactive work +", 8, 3600000));
    CHANNELS.put(CHANNEL_64_4_0, ObisChannel.of("1:64.4.0", "L3 Q-reactive power -", 4, 10));
    CHANNELS.put(CHANNEL_64_8_0, ObisChannel.of("1:64.8.0", "L3 Meter Q-reactive work -", 8, 3600000));
    CHANNELS.put(CHANNEL_69_4_0, ObisChannel.of("1:69.4.0", "L3 S-apparent power +", 4, 10));
    CHANNELS.put(CHANNEL_69_8_0, ObisChannel.of("1:69.8.0", "L3 Meter S-apparent work +", 8, 3600000));
    CHANNELS.put(CHANNEL_70_4_0, ObisChannel.of("1:70.4.0", "L3 S-apparent power -", 4, 10));
    CHANNELS.put(CHANNEL_70_8_0, ObisChannel.of("1:70.8.0", "L3 Meter S-apparent work -", 8, 3600000));
    CHANNELS.put(CHANNEL_71_4_0, ObisChannel.of("1:71.4.0", "L3 THD", 4, 1000));
    CHANNELS.put(CHANNEL_72_4_0, ObisChannel.of("1:72.4.0", "L3 Voltage", 4, 1000));
    CHANNELS.put(CHANNEL_73_4_0, ObisChannel.of("1:73.4.0", "L3 Power factor", 4, 1000));
    CHANNELS.put(CHANNEL_144_0_0, ObisChannel.of("144:0.0.0", "Software version raw", 4, 1));
    CHANNELS.put(CHANNEL_0_0_0,  ObisChannel.of("1:0.0.0", "End of telegram", 0, 1));
  }

  // Instance members
  private final String identifier;
  private final String description;
  private int dataLength;
  private int divisor;
  
  public double convertRawValue(@NotNull BigInteger rawValue) {
    return rawValue.doubleValue() / divisor;
  }
  
  public static @Nullable ObisChannel get(long identifier) {
    return CHANNELS.get(identifier);
  }
}
