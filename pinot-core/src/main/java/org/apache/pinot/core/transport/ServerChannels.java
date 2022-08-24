/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.core.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import org.apache.pinot.common.config.NettyConfig;
import org.apache.pinot.common.config.TlsConfig;
import org.apache.pinot.common.metrics.BrokerGauge;
import org.apache.pinot.common.metrics.BrokerMeter;
import org.apache.pinot.common.metrics.BrokerMetrics;
import org.apache.pinot.common.metrics.BrokerTimer;
import org.apache.pinot.common.request.InstanceRequest;
import org.apache.pinot.common.utils.TlsUtils;
import org.apache.pinot.core.util.OsCheck;
import org.apache.thrift.TSerializer;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.transport.TTransportException;

import static io.netty.handler.codec.http.HttpResponseStatus.*;


/**
 * The {@code ServerChannels} class manages the channels between broker to all the connected servers.
 * <p>There is only one channel between the broker and each connected server (we count OFFLINE and REALTIME as different
 * servers)
 */
@ThreadSafe
public class ServerChannels {
  public static final String CHANNEL_LOCK_TIMEOUT_MSG = "Timeout while acquiring channel lock";
  private static final long TRY_CONNECT_CHANNEL_LOCK_TIMEOUT_MS = 5_000L;

  private final QueryRouter _queryRouter;
  private final BrokerMetrics _brokerMetrics;
  // TSerializer currently is not thread safe, must be put into a ThreadLocal.
  private final ThreadLocal<TSerializer> _threadLocalTSerializer;
  private final ConcurrentHashMap<ServerRoutingInstance, ServerChannel> _serverToChannelMap = new ConcurrentHashMap<>();
  private final TlsConfig _tlsConfig;
  private final EventLoopGroup _eventLoopGroup;
  private final Class<? extends SocketChannel> _channelClass;

  /**
   * Create a server channel with TLS config
   *
   * @param queryRouter query router
   * @param brokerMetrics broker metrics
   * @param tlsConfig TLS/SSL config
   */
  public ServerChannels(QueryRouter queryRouter, BrokerMetrics brokerMetrics, @Nullable NettyConfig nettyConfig,
      @Nullable TlsConfig tlsConfig) {
    if (nettyConfig != null && nettyConfig.isNativeTransportsEnabled()
        && OsCheck.getOperatingSystemType() == OsCheck.OSType.Linux) {
      _eventLoopGroup = new EpollEventLoopGroup();
      _channelClass = EpollSocketChannel.class;
    } else if (nettyConfig != null && nettyConfig.isNativeTransportsEnabled()
        && OsCheck.getOperatingSystemType() == OsCheck.OSType.MacOS) {
      _eventLoopGroup = new KQueueEventLoopGroup();
      _channelClass = KQueueSocketChannel.class;
    } else {
      _eventLoopGroup = new NioEventLoopGroup();
      _channelClass = NioSocketChannel.class;
    }

    _queryRouter = queryRouter;
    _brokerMetrics = brokerMetrics;
    _tlsConfig = tlsConfig;
    _threadLocalTSerializer = ThreadLocal.withInitial(() -> {
      try {
        return new TSerializer(new TCompactProtocol.Factory());
      } catch (TTransportException e) {
        throw new RuntimeException("Failed to initialize Thrift Serializer", e);
      }
    });
  }

  public void sendRequest(String rawTableName, AsyncQueryResponse asyncQueryResponse,
      ServerRoutingInstance serverRoutingInstance, InstanceRequest instanceRequest, long timeoutMs)
      throws Exception {
    byte[] requestBytes = _threadLocalTSerializer.get().serialize(instanceRequest);
    _serverToChannelMap.computeIfAbsent(serverRoutingInstance, ServerChannel::new)
        .sendRequest(rawTableName, asyncQueryResponse, serverRoutingInstance, requestBytes, timeoutMs);
  }

  public void connect(ServerRoutingInstance serverRoutingInstance)
      throws InterruptedException, TimeoutException {
    _serverToChannelMap.computeIfAbsent(serverRoutingInstance, ServerChannel::new).connect();
  }

