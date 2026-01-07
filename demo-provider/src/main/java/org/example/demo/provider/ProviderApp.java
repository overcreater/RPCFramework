package org.example.demo.provider;

import org.example.rpc.api.OrderService;
import org.example.rpc.core.transport.RpcServer;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class ProviderApp {
    // 注册中心地址
    private static final String REGISTRY_URL = "http://localhost:8888/registry/register";

    public static void main(String[] args) throws Exception {
        // 1. 确定本机信息
        int myPort = 9999;
        String myIp = InetAddress.getLocalHost().getHostAddress(); // 获取本机局域网IP
        String serviceName = OrderService.class.getName();

        // 2. 启动 RPC 服务
        RpcServer server = new RpcServer("0.0.0.0", myPort);
        server.publishService(new OrderServiceImpl());

        // 3. 【关键】向注册中心汇报
        // 开启一个新线程去注册，避免阻塞启动
        new Thread(() -> {
            try {
                // 等待一会确保 Server 启动了
                Thread.sleep(1000);
                String myAddress = myIp + ":" + myPort;
                registerToRegistry(serviceName, myAddress);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        server.start();
    }

    private static void registerToRegistry(String service, String address) {
        try {
            // 使用 Java 11 HttpClient 发送 POST 请求
            String url = REGISTRY_URL + "?service=" + service + "&address=" + address;
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .POST(HttpRequest.BodyPublishers.noBody()) // 这里用 URL 参数传值，Body 为空
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("注册结果: " + response.body());
        } catch (Exception e) {
            System.err.println("注册中心连接失败，请检查 simple-registry 是否启动！");
        }
    }
}