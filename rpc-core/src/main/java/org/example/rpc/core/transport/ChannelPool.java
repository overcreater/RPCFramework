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
import java.util.concurrent.locks.ReentrantLock;

/**
 * Netty Channel 连接池
 * 管理客户端到服务端的连接，实现连接复用
 * <p>
 * 改进点：
 * 1. 使用锁机制防止竞态条件，严格限制连接数
 * 2. 区分连接状态：总连接数、池中连接数、使用中连接数
 * 3. 改进日志输出，显示详细的连接状态
 * 4. 缩短清理间隔，及时清理无效连接
 */
public class ChannelPool {

    // 单例模式
    private static volatile ChannelPool instance;

    // 连接池：Key=地址(IP:Port), Value=连接队列
    private final ConcurrentHashMap<String, BlockingQueue<Channel>> pool = new ConcurrentHashMap<>();

    // 每个地址的最大连接数
    private static final int MAX_CONNECTIONS_PER_ADDRESS = 50;

    // Bootstrap和EventLoopGroup复用
    private final Bootstrap bootstrap;
    private final EventLoopGroup eventLoopGroup;

    // 序列化器
    private static final JsonSerializer serializer = new JsonSerializer();

    // 连接统计信息：Key=地址, Value=总连接数
    private final ConcurrentHashMap<String, AtomicInteger> connectionCounts = new ConcurrentHashMap<>();

    // 每个地址的锁，防止竞态条件
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

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

