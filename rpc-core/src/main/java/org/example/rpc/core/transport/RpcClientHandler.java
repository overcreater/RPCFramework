package org.example.rpc.core.transport;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.AttributeKey;
import org.example.rpc.common.entity.RpcResponse;

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

        // 收到数据后关闭连接 (简单版 RPC，一问一答后断开)
        ctx.channel().close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}