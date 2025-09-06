package com.deigmueller.uni_meter.input.device.modbus.ksem;

import com.deigmueller.uni_meter.input.device.modbus.Modbus;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.digitalpetri.modbus.client.ModbusTcpClient;
import com.digitalpetri.modbus.exceptions.ModbusExecutionException;
import com.digitalpetri.modbus.exceptions.ModbusResponseException;
import com.digitalpetri.modbus.exceptions.ModbusTimeoutException;
import com.digitalpetri.modbus.pdu.ReadHoldingRegistersRequest;
import com.digitalpetri.modbus.pdu.ReadHoldingRegistersResponse;
import com.typesafe.config.Config;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.nio.ByteBuffer;

public class Ksem extends Modbus {
  // Class members
  public static final String TYPE = "KSEM";

  // Instance members

  private long supplyFrequency;

  private long activePowerPlusL1;

  private long activePowerMinusL1;

  private long currentL1;

  private long voltageL1;

  private long powerFactorL1;

  private long activePowerPlusL2;

  private long activePowerMinusL2;

  private long currentL2;

  private long voltageL2;

  private long powerFactorL2;

  private long activePowerPlusL3;

  private long activePowerMinusL3;

  private long currentL3;

  private long voltageL3;

  private long powerFactorL3;

  private long apparentPowerPlusL1;

  private long apparentPowerMinusL1;

  private long apparentPowerPlusL2;

  private long apparentPowerMinusL2;

  private long apparentPowerPlusL3;

  private long apparentPowerMinusL3;

  private BigInteger activeEnergyPlusL1;

  private BigInteger activeEnergyMinusL1;

  private BigInteger activeEnergyPlusL2;

  private BigInteger activeEnergyMinusL2;

  private BigInteger activeEnergyPlusL3;

  private BigInteger activeEnergyMinusL3;
  
  public static Behavior<Command> create(@NotNull ActorRef<OutputDevice.Command> outputDevice,
                                         @NotNull Config config) {
    return Behaviors.setup(context -> new Ksem(context, outputDevice, config));
  }

  protected Ksem(@NotNull ActorContext<Command> context, 
                      @NotNull ActorRef<OutputDevice.Command> outputDevice, 
                      @NotNull Config config) {
    super(context, 
          outputDevice, 
          config.withFallback(context.getSystem().settings().config().getConfig("uni-meter.input-devices.modbus")));
  }

