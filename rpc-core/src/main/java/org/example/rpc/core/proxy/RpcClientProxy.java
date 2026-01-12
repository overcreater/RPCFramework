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
                for (String service : SUBSCRIBED_SERVICES) {
                    List<String> urls = discoverFromRegistry(service);
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

    public RpcClientProxy(String host, int port) {
        this.host = host;
        this.port = port;
    }

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

        String targetIp;
        int targetPort;

        // 集群模式：服务发现 + 负载均衡
        if (this.serviceName != null) {
            SUBSCRIBED_SERVICES.add(this.serviceName);

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
            String[] parts = chosenAddr.split(":");
            targetIp = parts[0];
            targetPort = Integer.parseInt(parts[1]);

        } else {
            // 直连模式
            targetIp = this.host;
            targetPort = this.port;
        }

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