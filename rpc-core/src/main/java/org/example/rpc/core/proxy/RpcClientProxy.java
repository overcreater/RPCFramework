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

public class RpcClientProxy implements InvocationHandler {

    // 模式开关
    private String host;
    private int port;
    private String serviceName; // 如果有 serviceName，说明走注册中心模式

    // 构造器1：直连模式 (单服务器)
    public RpcClientProxy(String host, int port) {
        this.host = host;
        this.port = port;
    }

    // 构造器2：集群模式 (走注册中心)
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
        // 1. 封装请求
        RpcRequest request = RpcRequest.builder()
                .interfaceName(method.getDeclaringClass().getName())
                .methodName(method.getName())
                .paramTypes(method.getParameterTypes())
                .parameters(args)
                .build();

        // 2. 确定目标 IP (关键升级点！)
        String targetIp;
        int targetPort;

        if (this.serviceName != null) {
            // === 集群模式：去注册中心发现服务，并随机负载均衡 ===
            List<String> addressList = discoverFromRegistry(this.serviceName);
            // 简单随机负载均衡
            int index = new Random().nextInt(addressList.size());
            String chosenAddr = addressList.get(index);
            String[] parts = chosenAddr.split(":");
            targetIp = parts[0];
            targetPort = Integer.parseInt(parts[1]);
            System.out.println("【负载均衡】动态选择了节点: " + chosenAddr);
        } else {
            // === 直连模式 ===
            targetIp = this.host;
            targetPort = this.port;
        }

        // 3. 发送请求
        RpcClient client = new RpcClient(targetIp, targetPort);
        RpcResponse response = client.sendRequest(request);

        if (response == null) {
            throw new RuntimeException("远程调用失败");
        }
        return response.getData();
    }

    // 从注册中心获取列表的工具方法
    private List<String> discoverFromRegistry(String serviceName) {
        try {
            // 这里硬编码了注册中心地址，实际项目应该写配置
            String url = "http://10.206.255.171:8888/registry/discover?service=" + serviceName;
            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest httpRequest = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            String body = httpResponse.body().replace("[", "").replace("]", "").replace("\"", "");
            if (body.isEmpty()) return Collections.emptyList();
            return Arrays.asList(body.split(","));
        } catch (Exception e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }
}