  @Override
  public @NotNull ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onMessage(ReadMeterDataSucceeded.class, this::onReadMetaDataSucceeded);
  }

  @Override
  protected @NotNull Behavior<Command> onConnectSucceeded(@NotNull NotifyConnectSucceeded message) {
    logger.trace("Ksem.onConnectSucceeded()");
    super.onConnectSucceeded(message);

    readMeterData();

    return Behaviors.same();
  }

  @Override
  protected @NotNull Behavior<Command> onStartNextPollingCycle(@NotNull StartNextPollingCycle message) {
    logger.trace("Ksem.onStartNextPollingCycle()");

    readMeterData();

    return Behaviors.same();
  }
  
  protected @NotNull Behavior<Command> onReadMetaDataSucceeded(@NotNull ReadMeterDataSucceeded message) {
    logger.trace("Ksem.onReadMetaDataSucceeded()");

    try {

      long activePowerL1 = activePowerPlusL1 - activePowerMinusL1;
      long apparentPowerL1 = apparentPowerPlusL1 - apparentPowerMinusL1;

      long activePowerL2 = activePowerPlusL2 - activePowerMinusL2;
      long apparentPowerL2 = apparentPowerPlusL2 - apparentPowerMinusL2;

      long activePowerL3 = activePowerPlusL3 - activePowerMinusL3;
      long apparentPowerL3 = apparentPowerPlusL3 - apparentPowerMinusL3;
      
      getOutputDevice().tell(new OutputDevice.NotifyPhasesPowerData(
            getNextMessageId(),
            new OutputDevice.PowerData(
                  activePowerL1 / 10.0,
                  apparentPowerL1 / 10.0,
                  powerFactorL1 / 1000.0,
                  currentL1 / 1000.0,
                  voltageL1 / 1000.0,
                  supplyFrequency / 1000.0), 
            new OutputDevice.PowerData(
                  activePowerL2 / 10.0,
                  apparentPowerL2 / 10.0,
                  powerFactorL2 / 1000.0,
                  currentL2 / 1000.0,
                  voltageL2 / 1000.0,
                  supplyFrequency / 1000.0), 
            new OutputDevice.PowerData(
                  activePowerL3 / 10.0,
                  apparentPowerL3 / 10.0,
                  powerFactorL3 / 1000.0,
                  currentL3 / 1000.0,
                  voltageL3 / 1000.0,
                  supplyFrequency / 1000.0), 
            getOutputDeviceAckAdapter()));
      
      getOutputDevice().tell(new OutputDevice.NotifyPhasesEnergyData(
            getNextMessageId(),
            new OutputDevice.EnergyData(
                  activeEnergyPlusL1.longValue() / 10000.0,
                  activeEnergyMinusL1.longValue() / 10000.0),
            new OutputDevice.EnergyData(
                  activeEnergyPlusL2.longValue() / 10000.0,
                  activeEnergyMinusL2.longValue() / 10000.0),
            new OutputDevice.EnergyData(
                  activeEnergyPlusL3.longValue() / 10000.0,
                  activeEnergyMinusL3.longValue() / 10000.0),
            getOutputDeviceAckAdapter()));

      startNextPollingTimer();
    } catch (Exception exception) {
      logger.error("failed to process meter data", exception);
      startNextPollingTimer();
    }

    return Behaviors.same();
  }

  private void readMeterData() {
    logger.trace("Ksem.readMeterData()");
    
    try {
      supplyFrequency = readUnsignedInt32(getClient(), 0x001A);
        
      activePowerPlusL1 = readUnsignedInt32(getClient(), 0x0028);
      activePowerMinusL1 = readUnsignedInt32(getClient(), 0x002A);
      apparentPowerPlusL1 = readUnsignedInt32(getClient(), 0x0038);
      apparentPowerMinusL1 = readUnsignedInt32(getClient(), 0x003A);
      currentL1 = readUnsignedInt32(getClient(), 0x003C);
      voltageL1 = readUnsignedInt32(getClient(), 0x003E);
      powerFactorL1 = readUnsignedInt32(getClient(), 0x0040);
        
      activePowerPlusL2 = readUnsignedInt32(getClient(), 0x0050);
      activePowerMinusL2 = readUnsignedInt32(getClient(), 0x0052);
      apparentPowerPlusL2 = readUnsignedInt32(getClient(), 0x0060);
      apparentPowerMinusL2 = readUnsignedInt32(getClient(), 0x0062);
      currentL2 = readUnsignedInt32(getClient(), 0x0064);
      voltageL2 = readUnsignedInt32(getClient(), 0x0066);
      powerFactorL2 = readUnsignedInt32(getClient(), 0x0068);

      activePowerPlusL3 = readUnsignedInt32(getClient(), 0x0078);
      activePowerMinusL3 = readUnsignedInt32(getClient(), 0x007A);
      apparentPowerPlusL3 = readUnsignedInt32(getClient(), 0x0088);
      apparentPowerMinusL3 = readUnsignedInt32(getClient(), 0x008A);
      currentL3 = readUnsignedInt32(getClient(), 0x008C);
      voltageL3 = readUnsignedInt32(getClient(), 0x008E);
      powerFactorL3 = readUnsignedInt32(getClient(), 0x0090);
        
      activeEnergyPlusL1 = readUnsignedInt64(getClient(), 0x0250);
      activeEnergyMinusL1 = readUnsignedInt64(getClient(), 0x0254);

      activeEnergyPlusL2 = readUnsignedInt64(getClient(), 0x02A0);
      activeEnergyMinusL2 = readUnsignedInt64(getClient(), 0x02A4);

      activeEnergyPlusL3 = readUnsignedInt64(getClient(), 0x02F0);
      activeEnergyMinusL3 = readUnsignedInt64(getClient(), 0x02F4);

      getContext().getSelf().tell(new ReadMeterDataSucceeded());
    } catch (ModbusExecutionException | ModbusResponseException | ModbusTimeoutException e) {
      getContext().getSelf().tell(new ReadHoldingRegistersFailed(0, 100, e));
    }
    
    
  }
  
  private long readUnsignedInt32(ModbusTcpClient client, int address)
        throws ModbusExecutionException, ModbusResponseException, ModbusTimeoutException {
    ReadHoldingRegistersResponse response = client.readHoldingRegisters(
          1,
          new ReadHoldingRegistersRequest(address, 2)
    );
		
    ByteBuffer byteBuffer = ByteBuffer.wrap(response.registers());
    return Integer.toUnsignedLong(byteBuffer.getInt());
  }
	
	
  private BigInteger readUnsignedInt64(ModbusTcpClient client, int address)
        throws ModbusExecutionException, ModbusResponseException, ModbusTimeoutException {
    ReadHoldingRegistersResponse response = client.readHoldingRegisters(
          1,
          new ReadHoldingRegistersRequest(address, 4)
    );
		
    ByteBuffer byteBuffer = ByteBuffer.wrap(response.registers());
    return new BigInteger(1, byteBuffer.array());
  }

  
  public record ReadMeterDataSucceeded(
        
  ) implements Command {}
}
