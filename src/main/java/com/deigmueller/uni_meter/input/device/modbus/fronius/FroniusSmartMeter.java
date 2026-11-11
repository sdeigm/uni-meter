package com.deigmueller.uni_meter.input.device.modbus.fronius;

import com.deigmueller.uni_meter.common.utils.MathUtils;
import com.deigmueller.uni_meter.input.device.modbus.Modbus;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.digitalpetri.modbus.pdu.ReadHoldingRegistersRequest;
import com.typesafe.config.Config;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.function.Function;

/**
 * Input device for a Fronius Smart Meter (e.g. Fronius Smart Meter IP or a meter connected to a Fronius inverter)
 * read via Modbus TCP using the SunSpec meter models. The meter model is discovered automatically by walking the
 * SunSpec model chain. Both the float models (211-214) and the integer + scale factor models (201-204) are supported.
 */
public class FroniusSmartMeter extends Modbus {
  // Class members
  public static final String TYPE = "FroniusSmartMeter";

  private static final int SUNSPEC_ID_HIGH = 0x5375; // "Su"
  private static final int SUNSPEC_ID_LOW = 0x6e53;  // "nS"
  private static final int SUNSPEC_END_MODEL = 0xFFFF;
  private static final int MAX_MODELS_TO_SCAN = 50;

  // Number of registers read from the start of the model data block (up to and including the imported energy)
  private static final int INT_SF_REGISTER_COUNT = 53;
  private static final int FLOAT_REGISTER_COUNT = 74;

  // Instance members
  private final int baseRegisterAddress = getConfig().getInt("base-register-address");
  private final double powerSign = getConfig().getBoolean("invert-power") ? -1.0 : 1.0;

  private int dataRegisterAddress = -1;
  private boolean floatModel;
  private int modelsScanned;

  public static Behavior<Command> create(@NotNull ActorRef<OutputDevice.Command> outputDevice,
                                         @NotNull Config config) {
    return Behaviors.setup(context -> new FroniusSmartMeter(context, outputDevice, config));
  }

  protected FroniusSmartMeter(@NotNull ActorContext<Command> context,
                              @NotNull ActorRef<OutputDevice.Command> outputDevice,
                              @NotNull Config config) {
    super(context,
          outputDevice,
          config.withFallback(context.getSystem().settings().config().getConfig("uni-meter.input-devices.modbus")));
  }

