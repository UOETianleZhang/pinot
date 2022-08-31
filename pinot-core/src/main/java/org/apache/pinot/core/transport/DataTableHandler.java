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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2StreamFrame;
import java.util.HashMap;
import java.util.Map;
import org.apache.pinot.common.metrics.BrokerMeter;
import org.apache.pinot.common.metrics.BrokerMetrics;
import org.apache.pinot.common.utils.DataTable;
import org.apache.pinot.core.common.datatable.DataTableFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The {@code DataTableHandler} is the Netty inbound handler on Pinot Broker side to handle the serialized data table
 * responses sent from Pinot Server.
 */
public class DataTableHandler extends SimpleChannelInboundHandler<Http2StreamFrame> {
  private static final Logger LOGGER = LoggerFactory.getLogger(DataTableHandler.class);

  private final QueryRouter _queryRouter;
  private final ServerRoutingInstance _serverRoutingInstance;
  private final BrokerMetrics _brokerMetrics;
  private final Map<Integer, ByteBuf> _unfinishedDataFrames;

  public DataTableHandler(QueryRouter queryRouter, ServerRoutingInstance serverRoutingInstance,
      BrokerMetrics brokerMetrics) {
    _queryRouter = queryRouter;
    _serverRoutingInstance = serverRoutingInstance;
    _brokerMetrics = brokerMetrics;
    _unfinishedDataFrames = new HashMap<>();
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) {
    LOGGER.info("Channel for server: {} is now active", _serverRoutingInstance);
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) {
    LOGGER.error("Channel for server: {} is now inactive, marking server down", _serverRoutingInstance);
    _queryRouter.markServerDown(_serverRoutingInstance,
        new RuntimeException(String.format("Channel for server: %s is inactive", _serverRoutingInstance)));
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, Http2StreamFrame message) {
    if (!(message instanceof Http2DataFrame)) {
      return;
    }
    int streamId = message.stream().id();
    LOGGER.info("DataTableHandler gets an http data frame in stream {}: {}", streamId, message);

    // check if we have previous data for the same stream and combine two parts
    ByteBuf msg = ((Http2DataFrame) message).content();
    if (_unfinishedDataFrames.containsKey(streamId)) {
      // retain() will increase the reference count of the msg by one to keep it
      msg = Unpooled.wrappedBuffer(_unfinishedDataFrames.get(streamId), msg.retain());
    }
    _unfinishedDataFrames.put(streamId, msg);

    if (!((Http2DataFrame) message).isEndStream()) {
      return;
    }
    _unfinishedDataFrames.remove(streamId);

    int responseSize = msg.readableBytes();
    _brokerMetrics.addMeteredGlobalValue(BrokerMeter.NETTY_CONNECTION_BYTES_RECEIVED, responseSize);
    try {
      long deserializationStartTimeMs = System.currentTimeMillis();
      DataTable dataTable = DataTableFactory.getDataTable(msg.nioBuffer());
      _queryRouter.receiveDataTable(_serverRoutingInstance, dataTable, responseSize,
          (int) (System.currentTimeMillis() - deserializationStartTimeMs));
    } catch (Exception e) {
      LOGGER.error("Caught exception while deserializing data table of size: {} from server: {}", responseSize,
          _serverRoutingInstance, e);
      _brokerMetrics.addMeteredGlobalValue(BrokerMeter.DATA_TABLE_DESERIALIZATION_EXCEPTIONS, 1);
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    LOGGER.error("Caught exception while handling response from server: {}", _serverRoutingInstance, cause);
    _brokerMetrics.addMeteredGlobalValue(BrokerMeter.RESPONSE_FETCH_EXCEPTIONS, 1);
  }
}
