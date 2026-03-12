package com.deigmueller.uni_meter.input.device.tibber.pulse;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

class PulseTest {
  @Test
  void testFindPowerEntryText() {
    Double powerEntry = Pulse.findEntry(Pulse.POWER_16_7_PATTERN, getTestLines());
    assertThat(powerEntry, is(equalTo(-458.90)));
  }
  
  @Test
  void testFindEnergyImportText() {
    Double energyImport = Pulse.findEntry(Pulse.ENERGY_IMPORT_PATTERN, getTestLines());
    assertThat(energyImport, is(equalTo(3902.58359727)));
  }

  @Test
  void testFindEnergyExportText() {
    Double energyImport = Pulse.findEntry(Pulse.ENERGY_EXPORT_PATTERN, getTestLines());
    assertThat(energyImport, is(equalTo(1267.59172376)));
  }

  static String[] getTestLines() {
    return new String[] {
          "1-0:0.0.0*255(1EBZ0101937879)",
          "1-0:96.1.0*255(1EBZ0101937879)",
          "1-0:1.8.0*255(003902.58359727*kWh)",
          "1-0:2.8.0*255(001267.59172376*kWh)",
          "1-0:16.7.0*255(-000458.90*W)",
          "1-0:36.7.0*255(000344.88*W)",
          "1-0:56.7.0*255(000114.02*W)",
          "1-0:76.7.0*255(000000.00*W)",
          "1-0:96.5.0*255(001C0104)",
          "0-0:96.8.0*255(055BD83E)"
    };
  }
}