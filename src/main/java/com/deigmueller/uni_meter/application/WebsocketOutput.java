package com.deigmueller.uni_meter.application;

import org.apache.pekko.NotUsed;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.http.javadsl.model.ws.BinaryMessage;
import org.apache.pekko.http.javadsl.model.ws.Message;
import org.apache.pekko.http.javadsl.model.ws.TextMessage;
import org.apache.pekko.stream.Materializer;
import org.apache.pekko.stream.OverflowStrategy;
import org.apache.pekko.stream.javadsl.Source;
import org.apache.pekko.stream.typed.javadsl.ActorSource;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public class WebsocketOutput {
  private WebsocketOutput() {}
  
  public static Optional<Throwable> failureMatcher(WebsocketOutput.Command command) {
    if (command instanceof WebsocketOutput.Failure) {
      return Optional.of(((WebsocketOutput.Failure) command).failure());
    } else {
      return Optional.empty();
    }
  }

  public static Source<Message, NotUsed> createSource(@NotNull Logger logger,
                                                      @NotNull Materializer materializer,
                                                      @NotNull Consumer<ActorRef<Command>> notifyServer) {
    return ActorSource.actorRef(
                c -> c instanceof WebsocketOutput.Close,
                WebsocketOutput::failureMatcher,
                10,
                OverflowStrategy.fail()
          ).filter(
                command -> command instanceof WebsocketOutput.Send
          ).map(
                command -> ((WebsocketOutput.Send) command).message()           
          ).map(
                message -> {
                  if (logger.isDebugEnabled()) {
                    String msg = message.isText()
                          ? message.asTextMessage().getStrictText()
                          : message.asBinaryMessage().getStrictData().utf8String();
                    logger.debug("send => {}", msg);
                  }
                  return message;
                }
          ).mapMaterializedValue(destinationRef -> {
            notifyServer.accept(destinationRef);
            return NotUsed.getInstance();
          }).map(message -> WebsocketOutput.toStrictMessage(message, materializer))
          .mapAsync(1, cs -> cs)
          .map(m -> m);
  }

  /**
   * Convert a Pekko WebSocket message into a strict message
   * @param message Message to convert
   * @param materializer Materializer to use
   * @return Completion stage which completes to a strict message
   */
  public static CompletionStage<? extends Message> toStrictMessage(Message message, Materializer materializer) {
    CompletionStage<? extends Message> completionStage;

    if(message instanceof TextMessage t) {
      completionStage = t.toStrict(5000, materializer);
    } else {
      BinaryMessage b = (BinaryMessage) message;
      completionStage = b.toStrict(5000, materializer);
    }

    return completionStage;
  }

  public interface Command {}

  public enum Close implements Command {
    INSTANCE
  }

  public record Failure(
        @NotNull Throwable failure
  ) implements Command { }
  
  public record Send(
        @NotNull Message message
  ) implements Command { }
}
