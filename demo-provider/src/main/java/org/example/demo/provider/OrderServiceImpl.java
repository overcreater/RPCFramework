package org.example.demo.provider;

import org.example.rpc.api.OrderService;
import java.net.InetAddress;

public class OrderServiceImpl implements OrderService {
    @Override
    public String createOrder(String userId, String productId) {
        String serverIp = "Unknown";
        try {
            serverIp = InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) { e.printStackTrace(); }

        System.out.println("【服务端】接收下单请求: " + userId + " -> " + productId);
        return String.format("下单成功！\n处理节点IP: %s\n商品: %s", serverIp, productId);
    }
}