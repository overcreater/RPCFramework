package org.example.rpc.core.proxy;

import org.example.rpc.common.entity.RpcRequest;
import org.example.rpc.common.entity.RpcResponse;
import org.example.rpc.core.transport.RpcClient;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;

public class RpcClientProxy implements InvocationHandler {

    // 全局缓存与订阅记录
    private static final Map<String, List<String>> SERVICE_CACHE = new ConcurrentHashMap<>();
    //订阅列表
    private static final Set<String> SUBSCRIBED_SERVICES = ConcurrentHashMap.newKeySet();
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor();
    // 锁对象，用于DCL
    private static final Object LOCK = new Object();

    private static final String REGISTRY_HOST = "http://10.206.11.184:8888/registry/discover";

    // 后台定时刷新缓存
    static {
        SCHEDULER.scheduleAtFixedRate(() -> {
            try {
                if (SUBSCRIBED_SERVICES.isEmpty()) return;

                // 遍历所有订阅的服务，去注册中心拉取最新列表
                for (String service : SUBSCRIBED_SERVICES) {
                    // === 【修改点】每次刷新前先手动移除缓存，确保不残留脏数据 ===
                    SERVICE_CACHE.remove(service);

                    List<String> urls = discoverFromRegistry(service);
                    // 只有当注册中心有数据时才放回去
                    // 如果注册中心返回空（服务全挂了），缓存里就一直保持为空，Invoke的时候会报错而不是连错地址
                    if (!urls.isEmpty()) {
                        SERVICE_CACHE.put(service, urls);
                    }
                }
            } catch (Exception e) {
                System.err.println("【后台】缓存刷新失败: " + e.getMessage());
            }
        }, 100, 300, TimeUnit.SECONDS);
    }
    private String host;
    private int port;
    private String serviceName;

    // 构造器1：直连模式
    public RpcClientProxy(String host, int port) {
        this.host = host;
        this.port = port;
    }

    // 构造器2：集群模式
    public RpcClientProxy(String serviceName) {
        this.serviceName = serviceName;
    }

    @SuppressWarnings("unchecked")
    public <T> T getProxy(Class<T> clazz) {
        return (T) Proxy.newProxyInstance(
                clazz.getClassLoader(),
                new Class<?>[]{clazz},
                this
        );
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (Object.class.equals(method.getDeclaringClass())) {
            return method.invoke(this, args);
        }

        RpcRequest request = RpcRequest.builder()
                .interfaceName(method.getDeclaringClass().getName())
                .methodName(method.getName())
                .paramTypes(method.getParameterTypes())
                .parameters(args)
                .build();

        // 确定目标 IP
        String targetIp;
        int targetPort;

        // 集群模式：服务发现 + 负载均衡
        if (this.serviceName != null) {
            SUBSCRIBED_SERVICES.add(this.serviceName);

            // 尝试从本地缓存获取
            List<String> addressList = SERVICE_CACHE.get(this.serviceName);

            // Double-Check Locking 处理缓存击穿
            if (addressList == null || addressList.isEmpty()) {
                synchronized (LOCK) {
                    // 二次检查
                    addressList = SERVICE_CACHE.get(this.serviceName);
                    if (addressList == null || addressList.isEmpty()) {
                        System.out.println("【客户端】DCL锁命中，拉取注册中心: " + this.serviceName);
                        addressList = discoverFromRegistry(this.serviceName);
                        if (addressList.isEmpty()) {
                            throw new RuntimeException("无可用服务节点: " + this.serviceName);
                        }
                        SERVICE_CACHE.put(this.serviceName, addressList);
                    }
                }
            }

            // 随机负载均衡
            int index = new Random().nextInt(addressList.size());
            String chosenAddr = addressList.get(index);

            // 解析 IP:Port
            String[] parts = chosenAddr.split(":");
            targetIp = parts[0];
            targetPort = Integer.parseInt(parts[1]);


        } else {
            // 直连模式
            targetIp = this.host;
            targetPort = this.port;
        }

        // 发送请求
        RpcClient client = new RpcClient(targetIp, targetPort);
        RpcResponse response = client.sendRequest(request);

        if (response == null) {
            throw new RuntimeException("RPC调用无响应");
        }
        return response.getData();
    }

    // 注册中心查询工具
    private static List<String> discoverFromRegistry(String serviceName) {
        try {
            String url = REGISTRY_HOST + "?service=" + serviceName;
            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(3))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (httpResponse.statusCode() == 200) {
                String body = httpResponse.body().trim();
                String cleanBody = body.replace("[", "").replace("]", "").replace("\"", "");
                if (cleanBody.isEmpty()) return Collections.emptyList();

                // 去除可能存在的空白字符
                String[] parts = cleanBody.split(",");
                List<String> list = new ArrayList<>();
                for (String part : parts) {
                    list.add(part.trim());
                }
                return list;
            }
        } catch (Exception e) {
            System.err.println("【客户端】注册中心连接异常: " + e.getMessage());
        }
        return Collections.emptyList();
    }
}