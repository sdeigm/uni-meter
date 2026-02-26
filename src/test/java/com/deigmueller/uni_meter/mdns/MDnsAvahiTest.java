package com.deigmueller.uni_meter.mdns;

import com.deigmueller.uni_meter.common.utils.NetUtils;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class MDnsAvahiTest {

  @Test
  @DisplayName("resolvePublishIpAddress uses configured ip-address with highest priority")
  void resolvePublishIpAddressUsesConfiguredIpAddress() throws Exception {
    MDnsAvahi mdnsAvahi = createMdnsAvahi(" 192.168.1.10 ", "");

    String result = invokeResolvePublishIpAddress(mdnsAvahi, createRegisterService("10.0.0.5"));

    assertThat(result, is("192.168.1.10"));
  }

  @Test
  @DisplayName("resolvePublishIpAddress resolves IP from configured interface")
  void resolvePublishIpAddressUsesConfiguredIpInterface() throws Exception {
    MDnsAvahi mdnsAvahi = createMdnsAvahi("", "eth0");

    try (MockedStatic<NetUtils> mockedNetUtils = Mockito.mockStatic(NetUtils.class)) {
      mockedNetUtils.when(NetUtils::listNetworkInterfaceNames).thenReturn(List.of("eth0", "wlan0"));
      mockedNetUtils.when(() -> NetUtils.detectIpAddressFromInterface("eth0")).thenReturn("192.168.178.22");

      String result = invokeResolvePublishIpAddress(mdnsAvahi, createRegisterService("10.0.0.5"));

      assertThat(result, is("192.168.178.22"));
    }
  }

  @Test
  @DisplayName("resolvePublishIpAddress falls back when configured interface is missing")
  void resolvePublishIpAddressFallsBackForMissingInterface() throws Exception {
    MDnsAvahi mdnsAvahi = createMdnsAvahi("", "eth9");

    try (MockedStatic<NetUtils> mockedNetUtils = Mockito.mockStatic(NetUtils.class)) {
      mockedNetUtils.when(NetUtils::listNetworkInterfaceNames).thenReturn(List.of("eth0", "wlan0"));

      String result = invokeResolvePublishIpAddress(mdnsAvahi, createRegisterService("10.0.0.5"));

      assertThat(result, is("10.0.0.5"));
    }
  }

  @Test
  @DisplayName("resolvePublishIpAddress falls back when interface has no IPv4 address")
  void resolvePublishIpAddressFallsBackForInterfaceWithoutIpv4() throws Exception {
    MDnsAvahi mdnsAvahi = createMdnsAvahi("", "eth0");

    try (MockedStatic<NetUtils> mockedNetUtils = Mockito.mockStatic(NetUtils.class)) {
      mockedNetUtils.when(NetUtils::listNetworkInterfaceNames).thenReturn(List.of("eth0", "wlan0"));
      mockedNetUtils.when(() -> NetUtils.detectIpAddressFromInterface("eth0")).thenReturn(null);

      String result = invokeResolvePublishIpAddress(mdnsAvahi, createRegisterService("10.0.0.5"));

      assertThat(result, is("10.0.0.5"));
    }
  }

  @Test
  @DisplayName("resolvePublishIpAddress falls back to registerService ip when nothing is configured")
  void resolvePublishIpAddressFallsBackToServiceIp() throws Exception {
    MDnsAvahi mdnsAvahi = createMdnsAvahi("", "");

    String result = invokeResolvePublishIpAddress(mdnsAvahi, createRegisterService("10.0.0.5"));

    assertThat(result, is("10.0.0.5"));
  }

  private static MDnsAvahi createMdnsAvahi(String ipAddress, String ipInterface) {
    Config config =
        ConfigFactory.parseString("enable-avahi-publish = false\n" + "ip-address = \"" + ipAddress + "\"\n" + "ip-interface = \"" + ipInterface + "\"\n" + "avahi-publish = \"\"");
    @SuppressWarnings("unchecked")
    ActorContext<MDnsKind.Command> context = Mockito.mock(ActorContext.class);
    return new MDnsAvahi(context, config);
  }

  private static MDnsKind.RegisterService createRegisterService(String ipAddress) {
    return new MDnsKind.RegisterService("_shelly._tcp", "shellypro3em-123456", 80, Map.of("app", "shellypro3em"), "shellypro3em-123456", ipAddress);
  }

  private static String invokeResolvePublishIpAddress(MDnsAvahi mdnsAvahi, MDnsKind.RegisterService registerService) throws Exception {
    Method resolveMethod = MDnsAvahi.class.getDeclaredMethod("resolvePublishIpAddress", MDnsKind.RegisterService.class);
    resolveMethod.setAccessible(true);
    return (String) resolveMethod.invoke(mdnsAvahi, registerService);
  }
}
