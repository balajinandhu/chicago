package com.xjeffrose.chicago.client;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.xjeffrose.chicago.ChicagoMessage;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import java.util.List;

public class EmbeddedConnectionManager implements ConnectionPoolManager {
  private EmbeddedChannel ch;

  public EmbeddedConnectionManager(EmbeddedChannel ch) {
    this.ch = ch;
  }

  @Override
  public ListenableFuture<Boolean> write(String addr, ChicagoMessage msg) {
    SettableFuture<Boolean> f = SettableFuture.create();
    ch.writeOutbound(msg);
    f.set(true);

    return f;
  }

  @Override
  public void start() {

  }
}
