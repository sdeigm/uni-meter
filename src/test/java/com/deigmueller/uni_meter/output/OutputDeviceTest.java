package com.deigmueller.uni_meter.output;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import com.deigmueller.uni_meter.common.utils.NetUtils;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

class OutputDeviceTest {

  @Test
  @DisplayName("resolveAnnouncedIpAddress uses configured ip-address with highest priority")
  void resolveAnnouncedIpAddressUsesConfiguredIpAddress() {
    try (MockedStatic<NetUtils> mockedNetUtils = Mockito.mockStatic(NetUtils.class)) {
      mockedNetUtils.when(NetUtils::detectPrimaryIpAddress).thenReturn("10.0.0.5");

      String result = resolveAnnouncedIpAddress(" 192.168.1.10 ", "");

      assertThat(result, is("192.168.1.10"));
    }
  }

  @Test
  @DisplayName("resolveAnnouncedIpAddress resolves IP from configured interface")
  void resolveAnnouncedIpAddressUsesConfiguredIpInterface() {
    try (MockedStatic<NetUtils> mockedNetUtils = Mockito.mockStatic(NetUtils.class)) {
      mockedNetUtils.when(NetUtils::listNetworkInterfaceNames).thenReturn(List.of("eth0", "wlan0"));
      mockedNetUtils.when(() -> NetUtils.detectIpAddressFromInterface("eth0")).thenReturn("192.168.178.22");

      String result = resolveAnnouncedIpAddress("", "eth0");

      assertThat(result, is("192.168.178.22"));
    }
  }

  @Test
  @DisplayName("resolveAnnouncedIpAddress falls back to primary IP when configured interface is missing")
  void resolveAnnouncedIpAddressFallsBackForMissingInterface() {
    try (MockedStatic<NetUtils> mockedNetUtils = Mockito.mockStatic(NetUtils.class)) {
      mockedNetUtils.when(NetUtils::listNetworkInterfaceNames).thenReturn(List.of("eth0", "wlan0"));
      mockedNetUtils.when(NetUtils::detectPrimaryIpAddress).thenReturn("10.0.0.5");

      String result = resolveAnnouncedIpAddress("", "eth9");

      assertThat(result, is("10.0.0.5"));
    }
  }

  @Test
  @DisplayName("resolveAnnouncedIpAddress falls back to primary IP when interface has no IPv4 address")
  void resolveAnnouncedIpAddressFallsBackForInterfaceWithoutIpv4() {
    try (MockedStatic<NetUtils> mockedNetUtils = Mockito.mockStatic(NetUtils.class)) {
      mockedNetUtils.when(NetUtils::listNetworkInterfaceNames).thenReturn(List.of("eth0", "wlan0"));
      mockedNetUtils.when(() -> NetUtils.detectIpAddressFromInterface("eth0")).thenReturn(null);
      mockedNetUtils.when(NetUtils::detectPrimaryIpAddress).thenReturn("10.0.0.5");

      String result = resolveAnnouncedIpAddress("", "eth0");

      assertThat(result, is("10.0.0.5"));
    }
  }

  @Test
  @DisplayName("resolveAnnouncedIpAddress falls back to primary IP when nothing is configured")
  void resolveAnnouncedIpAddressFallsBackToPrimaryIp() {
    try (MockedStatic<NetUtils> mockedNetUtils = Mockito.mockStatic(NetUtils.class)) {
      mockedNetUtils.when(NetUtils::detectPrimaryIpAddress).thenReturn("10.0.0.5");

      String result = resolveAnnouncedIpAddress("", "");

      assertThat(result, is("10.0.0.5"));
    }
  }

  private static String resolveAnnouncedIpAddress(String ipAddress, String ipInterface) {
    Config mdnsConfig = ConfigFactory.parseString("""
        ip-address = "%s"
        ip-interface = "%s"
        """.formatted(ipAddress, ipInterface));

    return OutputDevice.resolveAnnouncedIpAddress(mdnsConfig, NetUtils::detectPrimaryIpAddress);
  }
}
