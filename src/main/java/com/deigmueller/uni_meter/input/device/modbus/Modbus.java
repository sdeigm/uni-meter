package com.deigmueller.uni_meter.input.device.modbus;

import com.deigmueller.uni_meter.input.InputDevice;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.digitalpetri.modbus.client.ModbusTcpClient;
import com.digitalpetri.modbus.tcp.client.NettyTcpClientTransport;
import com.typesafe.config.Config;
import lombok.AccessLevel;
import lombok.Getter;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.PostStop;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.time.Duration;

@Getter(AccessLevel.PROTECTED)
public abstract class Modbus extends InputDevice {
  // Instance members
  private final String address = getConfig().getString("address");
  private final int port = getConfig().getInt("port");
  private final int unitId = getConfig().getInt("unit-id");
  private final Duration pollingInterval = getConfig().getDuration("polling-interval");
  private final Duration reconnectInterval = getConfig().getDuration("reconnect-interval");
  private ModbusTcpClient client;
  
  protected Modbus(@NotNull ActorContext<Command> context,
                   @NotNull ActorRef<OutputDevice.Command> outputDevice,
                   @NotNull Config config) {
    super(context, outputDevice, config);
    
    startConnection();
  }
  
  @Override
  public @NotNull ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onSignal(PostStop.class, this::onPostStop)
          .onMessage(NotifyConnectFailed.class, this::onConnectFailed)
          .onMessage(NotifyConnectSucceeded.class, this::onConnectSucceeded)
          .onMessage(ReadInputRegistersFailed.class, this::onReadInputRegistersFailed)
          .onMessage(ReadHoldingRegistersFailed.class, this::onReadHoldingRegistersFailed)
          .onMessage(StartNextPollingCycle.class, this::onStartNextPollingCycle)
          .onMessage(ReconnectToModbusDevice.class, this::onReconnectToModbusDevice);
  }
  
  protected @NotNull Behavior<Command> onPostStop(@NotNull PostStop message) {
    logger.trace("Modbus.onPostStop()");
    
    if (client != null) {
      try {
        client.disconnect();
      } catch (Exception exception) {
        logger.debug("failed to disconnect from the Modbus device", exception);
      }
    }
    
    return Behaviors.same();
  }

  protected @NotNull Behavior<Command> onConnectFailed(@NotNull NotifyConnectFailed message) {
    logger.trace("Modbus.onConnectFailed()");
    
    logger.error("failed to connect to the Modbus device", message.throwable());

    reinitializeClient();

    return Behaviors.same();
  }

  protected @NotNull Behavior<Command> onConnectSucceeded(@NotNull NotifyConnectSucceeded message) {
    logger.trace("Modbus.onConnectSucceeded()");
    
    logger.info("connection to the Modbus device at {}:{} established", address, port);
    
    client = message.client();
    
    return Behaviors.same();
  }
  
  protected @NotNull Behavior<Command> onReadInputRegistersFailed(@NotNull ReadInputRegistersFailed message) {
    logger.trace("Modbus.onReadInputRegistersFailed()");
    
    logger.error("failed to read input registers at address {}, quantity {}", message.address(), message.quantity(), 
          message.throwable());
    
    reinitializeClient();
    
    return Behaviors.same();
  }

  protected @NotNull Behavior<Command> onReadHoldingRegistersFailed(@NotNull ReadHoldingRegistersFailed message) {
    logger.trace("Modbus.onReadHoldingRegistersFailed()");

    logger.error("failed to read holding registers at address {}, quantity {}", message.address(), message.quantity(),
          message.throwable());

    reinitializeClient();

    return Behaviors.same();
  }
  
  protected @NotNull Behavior<Command> onReconnectToModbusDevice(@NotNull ReconnectToModbusDevice message) {
    logger.trace("Modbus.onReconnectToModbusDevice()");
    
    logger.info("reconnecting to the Modbus device");
    
    startConnection();
    
    return Behaviors.same();
  }

  abstract protected @NotNull Behavior<Command> onStartNextPollingCycle(@NotNull StartNextPollingCycle message); 
  
  private void startConnection() {
    ModbusTcpClient client = null;
    try {
      NettyTcpClientTransport transport = NettyTcpClientTransport.create(cfg -> {
        cfg.hostname = address;
        cfg.port = port;
      });

      client = ModbusTcpClient.create(transport);

      client.connect();

      getContext().getSelf().tell(new NotifyConnectSucceeded(client));
    } catch (Exception e) {
      if (client != null) {
        try { client.disconnect(); } catch (Exception ignore) {}
      }
      
      getContext().getSelf().tell(new NotifyConnectFailed(e));
    }
  }
  
private void reinitializeClient() {
  if (client != null) {
    try {
      client.disconnect();
    } catch (Exception exception) {
      logger.debug("failed to disconnect from the Modbus device", exception);
      }
      
      client = null;
    }
    
    getContext().getSystem().scheduler().scheduleOnce(
          getReconnectInterval(),
          () -> getContext().getSelf().tell(ReconnectToModbusDevice.INSTANCE),
          getContext().getExecutionContext());
  }

  protected void startNextPollingTimer() {
    getContext().getSystem().scheduler().scheduleOnce(
          getPollingInterval(),
          () -> getContext().getSelf().tell(StartNextPollingCycle.INSTANCE),
          getContext().getExecutionContext());
  }
  
  protected static float bytesToFloat(byte[] bytes) {
    int asInt = (bytes[3] & 0xFF)
          | ((bytes[2] & 0xFF) << 8)
          | ((bytes[1] & 0xFF) << 16)
          | ((bytes[0] & 0xFF) << 24);
    
    return Float.intBitsToFloat(asInt);
  }

  protected static long readUInt16(ByteBuffer buffer) {
    return ((buffer.get() & 0xFF) << 8)
          | ((buffer.get() & 0xFF));
  }

  protected static long readUInt32(ByteBuffer buffer) {
    return (buffer.get() & 0xFFL << 24)
          | ((buffer.get() & 0xFF) << 16)
          | ((buffer.get() & 0xFF) << 8)
          | ((buffer.get() & 0xFF));
  }

  protected static int readSignedInt32(ByteBuffer buffer) {
    return buffer.getInt();
  }
  
  protected static void skip(ByteBuffer b, int bytes) {
    b.position(b.position() + bytes);
  }  
  
  protected record NotifyConnectSucceeded(
        @NotNull ModbusTcpClient client
  ) implements Command {}

  protected record NotifyConnectFailed(
        @NotNull Throwable throwable
  ) implements Command {}

  public record ReadInputRegistersFailed(
        int address,
        int quantity,
        Throwable throwable
  ) implements Command {}

  public record ReadHoldingRegistersFailed(
        int address,
        int quantity,
        Throwable throwable
  ) implements Command {}

  protected enum StartNextPollingCycle implements Command {
    INSTANCE
  }
  
  protected enum ReconnectToModbusDevice implements Command {
    INSTANCE
  }
}