        // 启动定时任务，清理空闲连接（缩短间隔到30秒）
        startIdleConnectionCleaner();
    }

    /**
     * 获取指定地址的锁
     */
    private ReentrantLock getLock(String key) {
        return locks.computeIfAbsent(key, k -> new ReentrantLock());
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
        ReentrantLock lock = getLock(key);
        BlockingQueue<Channel> queue = pool.computeIfAbsent(key, k -> new LinkedBlockingQueue<>());

        Channel channel = null;

        // 第一步：尝试从池中获取可用连接（无需加锁）
        while ((channel = queue.poll()) != null) {
            if (channel.isActive() && channel.isOpen()) {
                // 连接可用，直接返回
                int poolSize = queue.size();
                int totalCount = connectionCounts.computeIfAbsent(key, k -> new AtomicInteger(0)).get();
                System.out.println("【连接池】复用连接: " + key +
                        " (池中=" + poolSize + ", 总计=" + totalCount + ", 使用中=" + (totalCount - poolSize) + ")");
                return channel;
            }
            // 连接已关闭，从计数中移除
            AtomicInteger count = connectionCounts.get(key);
            if (count != null) {
                count.decrementAndGet();
            }
        }

        // 第二步：池中没有可用连接，需要创建新连接（必须加锁）
        lock.lock();
        try {
            // 双重检查：再次尝试从池中获取（可能其他线程刚归还了连接）
            while ((channel = queue.poll()) != null) {
                if (channel.isActive() && channel.isOpen()) {
                    int poolSize = queue.size();
                    int totalCount = connectionCounts.computeIfAbsent(key, k -> new AtomicInteger(0)).get();
                    System.out.println("【连接池】复用连接: " + key +
                            " (池中=" + poolSize + ", 总计=" + totalCount + ", 使用中=" + (totalCount - poolSize) + ")");
                    return channel;
                }
                AtomicInteger count = connectionCounts.get(key);
                if (count != null) {
                    count.decrementAndGet();
                }
            }

            // 检查是否可以创建新连接
            AtomicInteger count = connectionCounts.computeIfAbsent(key, k -> new AtomicInteger(0));
            int currentCount = count.get();

            if (currentCount >= MAX_CONNECTIONS_PER_ADDRESS) {
                // 已达到最大连接数，等待池中的连接
                lock.unlock(); // 释放锁，等待连接
                try {
                    channel = queue.poll(3, TimeUnit.SECONDS);
                    if (channel != null && channel.isActive() && channel.isOpen()) {
                        int poolSize = queue.size();
                        int totalCount = connectionCounts.computeIfAbsent(key, k -> new AtomicInteger(0)).get();
                        System.out.println("【连接池】等待后复用连接: " + key +
                                " (池中=" + poolSize + ", 总计=" + totalCount + ", 使用中=" + (totalCount - poolSize) + ")");
                        return channel;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                // 等待超时，强制创建新连接（超过限制，但保证可用性）
                lock.lock();
            }

            // 创建新连接
            return createChannel(host, port, key);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 创建新连接（必须在锁内调用）
     */
    private Channel createChannel(String host, int port, String key) {
        try {
            ChannelFuture future = bootstrap.connect(host, port).sync();
            Channel channel = future.channel();

            if (channel.isActive()) {
                AtomicInteger count = connectionCounts.computeIfAbsent(key, k -> new AtomicInteger(0));
                int newCount = count.incrementAndGet();
                int poolSize = pool.get(key).size();
                System.out.println("【连接池】创建新连接: " + key +
                        " (池中=" + poolSize + ", 总计=" + newCount + ", 使用中=" + (newCount - poolSize) + ")");
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
        ReentrantLock lock = getLock(key);
        BlockingQueue<Channel> queue = pool.get(key);

        if (queue == null) {
            // 池不存在，关闭连接
            channel.close();
            AtomicInteger count = connectionCounts.get(key);
            if (count != null) {
                count.decrementAndGet();
            }
            return;
        }

        lock.lock();
        try {
            if (channel.isActive() && channel.isOpen()) {
                // 连接仍然有效，放回池中
                int poolSizeBefore = queue.size();
                if (poolSizeBefore < MAX_CONNECTIONS_PER_ADDRESS) {
                    queue.offer(channel);
                    int poolSizeAfter = queue.size();
                    int totalCount = connectionCounts.computeIfAbsent(key, k -> new AtomicInteger(0)).get();
                    System.out.println("【连接池】归还连接: " + key +
                            " (池中=" + poolSizeAfter + ", 总计=" + totalCount + ", 使用中=" + (totalCount - poolSizeAfter) + ")");
                } else {
                    // 池已满，关闭连接
                    channel.close();
                    AtomicInteger count = connectionCounts.get(key);
                    if (count != null) {
                        int newCount = count.decrementAndGet();
                        System.out.println("【连接池】池满关闭连接: " + key + " (总计=" + newCount + ")");
                    }
                }
            } else {
                // 连接已关闭，从计数中移除
                AtomicInteger count = connectionCounts.get(key);
                if (count != null) {
                    int newCount = count.decrementAndGet();
                    System.out.println("【连接池】移除无效连接: " + key + " (总计=" + newCount + ")");
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 关闭连接（不归还到池中）
     */
    public void closeChannel(String host, int port, Channel channel) {
        if (channel == null) {
            return;
        }

        String key = host + ":" + port;
        ReentrantLock lock = getLock(key);

        lock.lock();
        try {
            if (channel.isActive()) {
                channel.close();
            }
            AtomicInteger count = connectionCounts.get(key);
            if (count != null) {
                int newCount = count.decrementAndGet();
                int poolSize = pool.get(key) != null ? pool.get(key).size() : 0;
                System.out.println("【连接池】关闭连接: " + key +
                        " (池中=" + poolSize + ", 总计=" + newCount + ", 使用中=" + (newCount - poolSize) + ")");
            }
        } finally {
            lock.unlock();
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
        }, 30, 30, TimeUnit.SECONDS); // 每30秒清理一次（缩短间隔）
    }

    /**
     * 清理空闲连接
     */
    private void cleanIdleConnections() {
        for (String key : pool.keySet()) {
            BlockingQueue<Channel> queue = pool.get(key);
            if (queue == null) continue;

            ReentrantLock lock = getLock(key);
            lock.lock();
            try {
                int removed = 0;
                BlockingQueue<Channel> newQueue = new LinkedBlockingQueue<>();

                while (!queue.isEmpty()) {
                    Channel channel = queue.poll();
                    if (channel == null) break;

                    if (channel.isActive() && channel.isOpen()) {
                        // 连接有效，保留
                        newQueue.offer(channel);
                    } else {
                        // 连接已关闭，移除并更新计数
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
                    int poolSize = newQueue.size();
                    int totalCount = connectionCounts.get(key) != null ? connectionCounts.get(key).get() : 0;
                    System.out.println("【连接池】清理无效连接: " + key +
                            " (移除 " + removed + " 个, 池中=" + poolSize + ", 总计=" + totalCount + ")");
                }
            } finally {
                lock.unlock();
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
            int inUse = totalCount - poolSize;
            sb.append(String.format("  %s: 池中=%d, 使用中=%d, 总计=%d (最大=%d)\n",
                    key, poolSize, inUse, totalCount, MAX_CONNECTIONS_PER_ADDRESS));
        }
        return sb.toString();
    }

    /**
     * 关闭连接池
     */
    public void shutdown() {
        // 关闭所有连接
        for (String key : pool.keySet()) {
            ReentrantLock lock = getLock(key);
            lock.lock();
            try {
                BlockingQueue<Channel> queue = pool.get(key);
                if (queue != null) {
                    while (!queue.isEmpty()) {
                        Channel channel = queue.poll();
                        if (channel != null && channel.isActive()) {
                            channel.close();
                        }
                    }
                }
            } finally {
                lock.unlock();
            }
        }
        pool.clear();
        connectionCounts.clear();
        locks.clear();

        // 关闭EventLoopGroup
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully();
        }
    }
}

