package org.example.demo.consumer;

import org.example.rpc.api.OrderService;
import org.example.rpc.core.proxy.RpcClientProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Configuration
public class RpcConfig {

    @Bean
    public OrderService orderService() {
        // 1. 从注册中心获取地址
        String serviceName = OrderService.class.getName();
        String address = getAddressFromRegistry(serviceName);

        System.out.println("从注册中心发现服务地址: " + address);

        // 2. 解析 IP 和 端口
        String[] parts = address.split(":");
        String ip = parts[0];
        int port = Integer.parseInt(parts[1]);

        // 3. 创建代理
        return new RpcClientProxy(ip, port).getProxy(OrderService.class);
    }

    private String getAddressFromRegistry(String serviceName) {
        try {
            // 请求: http://localhost:8888/registry/discover?service=...
            String url = "http://localhost:8888/registry/discover?service=" + serviceName;

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // 响应类似: ["192.168.1.5:9999"] (JSON数组格式)
            String responseBody = response.body();

            // 简单的字符串处理 (不做 JSON 解析库依赖，为了省事)
            // 去掉 [" 和 "]
            responseBody = responseBody.replace("[", "").replace("]", "").replace("\"", "");

            if (responseBody.trim().isEmpty()) {
                throw new RuntimeException("注册中心没有找到服务: " + serviceName);
            }

            // 如果有多个，逗号分隔，我们暂时取第一个 (负载均衡的雏形在这里！)
            return responseBody.split(",")[0];

        } catch (Exception e) {
            throw new RuntimeException("无法连接注册中心: " + e.getMessage());
        }
    }
}