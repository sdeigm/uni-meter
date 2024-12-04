package com.deigmueller.uni_meter.input.device.modbus;

import com.deigmueller.uni_meter.input.InputDevice;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.digitalpetri.modbus.master.ModbusTcpMaster;
import com.digitalpetri.modbus.master.ModbusTcpMasterConfig;
import com.typesafe.config.Config;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;

import org.jetbrains.annotations.NotNull;

public class Modbus extends InputDevice {
  // Instance members
  private final String address = getConfig().getString("address");
  private final int port = getConfig().getInt("port");
  
  public static Behavior<Command> create(@NotNull ActorRef<OutputDevice.Command> shelly,
                                         @NotNull ActorRef<OutputDevice.Command> outputDevice,
                                         @NotNull Config config) {
    return Behaviors.setup(context -> new Modbus(context, outputDevice, config));
  }

  protected Modbus(@NotNull ActorContext<Command> context,
                   @NotNull ActorRef<OutputDevice.Command> outputDevice,
                   @NotNull Config config) {
    super(context, outputDevice, config);
    
    startConnection();
  }
  
  @Override
  public @NotNull ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onMessage(NotifyConnectFailed.class, this::onConnectFailed)
          .onMessage(NotifyConnectSucceeded.class, this::onConnectSucceeded);
  }

  private @NotNull Behavior<Command> onConnectFailed(@NotNull NotifyConnectFailed message) {
    logger.trace("Modbus.onConnectFailed()");
    
    return Behaviors.same();
  }

  private @NotNull Behavior<Command> onConnectSucceeded(@NotNull NotifyConnectSucceeded message) {
    logger.trace("Modbus.onConnectSucceeded()");
    
    
    return Behaviors.same();
  }
  
  private void startConnection() {
      try {
        ModbusTcpMasterConfig config = new ModbusTcpMasterConfig.Builder(address)
              .setPort(port)
              .setInstanceId("1")
              .build();
  
        ModbusTcpMaster master = new ModbusTcpMaster(config);
  
        master.connect().whenComplete((tcpMaster, throwable) -> {
          if (throwable != null) {
            getContext().getSelf().tell(new NotifyConnectFailed(throwable));
          } else {
            getContext().getSelf().tell(new NotifyConnectSucceeded(tcpMaster));
          }
        });
      } catch (Exception e) {
        getContext().getSelf().tell(new NotifyConnectFailed(e));
      }
  }
  
  private record NotifyConnectSucceeded(
        @NotNull ModbusTcpMaster master
  ) implements Command {}
  
  private record NotifyConnectFailed(
        @NotNull Throwable throwable
  ) implements Command {}
}
