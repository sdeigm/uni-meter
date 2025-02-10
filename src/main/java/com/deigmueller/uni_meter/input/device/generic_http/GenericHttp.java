/*
 * Copyright (C) 2018-2023 layline.io GmbH <http://www.layline.io>
 */

package com.deigmueller.uni_meter.input.device.generic_http;

import com.deigmueller.uni_meter.input.device.common.generic.ChannelReader;
import com.deigmueller.uni_meter.input.device.common.generic.GenericInputDevice;
import com.deigmueller.uni_meter.input.device.common.generic.JsonChannelReader;
import com.deigmueller.uni_meter.output.OutputDevice;
import com.typesafe.config.Config;
import lombok.AccessLevel;
import lombok.Getter;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.ReceiveBuilder;
import org.apache.pekko.http.javadsl.Http;
import org.apache.pekko.http.javadsl.model.HttpEntity;
import org.apache.pekko.http.javadsl.model.HttpRequest;
import org.apache.pekko.http.javadsl.model.headers.HttpCredentials;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Getter(AccessLevel.PROTECTED)
public class GenericHttp extends GenericInputDevice {
  // Class members
  public static final String TYPE = "GenericHttp";
  
  // Instance members
  private final Http http = Http.get(getContext().getSystem());
  private final Duration pollingInterval = getConfig().getDuration("polling-interval");
  private final String url = getConfig().getString("url");
  private final List<ChannelReader> channelReaders = new ArrayList<>();
  private final HttpCredentials credentials;

  /**
   * Static setup method
   * @param outputDevice Output device to notify
   * @param config Input device configuration
   * @return Behavior of the created actor
   */
  public static Behavior<Command> create(@NotNull ActorRef<OutputDevice.Command> outputDevice,
                                         @NotNull Config config) {
    return Behaviors.setup(context -> new GenericHttp(context, outputDevice, config));
  }

  /**
   * Protected constructor called by the setup method.
   * @param context Actor context
   * @param outputDevice Output device to notify
   * @param config Input device configuration
   */
  protected GenericHttp(@NotNull ActorContext<Command> context, 
                        @NotNull ActorRef<OutputDevice.Command> outputDevice, 
                        @NotNull Config config) {
    super(context, outputDevice, config);

    credentials = !getConfig().getString("username").isEmpty() || !getConfig().getString("password").isEmpty()
          ? HttpCredentials.createBasicHttpCredentials(getConfig().getString("username"), getConfig().getString("password"))
          : null;
    
    initChannelReaders();

    executeHttpPolling();
  }

  /**
   * Create the actor's ReceiveBuilder.
   * @return The actor's ReceiveBuilder
   */
  @Override
  public ReceiveBuilder<Command> newReceiveBuilder() {
    return super.newReceiveBuilder()
          .onMessage(HttpRequestFailed.class, this::onHttpRequestFailed)
          .onMessage(HttpRequestSuccess.class, this::onHttpRequestSuccess)
          .onMessage(ExecuteNextHttpPolling.class, this::onExecuteNextHttpPolling);
  }

  /**
   * Handle a StatusRequestFailed message.
   * @param message Message to handle
   * @return Same behavior
   */
  protected Behavior<Command> onHttpRequestFailed(@NotNull HttpRequestFailed message) {
    logger.trace("GenericHttp.onHttpRequestFailed()");

    logger.error("failed to execute http polling: {}", message.throwable().getMessage());

    startNextPollingTimer();

    return Behaviors.same();
  }

  /**
   * Handle a StatusRequestSuccess message.
   * @param message Message to handle
   * @return Same behavior
   */
  protected Behavior<Command> onHttpRequestSuccess(@NotNull HttpRequestSuccess message) {
    logger.trace("GenericHttp.onHttpRequestSuccess()");

    try {
      String response = message.entity().getData().utf8String();
      logger.debug("http response: {}", response);

      boolean changes = false;
      for (ChannelReader channelReader : channelReaders) {
        Double value = channelReader.getValue(logger, response);
        if (value != null) {
          setChannelData(channelReader.getChannel(), value);
          changes = true;
        }
      }

      if (changes) {
        notifyOutputDevice();
      }
    } catch (Exception e) {
      logger.error("Failed to parse status response: {}", e.getMessage());
    }

    startNextPollingTimer();

    return Behaviors.same();
  }

  /**
   * Handle the notification to execute the next status polling.
   * @param message Notification message
   * @return Same behavior
   */
  protected Behavior<Command> onExecuteNextHttpPolling(@NotNull GenericHttp.ExecuteNextHttpPolling message) {
    logger.trace("GenericHttp.onExecuteNextStatusPolling()");

    executeHttpPolling();

    return Behaviors.same();
  }

  /**
   * Initialize the channel readers.
   */
  private void initChannelReaders() {
    logger.trace("GenericHttp.initChannelReaders()");

    for (Config channelConfig : getConfig().getConfigList("channels")) {
      if ("json".equals(channelConfig.getString("type"))) {
        channelReaders.add(new JsonChannelReader(channelConfig));
      } else {
        throw new IllegalArgumentException("unknown channel type: " + channelConfig.getString("type"));
      }
    }
  }

  /**
   * Execute the HTTP polling.
   */
  private void executeHttpPolling() {
    logger.trace("GenericHttp.executeHttpPolling()");

    HttpRequest httpRequest = HttpRequest.create(url);
    if (credentials != null) {
      httpRequest = httpRequest.addCredentials(credentials);
    }

    getHttp().singleRequest(httpRequest)
          .whenComplete((response, throwable) -> {
            if (throwable != null) {
              getContext().getSelf().tell(new HttpRequestFailed(throwable));
            } else {
              try {
                response.entity()
                      .toStrict(5000, getMaterializer())
                      .whenComplete((strictEntity, toStrictFailure) -> {
                        if (toStrictFailure != null) {
                          getContext().getSelf().tell(new HttpRequestFailed(toStrictFailure));
                        } else {
                          getContext().getSelf().tell(new HttpRequestSuccess(strictEntity));
                        }
                      });
              } catch (Exception e) {
                // Failed to get a strict entity
                getContext().getSelf().tell(new HttpRequestFailed(e));
              }
            }
          });
  }
  
  /**
   * Start the next polling timer.
   */
  private void startNextPollingTimer() {
    logger.trace("GenericHttp.startNextPollingTimer()");

    getContext().getSystem().scheduler().scheduleOnce(
          pollingInterval,
          () -> getContext().getSelf().tell(ExecuteNextHttpPolling.INSTANCE),
          getContext().getExecutionContext());
  }

  protected record HttpRequestFailed(
        @NotNull Throwable throwable
  ) implements Command {}

  protected record HttpRequestSuccess(
        @NotNull HttpEntity.Strict entity
  ) implements Command {}

  protected enum ExecuteNextHttpPolling implements Command {
    INSTANCE
  }
}