  @Override
  public @NotNull ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onMessage(ReadSunSpecIdSucceeded.class, this::onReadSunSpecIdSucceeded)
          .onMessage(ReadModelHeaderSucceeded.class, this::onReadModelHeaderSucceeded)
          .onMessage(RetryModelDiscovery.class, this::onRetryModelDiscovery)
          .onMessage(ReadMeterDataSucceeded.class, this::onReadMeterDataSucceeded);
  }

  @Override
  protected @NotNull Behavior<Command> onConnectSucceeded(@NotNull NotifyConnectSucceeded message) {
    logger.trace("FroniusSmartMeter.onConnectSucceeded()");
    super.onConnectSucceeded(message);

    if (dataRegisterAddress < 0) {
      startModelDiscovery();
    } else {
      readMeterData();
    }

    return Behaviors.same();
  }

  @Override
  protected @NotNull Behavior<Command> onStartNextPollingCycle(@NotNull StartNextPollingCycle message) {
    logger.trace("FroniusSmartMeter.onStartNextPollingCycle()");

    readMeterData();

    return Behaviors.same();
  }

  protected @NotNull Behavior<Command> onReadSunSpecIdSucceeded(@NotNull ReadSunSpecIdSucceeded message) {
    logger.trace("FroniusSmartMeter.onReadSunSpecIdSucceeded()");

    ByteBuffer byteBuffer = ByteBuffer.wrap(message.registers());
    int high = byteBuffer.getShort() & 0xFFFF;
    int low = byteBuffer.getShort() & 0xFFFF;

    if (high != SUNSPEC_ID_HIGH || low != SUNSPEC_ID_LOW) {
      logger.error("no SunSpec identifier found at register {} (got 0x{}{}), check base-register-address and unit-id",
            baseRegisterAddress, String.format("%04x", high), String.format("%04x", low));
      scheduleModelDiscoveryRetry();
    } else {
      modelsScanned = 0;
      readModelHeader(baseRegisterAddress + 2);
    }

    return Behaviors.same();
  }

  protected @NotNull Behavior<Command> onReadModelHeaderSucceeded(@NotNull ReadModelHeaderSucceeded message) {
    logger.trace("FroniusSmartMeter.onReadModelHeaderSucceeded()");

    ByteBuffer byteBuffer = ByteBuffer.wrap(message.registers());
    int modelId = byteBuffer.getShort() & 0xFFFF;
    int modelLength = byteBuffer.getShort() & 0xFFFF;

    logger.debug("found SunSpec model {} with length {} at register {}", modelId, modelLength, message.address());

    if (isIntSfMeterModel(modelId) || isFloatMeterModel(modelId)) {
      dataRegisterAddress = message.address() + 2;
      floatModel = isFloatMeterModel(modelId);

      logger.info("using SunSpec meter model {} ({}) at register {}",
            modelId, floatModel ? "float" : "int+sf", dataRegisterAddress);

      readMeterData();
    } else if (modelId == SUNSPEC_END_MODEL || ++modelsScanned >= MAX_MODELS_TO_SCAN) {
      logger.error("no SunSpec meter model (201-204, 211-214) found on unit-id {}", getUnitId());
      scheduleModelDiscoveryRetry();
    } else {
      readModelHeader(message.address() + 2 + modelLength);
    }

    return Behaviors.same();
  }

  protected @NotNull Behavior<Command> onRetryModelDiscovery(@NotNull RetryModelDiscovery message) {
    logger.trace("FroniusSmartMeter.onRetryModelDiscovery()");

    if (getClient() != null) {
      startModelDiscovery();
    }

    return Behaviors.same();
  }

  protected @NotNull Behavior<Command> onReadMeterDataSucceeded(@NotNull ReadMeterDataSucceeded message) {
    logger.trace("FroniusSmartMeter.onReadMeterDataSucceeded()");

    try {
      MeterData data = floatModel
            ? parseFloatModel(ByteBuffer.wrap(message.registers()))
            : parseIntSfModel(ByteBuffer.wrap(message.registers()));

      if (logger.isDebugEnabled()) {
        logger.debug("power: {} / {} / {}",
              MathUtils.round(data.l1().power(), 2),
              MathUtils.round(data.l2().power(), 2),
              MathUtils.round(data.l3().power(), 2));
        logger.debug("energy imported: {}, exported: {}",
              MathUtils.round(data.energyImported(), 2),
              MathUtils.round(data.energyExported(), 2));
      }

      getOutputDevice().tell(new OutputDevice.NotifyPhasesPowerData(
            getNextMessageId(),
            toPowerData(data.l1(), data.frequency()),
            toPowerData(data.l2(), data.frequency()),
            toPowerData(data.l3(), data.frequency()),
            getOutputDeviceAckAdapter()));

      getOutputDevice().tell(new OutputDevice.NotifyPhasesEnergyData(
            getNextMessageId(),
            toEnergyData(data.l1Imported(), data.l1Exported()),
            toEnergyData(data.l2Imported(), data.l2Exported()),
            toEnergyData(data.l3Imported(), data.l3Exported()),
            getOutputDeviceAckAdapter()));
    } catch (Exception exception) {
      logger.error("failed to process meter data", exception);
    }

    startNextPollingTimer();

    return Behaviors.same();
  }

  private @NotNull OutputDevice.PowerData toPowerData(@NotNull PhaseData phase, double frequency) {
    double power = powerSign * phase.power();
    return new OutputDevice.PowerData(
          power,
          Math.copySign(Math.abs(phase.apparentPower()), power),
          phase.powerFactor(),
          Math.abs(phase.current()),
          phase.voltage(),
          frequency);
  }

  private @NotNull OutputDevice.EnergyData toEnergyData(double importedWh, double exportedWh) {
    return powerSign > 0
          ? new OutputDevice.EnergyData(importedWh / 1000.0, exportedWh / 1000.0)
          : new OutputDevice.EnergyData(exportedWh / 1000.0, importedWh / 1000.0);
  }

  private void startModelDiscovery() {
    logger.debug("starting SunSpec model discovery at register {}", baseRegisterAddress);

    readHoldingRegisters(baseRegisterAddress, 2, ReadSunSpecIdSucceeded::new);
  }

  private void readModelHeader(int address) {
    readHoldingRegisters(address, 2, registers -> new ReadModelHeaderSucceeded(address, registers));
  }

  private void readMeterData() {
    logger.trace("FroniusSmartMeter.readMeterData()");

    readHoldingRegisters(
          dataRegisterAddress,
          floatModel ? FLOAT_REGISTER_COUNT : INT_SF_REGISTER_COUNT,
          ReadMeterDataSucceeded::new);
  }

  private void readHoldingRegisters(int address,
                                    int quantity,
                                    @NotNull Function<byte[], Command> onSuccess) {
    if (getClient() == null) {
      return;
    }

    getClient()
          .readHoldingRegistersAsync(getUnitId(), new ReadHoldingRegistersRequest(address, quantity))
          .whenComplete((response, throwable) -> {
            if (throwable != null) {
              getContext().getSelf().tell(new ReadHoldingRegistersFailed(address, quantity, throwable));
            } else {
              getContext().getSelf().tell(onSuccess.apply(response.registers()));
            }
          });
  }

  private void scheduleModelDiscoveryRetry() {
    getContext().getSystem().scheduler().scheduleOnce(
          getReconnectInterval(),
          () -> getContext().getSelf().tell(RetryModelDiscovery.INSTANCE),
          getContext().getExecutionContext());
  }

  static boolean isIntSfMeterModel(int modelId) {
    return modelId >= 201 && modelId <= 204;
  }

  static boolean isFloatMeterModel(int modelId) {
    return modelId >= 211 && modelId <= 214;
  }

  /**
   * Parse the data block of the SunSpec meter models 201-204 (integer values with scale factors)
   */
  static @NotNull MeterData parseIntSfModel(@NotNull ByteBuffer b) {
    // Current
    skip(b, 2); // total
    short currentA = b.getShort();
    short currentB = b.getShort();
    short currentC = b.getShort();
    double currentScale = scale(b.getShort());

    // Voltage
    skip(b, 2); // average line to neutral
    short voltageA = b.getShort();
    short voltageB = b.getShort();
    short voltageC = b.getShort();
    skip(b, 8); // line to line
    double voltageScale = scale(b.getShort());

    // Frequency
    short frequency = b.getShort();
    double frequencyScale = scale(b.getShort());

    // Real power
    skip(b, 2); // total
    short powerA = b.getShort();
    short powerB = b.getShort();
    short powerC = b.getShort();
    double powerScale = scale(b.getShort());

    // Apparent power
    skip(b, 2); // total
    short apparentPowerA = b.getShort();
    short apparentPowerB = b.getShort();
    short apparentPowerC = b.getShort();
    double apparentPowerScale = scale(b.getShort());

    // Reactive power
    skip(b, 10);

    // Power factor
    skip(b, 2); // average
    short powerFactorA = b.getShort();
    short powerFactorB = b.getShort();
    short powerFactorC = b.getShort();
    double powerFactorScale = scale(b.getShort());

    // Real energy
    skip(b, 4); // total exported
    long exportedA = readAcc32(b);
    long exportedB = readAcc32(b);
    long exportedC = readAcc32(b);
    skip(b, 4); // total imported
    long importedA = readAcc32(b);
    long importedB = readAcc32(b);
    long importedC = readAcc32(b);
    double energyScale = scale(b.getShort());

    return new MeterData(
          new PhaseData(
                value(powerA) * powerScale,
                value(apparentPowerA) * apparentPowerScale,
                normalizePowerFactor(value(powerFactorA) * powerFactorScale),
                value(currentA) * currentScale,
                value(voltageA) * voltageScale),
          new PhaseData(
                value(powerB) * powerScale,
                value(apparentPowerB) * apparentPowerScale,
                normalizePowerFactor(value(powerFactorB) * powerFactorScale),
                value(currentB) * currentScale,
                value(voltageB) * voltageScale),
          new PhaseData(
                value(powerC) * powerScale,
                value(apparentPowerC) * apparentPowerScale,
                normalizePowerFactor(value(powerFactorC) * powerFactorScale),
                value(currentC) * currentScale,
                value(voltageC) * voltageScale),
          value(frequency) * frequencyScale,
          importedA * energyScale,
          importedB * energyScale,
          importedC * energyScale,
          exportedA * energyScale,
          exportedB * energyScale,
          exportedC * energyScale);
  }

  /**
   * Parse the data block of the SunSpec meter models 211-214 (float values)
   */
  static @NotNull MeterData parseFloatModel(@NotNull ByteBuffer b) {
    // Current
    skip(b, 4); // total
    double currentA = readFloat(b);
    double currentB = readFloat(b);
    double currentC = readFloat(b);

    // Voltage
    skip(b, 4); // average line to neutral
    double voltageA = readFloat(b);
    double voltageB = readFloat(b);
    double voltageC = readFloat(b);
    skip(b, 16); // line to line

    // Frequency
    double frequency = readFloat(b);

    // Real power
    skip(b, 4); // total
    double powerA = readFloat(b);
    double powerB = readFloat(b);
    double powerC = readFloat(b);

    // Apparent power
    skip(b, 4); // total
    double apparentPowerA = readFloat(b);
    double apparentPowerB = readFloat(b);
    double apparentPowerC = readFloat(b);

    // Reactive power
    skip(b, 16);

    // Power factor
    skip(b, 4); // average
    double powerFactorA = readFloat(b);
    double powerFactorB = readFloat(b);
    double powerFactorC = readFloat(b);

    // Real energy
    skip(b, 4); // total exported
    double exportedA = readFloat(b);
    double exportedB = readFloat(b);
    double exportedC = readFloat(b);
    skip(b, 4); // total imported
    double importedA = readFloat(b);
    double importedB = readFloat(b);
    double importedC = readFloat(b);

    return new MeterData(
          new PhaseData(powerA, apparentPowerA, normalizePowerFactor(powerFactorA), currentA, voltageA),
          new PhaseData(powerB, apparentPowerB, normalizePowerFactor(powerFactorB), currentB, voltageB),
          new PhaseData(powerC, apparentPowerC, normalizePowerFactor(powerFactorC), currentC, voltageC),
          frequency,
          importedA, importedB, importedC,
          exportedA, exportedB, exportedC);
  }

  /**
   * SunSpec defines the power factor in percent, but some devices report it as a fraction. Normalize it to 0..1.
   */
  static double normalizePowerFactor(double powerFactor) {
    double result = Math.abs(powerFactor);
    if (result > 1.0) {
      result = result / 100.0;
    }
    return Math.min(result, 1.0);
  }

  private static double scale(short scaleFactor) {
    // 0x8000 marks a not implemented scale factor
    return scaleFactor == Short.MIN_VALUE ? 1.0 : Math.pow(10.0, scaleFactor);
  }

  private static double value(short value) {
    // 0x8000 marks a not implemented value
    return value == Short.MIN_VALUE ? 0.0 : value;
  }

  private static long readAcc32(ByteBuffer b) {
    return b.getInt() & 0xFFFFFFFFL;
  }

  private static double readFloat(ByteBuffer b) {
    float value = b.getFloat();
    return Float.isNaN(value) ? 0.0 : value;
  }

  record PhaseData(
        double power,
        double apparentPower,
        double powerFactor,
        double current,
        double voltage
  ) {}

  record MeterData(
        @NotNull PhaseData l1,
        @NotNull PhaseData l2,
        @NotNull PhaseData l3,
        double frequency,
        double l1Imported,
        double l2Imported,
        double l3Imported,
        double l1Exported,
        double l2Exported,
        double l3Exported
  ) {
    double energyImported() {
      return l1Imported + l2Imported + l3Imported;
    }

    double energyExported() {
      return l1Exported + l2Exported + l3Exported;
    }
  }

  public record ReadSunSpecIdSucceeded(
        byte[] registers
  ) implements Command {}

  public record ReadModelHeaderSucceeded(
        int address,
        byte[] registers
  ) implements Command {}

  public record ReadMeterDataSucceeded(
        byte[] registers
  ) implements Command {}

  protected enum RetryModelDiscovery implements Command {
    INSTANCE
  }
}
