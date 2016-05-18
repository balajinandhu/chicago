package com.xjeffrose.chicago.client;

import com.xjeffrose.chicago.ZkClient;
import com.xjeffrose.xio.SSL.XioSecurityHandlerImpl;
import com.xjeffrose.xio.client.retry.BoundedExponentialBackoffRetry;
import com.xjeffrose.xio.client.retry.RetryLoop;
import com.xjeffrose.xio.client.retry.TracerDriver;
import com.xjeffrose.xio.core.XioIdleDisconnectHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;

import io.netty.channel.pool.SimpleChannelPool;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.log4j.Logger;

public class ConnectionPoolManager {
  private static final Logger log = Logger.getLogger(ChicagoClient.class);
  private final static String NODE_LIST_PATH = "/chicago/node-list";

  private final Map<String, Listener> listenerMap = new HashMap<>();
  private final Map<String, ChannelFuture> connectionMap = new HashMap<>();
  private final ZkClient zkClient;

  public ConnectionPoolManager(ZkClient zkClient) {

    this.zkClient = zkClient;
    refreshPool();
  }

  private List<String> buildNodeList() {
    return zkClient.list(NODE_LIST_PATH);
  }

  private void refreshPool() {
    buildNodeList().stream().forEach(xs -> {
      listenerMap.put(xs, new ChicagoListener());
      connect(new InetSocketAddress(xs, 12000), listenerMap.get(xs));
    });
  }

  public ChannelFuture getNode(String node) {
    ChannelFuture cf = connectionMap.get(node);
   if (cf == null) {
     try {
       Thread.sleep(5);
       return getNode(node);
     } catch (InterruptedException e) {
       e.printStackTrace();
     }
   }
    if (cf.isSuccess()) {
      if (cf.channel().isWritable()) {
        return cf;
      }
    }

    cf.cancel(true);
    connectionMap.remove(node);
    connect(new InetSocketAddress(node, 12000), listenerMap.get(node));

    return getNode(node);
  }

  public Listener getListener(String node) {
    return listenerMap.get(node);
  }

  public void addNode(String hostname, ChannelFuture future) {
    connectionMap.put(hostname, future);

  }

  private void connect(InetSocketAddress server, Listener listener) {
    // Start the connection attempt.
    Bootstrap bootstrap = new Bootstrap();
    bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 500)
        .option(ChannelOption.SO_REUSEADDR, true)
        .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
        .option(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, 32 * 1024)
        .option(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK, 8 * 1024)
        .option(ChannelOption.TCP_NODELAY, true);
    bootstrap.group(new NioEventLoopGroup(20))
        .channel(NioSocketChannel.class)
        .handler(new ChannelInitializer<SocketChannel>() {
          @Override
          protected void initChannel(SocketChannel channel) throws Exception {
            ChannelPipeline cp = channel.pipeline();
            cp.addLast(new XioSecurityHandlerImpl(true).getEncryptionHandler());
//            cp.addLast(new XioSecurityHandlerImpl(true).getAuthenticationHandler());
            cp.addLast(new XioIdleDisconnectHandler(60, 60, 60));
            cp.addLast(new ChicagoClientCodec());
            cp.addLast(new ChicagoClientHandler(listener));
          }
        });

    BoundedExponentialBackoffRetry retry = new BoundedExponentialBackoffRetry(50, 500, 4);

    TracerDriver tracerDriver = new TracerDriver() {

      @Override
      public void addTrace(String name, long time, TimeUnit unit) {
      }

      @Override
      public void addCount(String name, int increment) {
      }
    };

    RetryLoop retryLoop = new RetryLoop(retry, new AtomicReference<>(tracerDriver));

    connect2(server, bootstrap, retryLoop);
  }

  private void connect2(InetSocketAddress server, Bootstrap bootstrap, RetryLoop retryLoop) {
    ChannelFutureListener listener = new ChannelFutureListener() {
      @Override
      public void operationComplete(ChannelFuture future) {
        if (!future.isSuccess()) {
          try {
            retryLoop.takeException((Exception) future.cause());
            log.error("==== Service connect failure (will retry)", future.cause());
            connect2(server, bootstrap, retryLoop);
          } catch (Exception e) {
            log.error("==== Service connect failure ", future.cause());
            // Close the connection if the connection attempt has failed.
            future.channel().close();
          }
        } else {
          log.debug("Chicago connected: ");
          String hostname = ((InetSocketAddress) future.channel().remoteAddress()).getHostName();
          if (hostname.equals("localhost")) {
            hostname = "127.0.0.1";
          }
          addNode(hostname, future);
        }
      }
    };

    bootstrap.connect(server).addListener(listener);
  }
}
