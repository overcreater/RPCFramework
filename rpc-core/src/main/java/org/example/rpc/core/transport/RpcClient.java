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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Netty 客户端启动
 * 负责连接服务端，发送 RpcRequest
 * 支持连接池模式，提高性能
 */
public class RpcClient {

    private final String host;
    private final int port;
    // 是否使用连接池
    private final boolean useConnectionPool;
    // 连接池实例
    private static final ChannelPool channelPool = ChannelPool.getInstance();
    // 这里的序列化器需要和 Server 端保持一致
    private static final JsonSerializer serializer = new JsonSerializer();

    public RpcClient(String host, int port) {
        this(host, port, true); // 默认使用连接池
    }

    public RpcClient(String host, int port, boolean useConnectionPool) {
        this.host = host;
        this.port = port;
        this.useConnectionPool = useConnectionPool;
    }

    public RpcResponse sendRequest(RpcRequest request) {
        if (useConnectionPool) {
            return sendRequestWithPool(request);
        } else {
            return sendRequestWithoutPool(request);
        }
    }

    /**
     * 使用连接池发送请求
     */
    private RpcResponse sendRequestWithPool(RpcRequest request) {
        Channel channel = null;
        try {
            // 1. 从连接池获取连接
            channel = channelPool.getChannel(host, port);
            if (channel == null || !channel.isActive()) {
                throw new RuntimeException("无法获取可用连接: " + host + ":" + port);
            }

            // 2. 清除之前的响应（如果连接被复用）
            AttributeKey<RpcResponse> responseKey = AttributeKey.valueOf("rpcResponse");
            AttributeKey<Boolean> responseReceivedKey = AttributeKey.valueOf("responseReceived");
            AttributeKey<CountDownLatch> latchKey = AttributeKey.valueOf("responseLatch");

            channel.attr(responseKey).set(null);
            channel.attr(responseReceivedKey).set(false);

            // 创建CountDownLatch用于等待响应
            CountDownLatch latch = new CountDownLatch(1);
            channel.attr(latchKey).set(latch);

            // 3. 发送请求数据
            channel.writeAndFlush(request).sync();

            // 4. 等待响应（使用CountDownLatch，更高效）
            boolean received = latch.await(100, TimeUnit.SECONDS);

            if (received) {
                // 响应已接收
                RpcResponse response = channel.attr(responseKey).get();
                // 清除latch
                channel.attr(latchKey).set(null);

                if (response != null) {
                    // 归还连接到池中
                    channelPool.returnChannel(host, port, channel);
                    return response;
                }
            }

            // 超时或响应为空，关闭连接
            channel.attr(latchKey).set(null);
            channelPool.closeChannel(host, port, channel);
            throw new RuntimeException("RPC调用超时或响应为空: " + host + ":" + port);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (channel != null) {
                channelPool.closeChannel(host, port, channel);
            }
            return null;
        } catch (Exception e) {
            if (channel != null) {
                channelPool.closeChannel(host, port, channel);
            }
            throw new RuntimeException("RPC调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 不使用连接池发送请求（原有方式）
     * 使用 CountDownLatch 等待响应，然后关闭连接
     */
    private RpcResponse sendRequestWithoutPool(RpcRequest request) {
        EventLoopGroup group = new NioEventLoopGroup();
        Channel channel = null;
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
            channel = future.channel();

            // 2. 设置响应等待机制（类似连接池模式）
            AttributeKey<RpcResponse> responseKey = AttributeKey.valueOf("rpcResponse");
            AttributeKey<Boolean> responseReceivedKey = AttributeKey.valueOf("responseReceived");
            AttributeKey<CountDownLatch> latchKey = AttributeKey.valueOf("responseLatch");

            channel.attr(responseKey).set(null);
            channel.attr(responseReceivedKey).set(false);

            // 创建CountDownLatch用于等待响应
            CountDownLatch latch = new CountDownLatch(1);
            channel.attr(latchKey).set(latch);

            // 3. 发送请求数据
            channel.writeAndFlush(request).sync();

            // 4. 等待响应（最多10秒）
            boolean received = latch.await(10, TimeUnit.SECONDS);

            if (received) {
                // 5. 获取响应
                RpcResponse response = channel.attr(responseKey).get();
                // 6. 关闭连接（不使用连接池，每次请求后关闭）
                if (channel != null && channel.isActive()) {
                    channel.close();
                }
                return response;
            } else {
                // 超时
                if (channel != null && channel.isActive()) {
                    channel.close();
                }
                throw new RuntimeException("RPC调用超时: " + host + ":" + port);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (channel != null && channel.isActive()) {
                channel.close();
            }
            return null;
        } catch (Exception e) {
            if (channel != null && channel.isActive()) {
                channel.close();
            }
            throw new RuntimeException("RPC调用失败: " + e.getMessage(), e);
        } finally {
            // 关闭EventLoopGroup（会自动关闭所有连接）
            group.shutdownGracefully();
        }
    }
}