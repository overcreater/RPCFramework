package org.example.rpc.core.transport;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.AttributeKey;
import org.example.rpc.common.entity.RpcResponse;

import java.util.concurrent.CountDownLatch;

/**
 * Netty 客户端处理器
 * 负责读取服务器端返回的RpcResponse，和 ServerHandler 是对应的
 */
public class RpcClientHandler extends SimpleChannelInboundHandler<RpcResponse> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RpcResponse msg) throws Exception {
        // 收到消息后，把结果存入 Channel 的属性中，以便主线程获取
        // AttributeKey 是 Netty 提供的类似于 Map 的东西，绑定在 Channel 上
        AttributeKey<RpcResponse> key = AttributeKey.valueOf("rpcResponse");
        ctx.channel().attr(key).set(msg);

        // 设置响应已接收标志，用于连接池模式
        AttributeKey<Boolean> responseReceivedKey = AttributeKey.valueOf("responseReceived");
        ctx.channel().attr(responseReceivedKey).set(true);

        // 使用CountDownLatch通知等待的线程
        AttributeKey<CountDownLatch> latchKey = AttributeKey.valueOf("responseLatch");
        CountDownLatch latch = ctx.channel().attr(latchKey).get();
        if (latch != null) {
            latch.countDown(); // 通知等待的线程
        }

        // 注意：连接池模式下不关闭连接，连接会被归还到池中
        // 只有在非连接池模式下才关闭连接
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}