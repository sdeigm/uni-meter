package com.deigmueller.uni_meter.input.device.modbus;

import com.deigmueller.uni_meter.input.InputDevice;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.digitalpetri.modbus.client.ModbusTcpClient;
import com.digitalpetri.modbus.pdu.ReadInputRegistersRequest;
import com.digitalpetri.modbus.pdu.ReadInputRegistersResponse;
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

import java.time.Duration;

@Getter(AccessLevel.PROTECTED)
public abstract class Modbus extends InputDevice {
  // Instance members
  private final String address = getConfig().getString("address");
  private final int port = getConfig().getInt("port");
  private final Duration pollingInterval = getConfig().getDuration("polling-interval");
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
          .onMessage(StartNextPollingCycle.class, this::onStartNextPollingCycle);
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
    
    throw new RuntimeException("failed to connect to the Modbus device", message.throwable());
  }

  protected @NotNull Behavior<Command> onConnectSucceeded(@NotNull NotifyConnectSucceeded message) {
    logger.trace("Modbus.onConnectSucceeded()");
    
    client = message.client();
    
    return Behaviors.same();
  }
  
  protected @NotNull Behavior<Command> onReadInputRegistersFailed(@NotNull ReadInputRegistersFailed message) {
    logger.trace("Modbus.onReadInputRegistersFailed()");
    
    logger.error("failed to read input registers at address {}, quantity {}", message.address(), message.quantity(), 
          message.throwable());

    startNextPollingTimer();
    
    return Behaviors.same();
  }
  
  abstract protected @NotNull Behavior<Command> onStartNextPollingCycle(@NotNull StartNextPollingCycle message); 
  
  private void startConnection() {
      try {
        NettyTcpClientTransport transport = NettyTcpClientTransport.create(cfg -> {
          cfg.hostname = "192.168.178.125";
          cfg.port = 8899;
        });

        ModbusTcpClient client = ModbusTcpClient.create(transport);
        client.connect();

        ReadInputRegistersResponse response = client.readInputRegisters(
              1,
              new ReadInputRegistersRequest(0 , 2)
        );

        int asInt = (response.registers()[3] & 0xFF)
              | ((response.registers()[2] & 0xFF) << 8)
              | ((response.registers()[1] & 0xFF) << 16)
              | ((response.registers()[0] & 0xFF) << 24);

        float asFloat = Float.intBitsToFloat(asInt);
        System.out.println("Value: " + asFloat);

        response = client.readInputRegisters(
              1,
              new ReadInputRegistersRequest(0x48 , 2)
        );

        asInt = (response.registers()[3] & 0xFF)
              | ((response.registers()[2] & 0xFF) << 8)
              | ((response.registers()[1] & 0xFF) << 16)
              | ((response.registers()[0] & 0xFF) << 24);

        asFloat = Float.intBitsToFloat(asInt);
        System.out.println("Value: " + asFloat);
        getContext().getSelf().tell(new NotifyConnectSucceeded(client));
      } catch (Exception e) {
        getContext().getSelf().tell(new NotifyConnectFailed(e));
      }
  }

  protected void startNextPollingTimer() {
    getContext().getSystem().scheduler().scheduleOnce(
          getPollingInterval(),
          () -> getContext().getSelf().tell(StartNextPollingCycle.INSTANCE),
          getContext().getExecutionContext());
  }
  
  protected float bytesToFloat(byte[] bytes) {
    int asInt = (bytes[3] & 0xFF)
          | ((bytes[2] & 0xFF) << 8)
          | ((bytes[1] & 0xFF) << 16)
          | ((bytes[0] & 0xFF) << 24);
    
    return Float.intBitsToFloat(asInt);
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

  protected enum StartNextPollingCycle implements Command {
    INSTANCE
  }
}
