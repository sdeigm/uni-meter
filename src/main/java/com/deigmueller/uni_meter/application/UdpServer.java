package com.deigmueller.uni_meter.application;

import org.apache.pekko.NotUsed;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.stream.OverflowStrategy;
import org.apache.pekko.stream.connectors.udp.Datagram;
import org.apache.pekko.stream.connectors.udp.javadsl.Udp;
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
            input.tell(new NotifyOutput(outputActor));
            return NotUsed.getInstance();
          }).viaMat(
                Udp.bindFlow(bindAddress, system), Keep.right()
          )
          .map(datagram -> {
            if (logger.isDebugEnabled()) {
              logger.debug("recv => {}", datagram.getData().utf8String());
            }
            return datagram;
          })
          .to(
                ActorSink.actorRefWithBackpressure(
                      input,
                      (replyTo, datagram) -> new NotifyDatagramReceived(datagram, replyTo), 
                      NotifyInitialized::new,
                      Ack.INSTANCE,
                      NotifyClosed.INSTANCE, 
                      NotifyFailed::new
                )
          ).run(system)
          .whenComplete((binding, failure) -> {
            if (failure != null) {
              input.tell(new NotifyFailed(failure));
            } else {
              input.tell(new NotifyBound(binding));
            }
          });
  }

  public enum Ack {
    INSTANCE
  }
  
  public interface Notification {}
  
  public record NotifyOutput(
        @NotNull ActorRef<Datagram> output
  ) implements Notification {}

  public record NotifyInitialized(
        @NotNull ActorRef<Ack> replyTo
  ) implements Notification {}

  public enum NotifyClosed implements Notification {
    INSTANCE
  }

  public record NotifyFailed(
        @NotNull Throwable failure
  ) implements Notification {}
  
  public record NotifyBound(
        @NotNull InetSocketAddress address
  ) implements Notification {}

  public record NotifyDatagramReceived(
        @NotNull Datagram datagram,
        @NotNull ActorRef<Ack> replyTo
  ) implements Notification {}
}
