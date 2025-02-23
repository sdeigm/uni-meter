package com.deigmueller.uni_meter.application;

import org.apache.pekko.NotUsed;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.http.javadsl.model.ws.Message;
import org.apache.pekko.stream.Materializer;
import org.apache.pekko.stream.javadsl.Flow;
import org.apache.pekko.stream.javadsl.Sink;
import org.apache.pekko.stream.typed.javadsl.ActorSink;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.net.InetAddress;

import static com.deigmueller.uni_meter.application.WebsocketOutput.toStrictMessage;

public class WebsocketInput {
  public static Sink<Message, NotUsed> createSink(@NotNull Logger logger,
                                                  @NotNull String connectionId,
                                                  @NotNull InetAddress remoteAddress,
                                                  @NotNull Materializer materializer,
                                                  @NotNull ActorRef<Notification> device) {
    return Flow.of(Message.class)
          .map(message -> toStrictMessage(message, materializer))
          .mapAsync(1, cs -> cs)
          .map(message -> {
            if (logger.isDebugEnabled()) {
              String msg = message.isText() 
                    ? message.asTextMessage().getStrictText() 
                    : message.asBinaryMessage().getStrictData().utf8String();
              logger.debug("recv <= {}", msg);
            }
            return message;
          })
          .to(
                ActorSink.actorRefWithBackpressure(
                      device,
                      (replyTo, message) -> new NotifyMessageReceived(connectionId, remoteAddress, message, replyTo),
                      (ack) -> new NotifyOpened(connectionId, remoteAddress, ack),
                      Ack.INSTANCE,
                      new NotifyClosed(connectionId), 
                      (failure) -> new NotifyFailed(connectionId, failure)
                )
          );
  }
  
  public enum Ack {
    INSTANCE
  }
  
  public interface Notification {}
  
  public record NotifyOpened(
        @NotNull String connectionId,
        @NotNull InetAddress remoteAddress,
        @NotNull ActorRef<Ack> replyTo
  ) implements Notification {}

  public record NotifyClosed(
        @NotNull String connectionId
  ) implements Notification {}

  public record NotifyFailed(
        @NotNull String connectionId,
        @NotNull Throwable failure
  ) implements Notification {}

  public record NotifyMessageReceived(
        @NotNull String connectionId,
        @NotNull InetAddress remoteAddress,
        @NotNull Message message,
        @NotNull ActorRef<Ack> replyTo
  ) implements Notification {}
}