  public void shutDown() {
    // Shut down immediately
    _eventLoopGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS);
  }

  @ThreadSafe
  private class ServerChannel {
    final ServerRoutingInstance _serverRoutingInstance;
    final Bootstrap _bootstrap;
    // lock to protect channel as requests must be written into channel sequentially
    final ReentrantLock _channelLock = new ReentrantLock();
    Channel _http2StreamChannel; // HTTP/2 stram channel

    ServerChannel(ServerRoutingInstance serverRoutingInstance) {
      _serverRoutingInstance = serverRoutingInstance;
      _bootstrap = new Bootstrap().remoteAddress(serverRoutingInstance.getHostname(), serverRoutingInstance.getPort())
          .group(_eventLoopGroup).channel(_channelClass)
          .option(ChannelOption.SO_KEEPALIVE, true)
          .handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
              if (_tlsConfig != null) {
                attachSSLHandler(ch);
              }

              // Encode and decode the message in a way of HTTP/2
              ch.pipeline().addLast(Http2FrameCodecBuilder.forClient().build());
              // Enable HTTP/2 multiplexing. The real handler is in DataTableHandler
              ch.pipeline().addLast(new Http2MultiplexHandler(new SimpleChannelInboundHandler() {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
                  // NOOP (this is the handler for 'inbound' streams, which is a server push)
                }
              }));
            }
          });
    }

    void attachSSLHandler(SocketChannel ch) {
      try {
        SslContextBuilder sslContextBuilder =
            SslContextBuilder.forClient().sslProvider(SslProvider.valueOf(_tlsConfig.getSslProvider()));

        if (_tlsConfig.getKeyStorePath() != null) {
          sslContextBuilder.keyManager(TlsUtils.createKeyManagerFactory(_tlsConfig));
        }

        if (_tlsConfig.getTrustStorePath() != null) {
          sslContextBuilder.trustManager(TlsUtils.createTrustManagerFactory(_tlsConfig));
        }

        ch.pipeline().addLast("ssl", sslContextBuilder.build().newHandler(ch.alloc()));
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    void sendRequest(String rawTableName, AsyncQueryResponse asyncQueryResponse,
        ServerRoutingInstance serverRoutingInstance, byte[] requestBytes, long timeoutMs)
        throws InterruptedException, TimeoutException {
      if (_channelLock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) {
        try {
          connectWithoutLocking();
          sendRequestWithoutLocking(rawTableName, asyncQueryResponse, serverRoutingInstance, requestBytes);
        } finally {
          _channelLock.unlock();
        }
      } else {
        throw new TimeoutException(CHANNEL_LOCK_TIMEOUT_MSG);
      }
    }

    void connectWithoutLocking()
        throws InterruptedException {
      if (_http2StreamChannel == null || !_http2StreamChannel.isActive()) {
        long startTime = System.currentTimeMillis();
        Channel channel = _bootstrap.connect().sync().channel();

        final Http2StreamChannelBootstrap streamChannelBootstrap = new Http2StreamChannelBootstrap(channel);
        _http2StreamChannel = streamChannelBootstrap.open().syncUninterruptibly().getNow();
        // NOTE: data table de-serialization happens inside this DataTableHandler
        // Revisit if this becomes a bottleneck
        _http2StreamChannel.pipeline().addLast(new DataTableHandler(_queryRouter, _serverRoutingInstance, _brokerMetrics));
        _brokerMetrics.setValueOfGlobalGauge(BrokerGauge.NETTY_CONNECTION_CONNECT_TIME_MS,
            System.currentTimeMillis() - startTime);
      }
    }

    void sendRequestWithoutLocking(String rawTableName, AsyncQueryResponse asyncQueryResponse,
        ServerRoutingInstance serverRoutingInstance, byte[] requestBytes) {
      long startTimeMs = System.currentTimeMillis();

      // Write HTTP/2 header frame
      Http2Headers headers = new DefaultHttp2Headers();
      headers.status(OK.toString());
      _http2StreamChannel.write(new DefaultHttp2HeadersFrame(headers));

      DefaultHttp2DataFrame dataFrame = new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(requestBytes), true);
      _http2StreamChannel.writeAndFlush(dataFrame).addListener(f -> {
        long requestSentLatencyMs = System.currentTimeMillis() - startTimeMs;
        _brokerMetrics.addTimedTableValue(rawTableName, BrokerTimer.NETTY_CONNECTION_SEND_REQUEST_LATENCY,
            requestSentLatencyMs, TimeUnit.MILLISECONDS);
        asyncQueryResponse.markRequestSent(serverRoutingInstance, requestSentLatencyMs);
      });
      _brokerMetrics.addMeteredGlobalValue(BrokerMeter.NETTY_CONNECTION_REQUESTS_SENT, 1);
      _brokerMetrics.addMeteredGlobalValue(BrokerMeter.NETTY_CONNECTION_BYTES_SENT, requestBytes.length);
    }

    void connect()
        throws InterruptedException, TimeoutException {
      if (_channelLock.tryLock(TRY_CONNECT_CHANNEL_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        try {
          connectWithoutLocking();
        } finally {
          _channelLock.unlock();
        }
      } else {
        throw new TimeoutException(CHANNEL_LOCK_TIMEOUT_MSG);
      }
    }

  }
}
