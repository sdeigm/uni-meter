package com.deigmueller.uni_meter.input.device.modbus.fronius;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class FroniusSmartMeterTest {
  @Test
  void parseIntSfModel() {
    ByteBuffer b = ByteBuffer.allocate(53 * 2);
    // Current (A, phases, SF -2)
    b.putShort((short) 600).putShort((short) 100).putShort((short) 200).putShort((short) 300).putShort((short) -2);
    // Voltage (avg, phases, line to line, SF -1)
    b.putShort((short) 2300).putShort((short) 2301).putShort((short) 2302).putShort((short) 2303);
    b.putShort((short) 4000).putShort((short) 4000).putShort((short) 4000).putShort((short) 4000).putShort((short) -1);
    // Frequency (SF -2)
    b.putShort((short) 5001).putShort((short) -2);
    // Real power (SF 0)
    b.putShort((short) 100).putShort((short) 230).putShort((short) -460).putShort(Short.MIN_VALUE).putShort((short) 0);
    // Apparent power (SF 0)
    b.putShort((short) 0).putShort((short) 240).putShort((short) 470).putShort((short) 0).putShort((short) 0);
    // Reactive power
    b.putShort((short) 0).putShort((short) 0).putShort((short) 0).putShort((short) 0).putShort((short) 0);
    // Power factor (percent, SF -1)
    b.putShort((short) 0).putShort((short) 958).putShort((short) -979).putShort((short) 0).putShort((short) -1);
    // Exported energy (total, phases)
    b.putInt(0).putInt(1000).putInt(2000).putInt(3000);
    // Imported energy (total, phases)
    b.putInt(0).putInt(4000).putInt(5000).putInt(6000);
    // Energy SF 0
    b.putShort((short) 0);
    b.flip();

    FroniusSmartMeter.MeterData data = FroniusSmartMeter.parseIntSfModel(b);

    assertEquals(230.0, data.l1().power(), 0.001);
    assertEquals(-460.0, data.l2().power(), 0.001);
    assertEquals(0.0, data.l3().power(), 0.001);
    assertEquals(1.0, data.l1().current(), 0.001);
    assertEquals(230.1, data.l1().voltage(), 0.001);
    assertEquals(50.01, data.frequency(), 0.001);
    assertEquals(0.958, data.l1().powerFactor(), 0.001);
    assertEquals(0.979, data.l2().powerFactor(), 0.001);
    assertEquals(4000.0, data.l1Imported(), 0.001);
    assertEquals(3000.0, data.l3Exported(), 0.001);
    assertFalse(b.hasRemaining());
  }

  @Test
  void parseFloatModel() {
    ByteBuffer b = ByteBuffer.allocate(74 * 2);
    // Current
    b.putFloat(6f).putFloat(1f).putFloat(2f).putFloat(3f);
    // Voltage (avg, phases, line to line)
    b.putFloat(230f).putFloat(230.1f).putFloat(230.2f).putFloat(230.3f);
    b.putFloat(400f).putFloat(400f).putFloat(400f).putFloat(400f);
    // Frequency
    b.putFloat(49.99f);
    // Real power
    b.putFloat(100f).putFloat(230f).putFloat(-460f).putFloat(Float.NaN);
    // Apparent power
    b.putFloat(0f).putFloat(240f).putFloat(470f).putFloat(0f);
    // Reactive power
    b.putFloat(0f).putFloat(0f).putFloat(0f).putFloat(0f);
    // Power factor
    b.putFloat(0f).putFloat(0.95f).putFloat(-97f).putFloat(0f);
    // Exported energy
    b.putFloat(0f).putFloat(1000f).putFloat(2000f).putFloat(3000f);
    // Imported energy
    b.putFloat(0f).putFloat(4000f).putFloat(5000f).putFloat(6000f);
    b.flip();

    FroniusSmartMeter.MeterData data = FroniusSmartMeter.parseFloatModel(b);

    assertEquals(230.0, data.l1().power(), 0.001);
    assertEquals(-460.0, data.l2().power(), 0.001);
    assertEquals(0.0, data.l3().power(), 0.001);
    assertEquals(230.3, data.l3().voltage(), 0.001);
    assertEquals(49.99, data.frequency(), 0.001);
    assertEquals(0.95, data.l1().powerFactor(), 0.001);
    assertEquals(0.97, data.l2().powerFactor(), 0.001);
    assertEquals(5000.0, data.l2Imported(), 0.001);
    assertEquals(1000.0, data.l1Exported(), 0.001);
    assertFalse(b.hasRemaining());
  }

  @Test
  void meterModelDetection() {
    assertTrue(FroniusSmartMeter.isIntSfMeterModel(203));
    assertTrue(FroniusSmartMeter.isFloatMeterModel(213));
    assertFalse(FroniusSmartMeter.isIntSfMeterModel(213));
    assertFalse(FroniusSmartMeter.isFloatMeterModel(1));
  }
}
