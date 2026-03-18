package com.deigmueller.uni_meter.output;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.text.IsEmptyString.isEmptyOrNullString;
import java.util.List;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.http.javadsl.server.Directives;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import com.deigmueller.uni_meter.application.UniMeter;
import com.deigmueller.uni_meter.common.utils.NetUtils;
import com.deigmueller.uni_meter.mdns.MDnsRegistrator;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

class OutputDeviceTest {

  private ActorTestKit testKit;

  @AfterEach
  void tearDown() {
    if (testKit != null) {
      testKit.shutdownTestKit();
    }
  }

  // ---------------------------------------------------------------------------
  // Tests for the static resolveAnnouncedIpAddress(Config, Supplier) overload
  // ---------------------------------------------------------------------------

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

  // ---------------------------------------------------------------------------
  // Tests for the instance resolveAnnouncedIpAddress() method
  // (reads uni-meter.mdns from ActorSystem config and calls resolveMdnsFallbackIpAddress)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("instance resolveAnnouncedIpAddress uses configured ip-address from system config")
  void instanceResolveAnnouncedIpAddressUsesConfiguredIpAddress() {
    Config systemConfig = buildSystemConfig("192.168.1.10", "", "0.0.0.0");
    testKit = ActorTestKit.create(systemConfig);
    String result = spawnAndResolve(testKit, systemConfig);
    assertThat(result, is("192.168.1.10"));
  }

  @Test
  @DisplayName("instance resolveAnnouncedIpAddress falls back to device interface address")
  void instanceResolveAnnouncedIpAddressUsesDeviceInterface() {
    Config systemConfig = buildSystemConfig("", "", "192.168.178.50");
    testKit = ActorTestKit.create(systemConfig);
    String result = spawnAndResolve(testKit, systemConfig);
    assertThat(result, is("192.168.178.50"));
  }

  @Test
  @DisplayName("instance resolveAnnouncedIpAddress falls back to primary IP when interface is unspecified")
  void instanceResolveAnnouncedIpAddressFallsBackToPrimaryIp() {
    Config systemConfig = buildSystemConfig("", "", "0.0.0.0");
    testKit = ActorTestKit.create(systemConfig);
    String result = spawnAndResolve(testKit, systemConfig);
    // The exact IP depends on the machine, but it must be a non-empty address
    assertThat(result, is(not(isEmptyOrNullString())));
  }

  /**
   * Builds a minimal system config with the given mdns ip-address, ip-interface
   * and the device-level interface (used by resolveMdnsFallbackIpAddress).
   */
  private static Config buildSystemConfig(String mdnsIpAddress, String mdnsIpInterface, String deviceInterface) {
    return ConfigFactory.parseString("""
        uni-meter.mdns {
          ip-address = "%s"
          ip-interface = "%s"
        }
        test-output-device {
          interface = "%s"
          forget-interval = 1m
          default-voltage = 230
          default-frequency = 50
          default-client-power-factor = 1.0
          power-offset-total = 0
          power-offset-l1 = 0
          power-offset-l2 = 0
          power-offset-l3 = 0
          usage-constraint-init-duration = 60s
        }
        """.formatted(mdnsIpAddress, mdnsIpInterface, deviceInterface)).withFallback(ConfigFactory.load());
  }

  /**
   * Spawns a TestOutputDevice actor inside the given testKit, lets it capture
   * its own resolveAnnouncedIpAddress() result and returns it.
   */
  private static String spawnAndResolve(ActorTestKit testKit, Config systemConfig) {
    Config deviceConfig = systemConfig.getConfig("test-output-device");
    ActorRef<UniMeter.Command> controllerDummy = testKit.createTestProbe(UniMeter.Command.class).ref();
    ActorRef<MDnsRegistrator.Command> mdnsDummy = testKit.createTestProbe(MDnsRegistrator.Command.class).ref();

    var resultProbe = testKit.createTestProbe(CaptureResult.class);
    testKit.spawn(TestOutputDevice.create(controllerDummy, mdnsDummy, deviceConfig, resultProbe.ref()));

    return resultProbe.receiveMessage().ipAddress();
  }

  /** Simple message to capture the resolved IP address out of the actor. */
  record CaptureResult(String ipAddress) {
  }

  /** Minimal concrete OutputDevice subclass for testing. */
  static class TestOutputDevice extends OutputDevice {

    static Behavior<Command> create(ActorRef<UniMeter.Command> controller, ActorRef<MDnsRegistrator.Command> mdnsRegistrator, Config config, ActorRef<CaptureResult> replyTo) {
      return Behaviors.setup(ctx -> new TestOutputDevice(ctx, controller, mdnsRegistrator, config, replyTo));
    }

    private TestOutputDevice(ActorContext<Command> context, ActorRef<UniMeter.Command> controller, ActorRef<MDnsRegistrator.Command> mdnsRegistrator, Config config,
        ActorRef<CaptureResult> replyTo) {
      super(context, controller, mdnsRegistrator, config, (logger, cfgList, map) -> {
      });
      // resolveAnnouncedIpAddress() is called inside the parent constructor via
      // this.announcedIpAddress = resolveAnnouncedIpAddress(); – capture it here.
      replyTo.tell(new CaptureResult(getAnnouncedIpAddress()));
    }

    @Override
    protected @NotNull org.apache.pekko.http.javadsl.server.Route createRoute() {
      return Directives.reject();
    }

    @Override
    protected void eventPowerDataChanged() {
      // NOOP
    }

  }
}
