package com.deigmueller.uni_meter.input.device.common.generic;

import com.deigmueller.uni_meter.input.InputDevice;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.json.JsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import com.jayway.jsonpath.spi.mapper.MappingProvider;
import com.typesafe.config.Config;
import lombok.AccessLevel;
import lombok.Getter;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.stream.Materializer;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.Set;

@Getter(AccessLevel.PROTECTED)
public abstract class GenericInputDevice extends InputDevice {
  // Instance members
  private final Materializer materializer = Materializer.createMaterializer(getContext());
  private final PhaseMode powerPhaseMode = getPhaseMode("power-phase-mode");
  private final PhaseMode energyPhaseMode = getPhaseMode("energy-phase-mode");

  private double powerTotal;
  private double powerTotalProduction;
  private double voltage = getDefaultVoltage();
  private double frequency = getDefaultFrequency();
  private double powerL1;
  private double powerL1Production;
  private double voltageL1 = getDefaultVoltage();
  private double frequencyL1 = getDefaultFrequency();
  private double powerL2;
  private double powerL2Production;
  private double voltageL2 = getDefaultVoltage();
  private double frequencyL2 = getDefaultFrequency();
  private double powerL3;
  private double powerL3Production;
  private double voltageL3 = getDefaultVoltage();
  private double frequencyL3 = getDefaultFrequency();

  private double energyConsumptionTotal;
  private double energyConsumptionL1;
  private double energyConsumptionL2;
  private double energyConsumptionL3;

  private double energyProductionTotal;
  private double energyProductionL1;
  private double energyProductionL2;
  private double energyProductionL3;

  /**
   * Constructor
   * @param context The actor context
   * @param outputDevice The output device to notify
   * @param config The configuration
   */
  protected GenericInputDevice(@NotNull ActorContext<Command> context, 
                               @NotNull ActorRef<OutputDevice.Command> outputDevice, 
                               @NotNull Config config) {
    super(context, outputDevice, config);

    initJsonPath();
  }

  /**
   * Set the channel data.
   * @param channel The channel to set the data for.
   * @param value The value to set.
   */
  protected void setChannelData(@NotNull String channel, double value) {
    switch (channel) {
      case "power-total":
        powerTotal = value;
        break;
      case "power-production-total":
        powerTotalProduction = value;
        break;
      case "voltage": 
        voltage = value;
        break;
      case "frequency":
        frequency = value;
        break;
        
      case "power-l1":
        powerL1 = value;
        break;
      case "power-production-l1":
        powerL1Production = value;
        break;
      case "voltage-l1":
        voltageL1 = value;
        break;
      case "frequency-l1":
        frequencyL1 = value;
        break;
        
      case "power-l2":
        powerL2 = value;
        break;
      case "power-production-l2":
        powerL2Production = value;
        break;
      case "voltage-l2":
        voltageL2 = value;
        break;
      case "frequency-l2":
        frequencyL2 = value;
        break;
        
      case "power-l3":
        powerL3 = value;
        break;
      case "power-production-l3":
        powerL3Production = value;
        break;
      case "voltage-l3":
        voltageL3 = value;
        break;
      case "frequency-l3":
        frequencyL3 = value;
        break;
        
      case "energy-consumption-total":
        energyConsumptionTotal = value;
        break;
      case "energy-consumption-l1":
        energyConsumptionL1 = value;
        break;
      case "energy-consumption-l2":
        energyConsumptionL2 = value;
        break;
      case "energy-consumption-l3":
        energyConsumptionL3 = value;
        break;
        
      case "energy-production-total":
        energyProductionTotal = value;
        break;
      case "energy-production-l1":
        energyProductionL1 = value;
        break;
      case "energy-production-l2":
        energyProductionL2 = value;
        break;
      case "energy-production-l3":
        energyProductionL3 = value;
        break;
      default:
        logger.warn("unknown channel: {}", channel);
    }
  }

  /**
   * Notify the current readings to the output device.
   */
  protected void notifyOutputDevice() {
    notifyPowerData();

    notifyEnergyData();
  }

  /**
   * Notify the current power data to the output device.
   */
  protected void notifyPowerData() {
    if (powerPhaseMode == PhaseMode.MONO) {
      double resultingPower = powerTotal - powerTotalProduction;
      
      getOutputDevice().tell(new OutputDevice.NotifyTotalPowerData(
            getNextMessageId(),
            new OutputDevice.PowerData(
                  resultingPower, resultingPower, 1.0, calcCurrent(resultingPower, voltage), voltage, frequency),
            getOutputDeviceAckAdapter()));
    } else {
      double resultingPowerL1 = powerL1 - powerL1Production;
      double resultingPowerL2 = powerL2 - powerL2Production;
      double resultingPowerL3 = powerL3 - powerL3Production;
      
      getOutputDevice().tell(new OutputDevice.NotifyPhasesPowerData(
            getNextMessageId(),
            new OutputDevice.PowerData(
                  resultingPowerL1, resultingPowerL1, 1.0, calcCurrent(resultingPowerL1, voltageL1), voltageL1, frequencyL1),
            new OutputDevice.PowerData(
                  resultingPowerL2, resultingPowerL2, 1.0, calcCurrent(resultingPowerL2, voltageL2), voltageL2, frequencyL2),
            new OutputDevice.PowerData(
                  resultingPowerL3, resultingPowerL3, 1.0, calcCurrent(resultingPowerL3, voltageL3), voltageL3, frequencyL3),
            getOutputDeviceAckAdapter()));
    }
  }

  /**
   * Calculate the current based on current power and voltage values
   * @param power Current power value
   * @param voltage Current voltage value
   * @return Calculated current
   */
  protected double calcCurrent(double power, double voltage) {
    if (voltage == 0.0) {
      return 0.0;
    }
    return power / voltage;
  }

  /**
   * Notify the current energy data to the output device.
   */
  protected void notifyEnergyData() {
    if (energyPhaseMode == PhaseMode.MONO) {
      getOutputDevice().tell(new OutputDevice.NotifyTotalEnergyData(
            getNextMessageId(),
            new OutputDevice.EnergyData(energyConsumptionTotal, energyProductionTotal),
            getOutputDeviceAckAdapter()));
    } else {
      getOutputDevice().tell(new OutputDevice.NotifyPhaseEnergyData(
            getNextMessageId(),
            0,
            new OutputDevice.EnergyData(energyConsumptionL1, energyProductionL1),
            getOutputDeviceAckAdapter()));
      getOutputDevice().tell(new OutputDevice.NotifyPhaseEnergyData(
            getNextMessageId(),
            1,
            new OutputDevice.EnergyData(energyConsumptionL2, energyProductionL2),
            getOutputDeviceAckAdapter()));
      getOutputDevice().tell(new OutputDevice.NotifyPhaseEnergyData(
            getNextMessageId(),
            2,
            new OutputDevice.EnergyData(energyConsumptionL3, energyProductionL3),
            getOutputDeviceAckAdapter()));
    }
  }

  private void initJsonPath() {
    Configuration.setDefaults(new Configuration.Defaults() {

      private final JsonProvider jsonProvider = new JacksonJsonProvider();
      private final MappingProvider mappingProvider = new JacksonMappingProvider();

      @Override
      public JsonProvider jsonProvider() {
        return jsonProvider;
      }

      @Override
      public MappingProvider mappingProvider() {
        return mappingProvider;
      }

      @Override
      public Set<Option> options() {
        return EnumSet.noneOf(Option.class);
      }
    });
  }
}
