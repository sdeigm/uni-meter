package com.deigmueller.uni_meter.input.device.failover;

import com.deigmueller.uni_meter.input.InputDevice;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

class FailoverTest {
  private static final String PRIMARY = "test-sources.primary";
  private static final String SECONDARY = "test-sources.secondary";

  private ActorTestKit testKit;
  private ManualClock clock;
  private TestProbe<OutputDevice.Command> output;
  private TestProbe<OutputDevice.Ack> ackProbe;
  private TestProbe<OutputDevice.GetStatusResponse> statusProbe;
  private ActorRef<OutputDevice.Command> primary;
  private ActorRef<OutputDevice.Command> secondary;
  private int nextMessageId = 1;

  @BeforeEach
  void setUp() {
    testKit = ActorTestKit.create(ConfigFactory.parseString("""
          test-sources {
            primary { type = "Fake" }
            secondary { type = "Fake" }
            nested { type = "Failover" }
          }
          """).withFallback(ConfigFactory.load()));
    clock = new ManualClock(Instant.parse("2026-01-01T00:00:00Z"));
    output = testKit.createTestProbe();
    ackProbe = testKit.createTestProbe();
    statusProbe = testKit.createTestProbe();
  }

  @AfterEach
  void tearDown() {
    testKit.shutdownTestKit();
  }

  @Test
  @DisplayName("forwards only the readings of the primary device while it is healthy")
  void forwardsPrimaryWhileHealthy() {
    start("");

    primary.tell(power(100));
    assertThat(powers(sync(primary)), contains(100.0));

    secondary.tell(power(-500));
    assertThat(sync(secondary), is(empty()));
  }

  @Test
  @DisplayName("switches to the secondary device if the primary device stops delivering readings")
  void switchesOnTimeout() {
    start("");

    primary.tell(power(100));
    assertThat(powers(sync(primary)), contains(100.0));

    clock.advance(Duration.ofSeconds(11));
    secondary.tell(power(-500));
    assertThat(powers(sync(secondary)), contains(-500.0));

    // late readings of the primary device are not forwarded anymore
    primary.tell(power(101));
    assertThat(sync(primary), is(empty()));
  }

  @Test
  @DisplayName("does not switch as long as the primary device delivers readings within the timeout")
  void staysOnPrimaryWithinTimeout() {
    start("");

    for (int i = 0; i < 10; i++) {
      clock.advance(Duration.ofSeconds(9));
      primary.tell(power(100 + i));
      assertThat(powers(sync(primary)), contains(100.0 + i));
    }
  }

  @Test
  @DisplayName("switches to the secondary device if the readings of the primary device are frozen")
  void switchesOnFrozenReadings() {
    start("");

    primary.tell(power(100));
    assertThat(powers(sync(primary)), contains(100.0));

    // the same value for 54 seconds is still accepted
    for (int i = 0; i < 6; i++) {
      clock.advance(Duration.ofSeconds(9));
      primary.tell(power(100));
      assertThat(powers(sync(primary)), contains(100.0));
    }

    // after more than 60 seconds the primary device is considered frozen
    clock.advance(Duration.ofSeconds(9));
    primary.tell(power(100));
    assertThat(sync(primary), is(empty()));

    secondary.tell(power(-500));
    assertThat(powers(sync(secondary)), contains(-500.0));
  }

  @Test
  @DisplayName("frozen detection can be disabled")
  void frozenDetectionDisabled() {
    start("frozen-timeout = 0s");

    for (int i = 0; i < 20; i++) {
      clock.advance(Duration.ofSeconds(9));
      primary.tell(power(100));
      assertThat(powers(sync(primary)), contains(100.0));
    }
  }

