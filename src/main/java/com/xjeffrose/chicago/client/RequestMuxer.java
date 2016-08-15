package com.xjeffrose.chicago.client;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.internal.PlatformDependent;
import java.net.InetSocketAddress;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RequestMuxer<T> {
  // WARNING!!!!!!!
  // This is the magic required to prevent deadlocks.
  // DO NOT CHANGE THIS VALUE or risk undoing all the
  // magic held therein...
  private static final int MAGIC_NUMBER = 23;
  private static final int POOL_SIZE = 4;


  private final String addr;
  private final EventLoopGroup workerLoop;
  private final AtomicBoolean isRunning = new AtomicBoolean();
  private final Deque<ChannelFuture> connectionQ = PlatformDependent.newConcurrentDeque();
  @Getter
  private final Deque<MuxedMessage<T>> messageQ = PlatformDependent.newConcurrentDeque();
  @Setter
  private ChicagoConnector connector;
  private AtomicLong counter = new AtomicLong();

  public RequestMuxer(String addr, ChannelHandler handler, EventLoopGroup workerLoop) {
    this.addr = addr;
    this.workerLoop = workerLoop;
    this.connector = new ChicagoConnector(handler, workerLoop);
  }

  public void start() throws Exception {
    buildInitialConnectionQ();
    blockAndAwaitPool();
    isRunning.set(true);

    new Thread(() -> {
      while (isRunning.get()) {
        if (connectionQ.size() < 1) {
          log.info("========================== Rebuilding NodeList ================================");
          rebuildConnectionQ();
        }
      }
    }).start();

  }

  public void shutdownGracefully() {
    isRunning.set(false);
  }

  private InetSocketAddress address(String node) {
    String chunks[] = node.split(":");
    return new InetSocketAddress(chunks[0], Integer.parseInt(chunks[1]));
  }

  private void buildInitialConnectionQ() {
    for (int i = 0; i < POOL_SIZE; i++) {
      Futures.addCallback(connector.connect(address(addr)), new FutureCallback<ChannelFuture>() {
        @Override
        public void onSuccess(@Nullable ChannelFuture channelFuture) {
          connectionQ.addLast(channelFuture);

        }

        @Override
        public void onFailure(Throwable throwable) {
          log.error("Error connecting to " + addr, throwable);
        }
      });
    }
  }

  void rebuildConnectionQ() {
    rebuildConnectionQ(this.connectionQ);
  }

  private void rebuildConnectionQ(Deque<ChannelFuture> connectionQ) {
    connectionQ.stream().forEach(xs -> {
      ChannelFuture cf = xs;
      connectionQ.remove(xs);
      if (cf.channel().isWritable()) {
        connectionQ.addLast(cf);
      } else {
        Futures.addCallback(connector.connect(address(addr)), new FutureCallback<ChannelFuture>() {
          @Override
          public void onSuccess(@Nullable ChannelFuture channelFuture) {
            connectionQ.addLast(channelFuture);
          }

          @Override
          public void onFailure(Throwable throwable) {
            log.error("Error connecting to " + addr, throwable);
          }
        });
      }
    });
  }

  private boolean blockAndAwaitPool(long timeout, TimeUnit timeUnit) {

    return blockAndAwaitPool();
  }

  private boolean blockAndAwaitPool() {
    while (connectionQ.size() != POOL_SIZE) {
      try {
        Thread.sleep(1);
      } catch (InterruptedException e) {
        e.printStackTrace();
        return false;
      }
    }

    return true;
  }

  public void write(T sendReq, SettableFuture<Boolean> f) {
    if (isRunning.get()) {
//      if (counter.incrementAndGet() % MAGIC_NUMBER == 0) {
//        try {
//          Thread.sleep(0, 1);
//          counter.set(0);
//        } catch (InterruptedException e) {
//          e.printStackTrace();
//        }
//      }
      drainMessageQ(sendReq, f);
    }
  }

  private Channel requestNode() {
//    ChannelFuture cf = connectionQ.pollFirst();
        ChannelFuture cf = connectionQ.peekFirst();

    if ((cf != null) && cf.isSuccess()) {
      if (cf.channel().isWritable()) {
        connectionQ.addLast(cf);
        return cf.channel();
      } else {
        connectionQ.remove(cf);
//        rebuildConnectionQ(connectionQ);
        log.error("Error connecting channel wasnt writable");
        return connectionQ.pollLast().channel();
      }
    } else {
      connectionQ.remove(cf);
//      rebuildConnectionQ(connectionQ);
      log.error("Error connecting channel was empty");
      return connectionQ.pollLast().channel();
    }
  }

  private void drainMessageQ() {
    if (isRunning.get() && messageQ.size() > 0) {
      final MuxedMessage<T> mm = messageQ.pollFirst();
      requestNode().writeAndFlush(mm.getMsg()).addListener(new GenericFutureListener<Future<? super Void>>() {
        @Override
        public void operationComplete(Future<? super Void> future) throws Exception {
          if (future.isSuccess()) {
            mm.getF().set(true);
          } else {
            mm.getF().set(false);
            mm.getF().setException(future.cause());
          }
        }
      });
    }
  }

  private void drainMessageQ(T sendReq, SettableFuture<Boolean> f) {
    if (isRunning.get()) {
      requestNode().writeAndFlush(sendReq).addListener(new GenericFutureListener<Future<? super Void>>() {
        @Override
        public void operationComplete(Future<? super Void> future) throws Exception {
          if (future.isSuccess()) {
            f.set(true);
          } else {
            f.set(false);
            f.setException(future.cause());
          }
        }
      });
    }
  }

  @Data
  private class MuxedMessage<T> {
    private final T msg;
    private final SettableFuture<Boolean> f;
  }

}
