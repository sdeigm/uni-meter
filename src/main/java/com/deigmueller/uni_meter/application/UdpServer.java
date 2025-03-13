package com.deigmueller.uni_meter.application;

import org.apache.pekko.NotUsed;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.stream.Materializer;
import org.apache.pekko.stream.OverflowStrategy;
import org.apache.pekko.stream.connectors.udp.Datagram;
import org.apache.pekko.stream.javadsl.Keep;
import org.apache.pekko.stream.typed.javadsl.ActorSink;
import org.apache.pekko.stream.typed.javadsl.ActorSource;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.Optional;

public class UdpServer {
  public static void createServer(@NotNull Logger logger,
                                  @NotNull ActorSystem<?> system,
                                  @NotNull Materializer materializer,
                                  @NotNull InetSocketAddress bindAddress,
                                  @NotNull ActorRef<Notification> input) {

    ActorSource.<Datagram>actorRef(
          Objects::isNull,
          object -> Optional.empty(),
          10,
          OverflowStrategy.dropHead()
    ).map(
          datagram -> {
            if (logger.isDebugEnabled()) {
              logger.debug("send => {}", datagram.getData().utf8String());
            }
            return datagram;
          }
    ).mapMaterializedValue(outputActor -> {
      input.tell(new SourceInitialized(outputActor));
      return NotUsed.getInstance();
    }).viaMat(
          new UdpBindFlow(bindAddress, system), Keep.right()
    ).map(datagram -> {
      if (logger.isDebugEnabled()) {
        logger.debug("recv => {}", datagram.getData().utf8String());
      }
      return datagram;
    }).to(
          ActorSink.actorRefWithBackpressure(
                input,
                (replyTo, datagram) -> new DatagramReceived(datagram, replyTo), 
                SinkInitialized::new,
                Ack.INSTANCE,
                SinkClosed.INSTANCE, 
                SinkFailed::new
          )
    ).run(materializer
    ).whenComplete((binding, failure) -> {
      if (binding != null) {
        input.tell(new NotifyBindSucceeded(binding));
      }
    });
  }

  public enum Ack {
    INSTANCE
  }
  
  public interface Notification {}
  
  public record SourceInitialized(
        @NotNull ActorRef<Datagram> output
  ) implements Notification {}

  public record SinkInitialized(
        @NotNull ActorRef<Ack> replyTo
  ) implements Notification {}

  public enum SinkClosed implements Notification {
    INSTANCE
  }

  public record SinkFailed(
        @NotNull Throwable failure
  ) implements Notification {}

  public record NotifyBindSucceeded(
        @NotNull InetSocketAddress address
  ) implements Notification {}

  public record DatagramReceived(
        @NotNull Datagram datagram,
        @NotNull ActorRef<Ack> replyTo
  ) implements Notification {}
}
