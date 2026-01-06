package org.example.rpc.core.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.AttributeKey;
import org.example.rpc.common.entity.RpcRequest;
import org.example.rpc.common.entity.RpcResponse;
import org.example.rpc.core.codec.CommonDecoder;
import org.example.rpc.core.codec.CommonEncoder;
import org.example.rpc.core.serializer.JsonSerializer;

/**
 * Netty 客户端启动
 * 负责连接服务端，发送 RpcRequest
 */
public class RpcClient {

    private final String host;
    private final int port;
    // 这里的序列化器需要和 Server 端保持一致
    private static final JsonSerializer serializer = new JsonSerializer();

    public RpcClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public RpcResponse sendRequest(RpcRequest request) {
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            // 流水线：Client 和 Server 是相反的
                            // 出站：编码 (RpcRequest -> byte[])
                            ch.pipeline().addLast(new CommonEncoder(serializer));
                            // 入站：解码 (byte[] -> RpcResponse)
                            ch.pipeline().addLast(new CommonDecoder());
                            // 处理响应
                            ch.pipeline().addLast(new RpcClientHandler());
                        }
                    });

            // 1. 连接服务端
            ChannelFuture future = bootstrap.connect(host, port).sync();
            Channel channel = future.channel();

            // 2. 发送请求数据
            channel.writeAndFlush(request).sync();

            // 3. 阻塞等待连接关闭 (意味着数据接收完毕)
            channel.closeFuture().sync();

            // 4. 获取 RpcClientHandler 存入的结果
            AttributeKey<RpcResponse> key = AttributeKey.valueOf("rpcResponse");
            return channel.attr(key).get();

        } catch (InterruptedException e) {
            e.printStackTrace();
            return null;
        } finally {
            group.shutdownGracefully();
        }
    }
}