package org.example.rpc.core.transport;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.example.rpc.core.codec.CommonDecoder;
import org.example.rpc.core.codec.CommonEncoder;
import org.example.rpc.core.registry.ServiceRegistry;
import org.example.rpc.core.serializer.JsonSerializer;
import org.example.rpc.core.serializer.Serializer;

/**
 * RPC 服务端启动类
 */
public class RpcServer {

    private final String host;
    private final int port;
    private final ServiceRegistry serviceRegistry;
    private final Serializer serializer;

    public RpcServer(String host, int port) {
        this.host = host;
        this.port = port;
        this.serviceRegistry = new ServiceRegistry();
        this.serializer = new JsonSerializer(); // 默认使用 JSON
    }

    /**
     * 暴露服务接口
     */
    public <T> void publishService(Object service) {
        serviceRegistry.register(service);
    }

    /**
     * 启动 Netty 服务
     */
    public void start() {
        // 1. 创建两个线程组：Boss负责接客，Worker负责干活
        EventLoopGroup bossGroup = new NioEventLoopGroup();
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            // 核心流水线配置
                            // 入站：解码器 -> 处理器
                            // 出站：编码器
                            ch.pipeline().addLast(new CommonEncoder(serializer)); // 编码
                            ch.pipeline().addLast(new CommonDecoder());           // 解码
                            ch.pipeline().addLast(new RpcServerHandler(serviceRegistry)); // 业务处理
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            // 2. 绑定端口，同步等待成功
            ChannelFuture future = bootstrap.bind(host, port).sync();
            System.out.println("RPC Server 启动成功，监听端口: " + port);

            // 3. 等待服务端监听端口关闭
            future.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}