  @Test
  @DisplayName("switches back only after the primary device has been healthy for the switch-back delay")
  void switchesBackAfterDelay() {
    start("");
    failOverByTimeout();

    // primary device recovers, but is interrupted again before the switch-back delay has expired
    primary.tell(power(200));
    assertThat(sync(primary), is(empty()));
    assertThat(feedPrimary(5, 201), is(empty()));            // healthy for 25 seconds

    clock.advance(Duration.ofSeconds(11));                            // no readings for 11 seconds
    secondary.tell(power(-501));
    assertThat(powers(sync(secondary)), contains(-501.0));

    // primary device recovers again, the switch-back delay starts from the beginning
    primary.tell(power(300));
    assertThat(sync(primary), is(empty()));
    assertThat(feedPrimary(5, 301), is(empty()));            // healthy for 25 seconds

    clock.advance(Duration.ofSeconds(5));                             // healthy for 30 seconds
    primary.tell(power(400));
    assertThat(powers(sync(primary)), contains(400.0));

    secondary.tell(power(-600));
    assertThat(sync(secondary), is(empty()));
  }

  @Test
  @DisplayName("forwards the latest readings of the secondary device immediately when switching")
  void forwardsCachedReadingsOnSwitch() {
    start("");

    primary.tell(power(100));
    assertThat(powers(sync(primary)), contains(100.0));

    clock.advance(Duration.ofSeconds(8));
    secondary.tell(power(-500));
    assertThat(sync(secondary), is(empty()));

    // the switch is detected by the periodic health check, no further message is needed
    clock.advance(Duration.ofSeconds(3));
    OutputDevice.Command command = output.receiveMessage(Duration.ofSeconds(3));
    assertThat(command, instanceOf(OutputDevice.NotifyTotalPowerData.class));
    assertThat(((OutputDevice.NotifyTotalPowerData) command).data().power(), is(-500.0));
  }

  @Test
  @DisplayName("does not forward outdated readings of the secondary device when switching")
  void doesNotForwardOutdatedReadingsOnSwitch() {
    start("");

    secondary.tell(power(-500));
    assertThat(sync(secondary), is(empty()));

    // the periodic health check switches to the secondary device, but its reading is too old to be forwarded
    clock.advance(Duration.ofSeconds(11));
    output.expectNoMessage(Duration.ofMillis(300));

    secondary.tell(power(-501));
    assertThat(powers(sync(secondary)), contains(-501.0));
  }

  @Test
  @DisplayName("forwards the energy readings of the active device by default")
  void energyFromActiveDevice() {
    start("");

    primary.tell(energy(1, 2));
    assertThat(energies(sync(primary)), contains(1.0));

    secondary.tell(energy(3, 4));
    assertThat(sync(secondary), is(empty()));

    failOverByTimeout();

    primary.tell(energy(5, 6));
    assertThat(sync(primary), is(empty()));

    secondary.tell(energy(7, 8));
    assertThat(energies(sync(secondary)), contains(7.0));
  }

  @Test
  @DisplayName("forwards the energy readings of the secondary device if configured")
  void energyFromSecondaryDevice() {
    start("energy-source = \"secondary\"");

    primary.tell(energy(1, 2));
    assertThat(sync(primary), is(empty()));

    secondary.tell(energy(3, 4));
    assertThat(energies(sync(secondary)), contains(3.0));
  }

