package org.example.rpc.core.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.example.rpc.core.codec.CommonDecoder;
import org.example.rpc.core.codec.CommonEncoder;
import org.example.rpc.core.serializer.JsonSerializer;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Netty Channel 连接池
 * 管理客户端到服务端的连接，实现连接复用
 */
public class ChannelPool {

    // 单例模式
    private static volatile ChannelPool instance;

    // 连接池：Key=地址(IP:Port), Value=连接队列
    private final ConcurrentHashMap<String, BlockingQueue<Channel>> pool = new ConcurrentHashMap<>();

    // 每个地址的最大连接数
    private static final int MAX_CONNECTIONS_PER_ADDRESS = 10;

    // Bootstrap和EventLoopGroup复用
    private final Bootstrap bootstrap;
    private final EventLoopGroup eventLoopGroup;

    // 序列化器
    private static final JsonSerializer serializer = new JsonSerializer();

    // 统计信息
    private final ConcurrentHashMap<String, AtomicInteger> connectionCounts = new ConcurrentHashMap<>();

    private ChannelPool() {
        // 创建共享的EventLoopGroup和Bootstrap
        this.eventLoopGroup = new NioEventLoopGroup();
        this.bootstrap = new Bootstrap();
        this.bootstrap.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new CommonEncoder(serializer));
                        ch.pipeline().addLast(new CommonDecoder());
                        ch.pipeline().addLast(new RpcClientHandler());
                    }
                });

        // 启动定时任务，清理空闲连接
        startIdleConnectionCleaner();
    }

    /**
     * 获取连接池单例
     */
    public static ChannelPool getInstance() {
        if (instance == null) {
            synchronized (ChannelPool.class) {
                if (instance == null) {
                    instance = new ChannelPool();
                }
            }
        }
        return instance;
    }

    /**
     * 获取连接
     *
     * @param host 服务端IP
     * @param port 服务端端口
     * @return Channel连接
     */
    public Channel getChannel(String host, int port) {
        String key = host + ":" + port;
        BlockingQueue<Channel> queue = pool.computeIfAbsent(key, k -> new LinkedBlockingQueue<>());

        Channel channel = null;

        // 尝试从池中获取可用连接
        while ((channel = queue.poll()) != null) {
            if (channel.isActive() && channel.isOpen()) {
                // 连接可用，直接返回
                return channel;
            }
            // 连接已关闭，继续尝试下一个
        }

        // 池中没有可用连接，检查是否可以创建新连接
        AtomicInteger count = connectionCounts.computeIfAbsent(key, k -> new AtomicInteger(0));
        if (count.get() >= MAX_CONNECTIONS_PER_ADDRESS) {
            // 已达到最大连接数，等待池中的连接
            try {
                channel = queue.poll(3, TimeUnit.SECONDS);
                if (channel != null && channel.isActive() && channel.isOpen()) {
                    return channel;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // 等待超时，创建新连接（可能超过最大连接数，但保证可用性）
        }

        // 创建新连接
        return createChannel(host, port, key);
    }

    /**
     * 创建新连接
     */
    private Channel createChannel(String host, int port, String key) {
        try {
            ChannelFuture future = bootstrap.connect(host, port).sync();
            Channel channel = future.channel();

            if (channel.isActive()) {
                AtomicInteger count = connectionCounts.computeIfAbsent(key, k -> new AtomicInteger(0));
                count.incrementAndGet();
                System.out.println("【连接池】创建新连接: " + key + " (当前连接数: " + count.get() + ")");
                return channel;
            }
        } catch (Exception e) {
            System.err.println("【连接池】创建连接失败: " + key + " - " + e.getMessage());
        }
        return null;
    }

    /**
     * 归还连接到池中
     *
     * @param host    服务端IP
     * @param port    服务端端口
     * @param channel 要归还的连接
     */
    public void returnChannel(String host, int port, Channel channel) {
        if (channel == null) {
            return;
        }

        String key = host + ":" + port;
        BlockingQueue<Channel> queue = pool.get(key);

        if (queue == null) {
            // 池不存在，关闭连接
            channel.close();
            return;
        }

        if (channel.isActive() && channel.isOpen()) {
            // 连接仍然有效，放回池中
            if (queue.size() < MAX_CONNECTIONS_PER_ADDRESS) {
                queue.offer(channel);
            } else {
                // 池已满，关闭连接
                channel.close();
                AtomicInteger count = connectionCounts.get(key);
                if (count != null) {
                    count.decrementAndGet();
                }
            }
        } else {
            // 连接已关闭，从计数中移除
            AtomicInteger count = connectionCounts.get(key);
            if (count != null) {
                count.decrementAndGet();
            }
        }
    }

    /**
     * 关闭连接（不归还到池中）
     */
    public void closeChannel(String host, int port, Channel channel) {
        if (channel != null && channel.isActive()) {
            channel.close();
            String key = host + ":" + port;
            AtomicInteger count = connectionCounts.get(key);
            if (count != null) {
                count.decrementAndGet();
            }
        }
    }

    /**
     * 启动空闲连接清理器
     */
    private void startIdleConnectionCleaner() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ChannelPool-Cleaner");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(() -> {
            try {
                cleanIdleConnections();
            } catch (Exception e) {
                System.err.println("【连接池】清理空闲连接异常: " + e.getMessage());
            }
        }, 60, 60, TimeUnit.SECONDS); // 每60秒清理一次
    }

    /**
     * 清理空闲连接
     */
    private void cleanIdleConnections() {
        for (String key : pool.keySet()) {
            BlockingQueue<Channel> queue = pool.get(key);
            if (queue == null) continue;

            int removed = 0;
            BlockingQueue<Channel> newQueue = new LinkedBlockingQueue<>();

            while (!queue.isEmpty()) {
                Channel channel = queue.poll();
                if (channel == null) break;

                if (channel.isActive() && channel.isOpen()) {
                    // 检查连接是否空闲时间过长
                    // 注意：这里简化处理，实际应该记录每个连接的最后使用时间
                    newQueue.offer(channel);
                } else {
                    // 连接已关闭，移除
                    removed++;
                    AtomicInteger count = connectionCounts.get(key);
                    if (count != null) {
                        count.decrementAndGet();
                    }
                }
            }

            // 更新队列
            pool.put(key, newQueue);

            if (removed > 0) {
                System.out.println("【连接池】清理无效连接: " + key + " (移除 " + removed + " 个)");
            }
        }
    }

    /**
     * 获取连接池统计信息
     */
    public String getPoolStats() {
        StringBuilder sb = new StringBuilder();
        sb.append("【连接池统计】\n");
        for (String key : pool.keySet()) {
            BlockingQueue<Channel> queue = pool.get(key);
            AtomicInteger count = connectionCounts.get(key);
            int poolSize = queue != null ? queue.size() : 0;
            int totalCount = count != null ? count.get() : 0;
            sb.append(String.format("  %s: 池中=%d, 总计=%d\n", key, poolSize, totalCount));
        }
        return sb.toString();
    }

    /**
     * 关闭连接池
     */
    public void shutdown() {
        // 关闭所有连接
        for (BlockingQueue<Channel> queue : pool.values()) {
            while (!queue.isEmpty()) {
                Channel channel = queue.poll();
                if (channel != null && channel.isActive()) {
                    channel.close();
                }
            }
        }
        pool.clear();
        connectionCounts.clear();

        // 关闭EventLoopGroup
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully();
        }
    }
}

