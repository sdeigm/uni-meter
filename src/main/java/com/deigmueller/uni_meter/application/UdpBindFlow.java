package com.deigmueller.uni_meter.application;

import org.apache.pekko.actor.ActorRef;
import org.apache.pekko.actor.Terminated;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.io.Inet;
import org.apache.pekko.io.Udp;
import org.apache.pekko.io.UdpMessage;
import org.apache.pekko.stream.*;
import org.apache.pekko.stream.connectors.udp.Datagram;
import org.apache.pekko.stream.stage.GraphStageLogic;
import org.apache.pekko.stream.stage.GraphStageWithMaterializedValue;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;
import scala.runtime.BoxedUnit;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class UdpBindFlow extends GraphStageWithMaterializedValue<FlowShape<Datagram, Datagram>, CompletionStage<InetSocketAddress>>{
  // Instance members
  private final Inlet<Datagram> inlet = Inlet.create("UdpBindFlow.in");
  private final Outlet<Datagram> outlet = Outlet.create("UdpBindFlow.out");
  private final InetSocketAddress bindAddress;
  private final ActorRef udpMgr;
  
  public UdpBindFlow(@NotNull InetSocketAddress bindAddress,
                     @NotNull ActorSystem<?> actorSystem) {
    super();

    this.bindAddress = bindAddress;
    this.udpMgr = Udp.get(actorSystem).getManager();
  }
  
  @Override
  public FlowShape<Datagram, Datagram> shape() {
    return FlowShape.of(inlet, outlet);
  }

  @Override
  public Tuple2<GraphStageLogic, CompletionStage<InetSocketAddress>> createLogicAndMaterializedValue(Attributes inheritedAttributes) {
    CompletableFuture<InetSocketAddress> addressFuture = new CompletableFuture<>();
    return new Tuple2<>(new Logic(shape(), addressFuture, udpMgr, bindAddress), addressFuture);
  }
  
  private static class Logic extends GraphStageLogic {
    // Class members
    static final Logger logger = LoggerFactory.getLogger(Logic.class);
    
    // Instance members
    private final Inlet<Datagram> inlet;
    private final Outlet<Datagram> outlet;
    private final CompletableFuture<InetSocketAddress> addressFuture;
    private final ActorRef udpMgr;
    private final InetSocketAddress bindAddress;
    private StageActor stageActor;
    private ActorRef listener;
    
    public Logic(@NotNull FlowShape<Datagram,Datagram> shape,
                 @NotNull CompletableFuture<InetSocketAddress> addressFuture,
                 @NotNull ActorRef udpMgr,
                 @NotNull InetSocketAddress bindAddress) {
      super(shape);
      this.inlet = shape.in();
      this.outlet = shape.out();
      this.addressFuture = addressFuture;
      this.udpMgr = udpMgr;
      this.bindAddress = bindAddress;

      setHandler(inlet, () -> {
        Datagram datagram = grab(inlet);
        listener.tell(UdpMessage.send(datagram.data(), datagram.getRemote()), stageActor.ref());
        pull(inlet);
      });

      setHandler(outlet, () -> {});
    }

    @Override
    public void preStart() throws Exception {
      super.preStart();

      List<Inet.SocketOption> options = new ArrayList<>();

      stageActor = getStageActor(this::messageHandler);
      udpMgr.tell(UdpMessage.bind(stageActor.ref(), bindAddress, options), stageActor.ref());
    }

    @Override
    public void postStop() throws Exception {
      super.postStop();
      
      if (listener != null) {
        listener.tell(UdpMessage.unbind(), stageActor.ref());
      }
    }

    /**
     * Message handler for the GraphStage's actor
     * @param tuple Tuple containing sender and received message
     */
    private BoxedUnit messageHandler(Tuple2<ActorRef,Object> tuple) {
      logger.trace("UdpBindFlow.messageHandler()");

      final ActorRef sender = tuple._1();
      final Object message = tuple._2();
      
      if (message instanceof Udp.Bound udpBound) {
        addressFuture.complete(udpBound.localAddress());
        listener = sender;
        stageActor.watch(listener);
        pull(inlet);
      } else if (message instanceof Udp.CommandFailed commandFailed) {
        if (commandFailed.cmd() instanceof Udp.Bind) {
          Exception ex = new IllegalArgumentException("Unable to bind to " + bindAddress);
          addressFuture.completeExceptionally(ex);
          failStage(ex);
        }
      } else if (message instanceof Udp.Received udpReceived) {
        if (isAvailable(outlet)) {
          push(outlet, Datagram.create(udpReceived.data(), udpReceived.sender()));
        }
      } else if (message instanceof Terminated) {
        listener = null;
        failStage(new IllegalStateException("UDP listener terminated unexpectedly"));
      } else {
        logger.debug("unhandled message: {}", message.getClass());
      }
      
      return BoxedUnit.UNIT;
    }
  }

}