  @Test
  @DisplayName("rejects a nested failover input device")
  void rejectsNestedFailover() {
    ActorRef<InputDevice.Command> failover = testKit.spawn(Failover.create(
          output.ref(),
          failoverConfig("primary = \"test-sources.nested\""),
          clock,
          (proxy, path) -> Behaviors.empty()));

    testKit.createTestProbe().expectTerminated(failover, Duration.ofSeconds(3));
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Spawn the failover input device with fake source devices, which only expose their (proxied) output device
   */
  private void start(@NotNull String additionalConfig) {
    TestProbe<SourceStarted> started = testKit.createTestProbe();

    testKit.spawn(Failover.create(
          output.ref(),
          failoverConfig(additionalConfig),
          clock,
          (proxy, path) -> Behaviors.setup(context -> {
            started.ref().tell(new SourceStarted(path, proxy));
            return Behaviors.empty();
          })));

    Map<String, ActorRef<OutputDevice.Command>> proxies = new HashMap<>();
    for (int i = 0; i < 2; i++) {
      SourceStarted message = started.receiveMessage();
      proxies.put(message.path(), message.proxy());
    }
    primary = proxies.get(PRIMARY);
    secondary = proxies.get(SECONDARY);
  }

  private @NotNull Config failoverConfig(@NotNull String additionalConfig) {
    return ConfigFactory.parseString(additionalConfig)
          .withFallback(ConfigFactory.parseString("""
                primary = "%s"
                secondary = "%s"
                check-interval = 10ms
                """.formatted(PRIMARY, SECONDARY)))
          .withFallback(ConfigFactory.load().getConfig("uni-meter.input-devices.failover"));
  }

  /**
   * Let the primary device time out, so that the secondary device becomes active
   */
  private void failOverByTimeout() {
    clock.advance(Duration.ofSeconds(11));
    secondary.tell(power(-500));
    assertThat(powers(sync(secondary)), contains(-500.0));
  }

  /**
   * Let the primary device deliver changing readings every 5 seconds
   * @return All readings forwarded to the output device in that period
   */
  private @NotNull List<OutputDevice.Command> feedPrimary(int count, double startValue) {
    List<OutputDevice.Command> forwarded = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      clock.advance(Duration.ofSeconds(5));
      primary.tell(power(startValue + i));
      forwarded.addAll(sync(primary));
    }
    return forwarded;
  }

  /**
   * Wait until all messages sent through the given proxy have been processed by the failover device. This uses the
   * fact that messages other than power and energy notifications are always forwarded unchanged.
   * @return The messages forwarded to the output device in the meantime
   */
  private @NotNull List<OutputDevice.Command> sync(@NotNull ActorRef<OutputDevice.Command> proxy) {
    proxy.tell(new OutputDevice.GetStatus(statusProbe.ref()));

    List<OutputDevice.Command> forwarded = new ArrayList<>();
    while (true) {
      OutputDevice.Command command = output.receiveMessage(Duration.ofSeconds(3));
      if (command instanceof OutputDevice.GetStatus) {
        return forwarded;
      }
      forwarded.add(command);
    }
  }

  private @NotNull OutputDevice.NotifyTotalPowerData power(double power) {
    return new OutputDevice.NotifyTotalPowerData(
          nextMessageId++,
          new OutputDevice.PowerData(power, power, 1.0, power / 230.0, 230.0, 50.0),
          ackProbe.ref());
  }

  private @NotNull OutputDevice.NotifyTotalEnergyData energy(double consumption, double production) {
    return new OutputDevice.NotifyTotalEnergyData(
          nextMessageId++,
          new OutputDevice.EnergyData(consumption, production),
          ackProbe.ref());
  }

  private static @NotNull List<Double> powers(@NotNull List<OutputDevice.Command> commands) {
    return commands.stream()
          .filter(command -> command instanceof OutputDevice.NotifyTotalPowerData)
          .map(command -> ((OutputDevice.NotifyTotalPowerData) command).data().power())
          .toList();
  }

  private static @NotNull List<Double> energies(@NotNull List<OutputDevice.Command> commands) {
    return commands.stream()
          .filter(command -> command instanceof OutputDevice.NotifyTotalEnergyData)
          .map(command -> ((OutputDevice.NotifyTotalEnergyData) command).data().totalConsumption())
          .toList();
  }

  private record SourceStarted(
        @NotNull String path,
        @NotNull ActorRef<OutputDevice.Command> proxy
  ) {}

  /**
   * Clock which only advances when told to
   */
  private static class ManualClock extends Clock {
    private volatile Instant now;

    ManualClock(@NotNull Instant start) {
      this.now = start;
    }

    void advance(@NotNull Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
