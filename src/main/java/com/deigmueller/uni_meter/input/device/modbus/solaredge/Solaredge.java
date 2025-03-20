/*
 * Copyright (C) 2018-2023 layline.io GmbH <http://www.layline.io>
 */

package com.deigmueller.uni_meter.input.device.modbus.solaredge;

import com.deigmueller.uni_meter.input.device.modbus.Modbus;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.digitalpetri.modbus.pdu.ReadInputRegistersRequest;
import com.digitalpetri.modbus.pdu.ReadInputRegistersResponse;
import com.typesafe.config.Config;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;

public class Solaredge extends Modbus {
  // Class members
  public static final String TYPE = "Solaredge";

  // Instance members
  private final int powerRegister = getConfig().getInt("power-register");
  private double power;


  public static Behavior<Command> create(@NotNull ActorRef<OutputDevice.Command> outputDevice,
                                         @NotNull Config config) {
    return Behaviors.setup(context -> new Solaredge(context, outputDevice, config));
  }

  protected Solaredge(@NotNull ActorContext<Command> context, 
                      @NotNull ActorRef<OutputDevice.Command> outputDevice, 
                      @NotNull Config config) {
    super(context, 
            outputDevice, 
            config.withFallback(context.getSystem().settings().config().getConfig("uni-meter.input-devices.modbus")));
  }

  @Override
  public @NotNull ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onMessage(ReadPowerSucceeded.class, this::onReadPowerSucceeded);
  }

  @Override
  protected @NotNull Behavior<Command> onConnectSucceeded(@NotNull NotifyConnectSucceeded message) {
    logger.trace("Solaredge.onConnectSucceeded()");
    super.onConnectSucceeded(message);

    readPower();

    return Behaviors.same();
  }

  @Override
  protected @NotNull Behavior<Command> onStartNextPollingCycle(@NotNull StartNextPollingCycle message) {
    logger.trace("Solaredge.onStartNextPollingCycle()");

    readPower();

    return Behaviors.same();
  }
  
  protected @NotNull Behavior<Command> onReadPowerSucceeded(@NotNull ReadPowerSucceeded message) {
    logger.trace("Solaredge.onReadPowerSucceeded()");

    try {
      ByteBuffer byteBuffer = ByteBuffer.wrap(message.response.registers());
      
      short powerValue = byteBuffer.getShort();
      short scaleFactor = byteBuffer.getShort();
      
      power = (double) powerValue * Math.pow(10.0, scaleFactor);
      logger.debug("power {} W (value={}, scale={})", power, powerValue, scaleFactor);

      notifyPowerData();

      startNextPollingTimer();
      
    } catch (Exception exception) {
      logger.error("failed to convert voltage", exception);
      startNextPollingTimer();
    }

    return Behaviors.same();
  }
  
  private void notifyPowerData() {
    getOutputDevice().tell(new OutputDevice.NotifyTotalPowerData(
            getNextMessageId(),
            new OutputDevice.PowerData(
                    power, 
                    power, 
                    1.0, 
                    power / 230.0, 
                    230.0, 
                    50.0),
            getOutputDeviceAckAdapter()));
  }

  private void readPower() {
    logger.trace("Solaredge.readPower()");

    // Read voltage 
    getClient()
          .readInputRegistersAsync(getUnitId(), new ReadInputRegistersRequest(powerRegister, 0x0002))
          .whenComplete((response, throwable) -> {
      if (throwable != null) {
        getContext().getSelf().tell(new ReadInputRegistersFailed(powerRegister, 0x0002, throwable));
      } else {
        getContext().getSelf().tell(new ReadPowerSucceeded(response));
      }
    });
  }

  public record ReadPowerSucceeded(
        ReadInputRegistersResponse response
  ) implements Command {}
}
