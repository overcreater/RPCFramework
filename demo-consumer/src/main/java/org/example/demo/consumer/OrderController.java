package org.example.demo.consumer;

import jakarta.annotation.PostConstruct;
import org.example.rpc.api.CalculatorService;
import org.example.rpc.api.OrderService;
import org.example.rpc.core.proxy.RpcClientProxy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

@RestController
public class OrderController {

    @Autowired
    private OrderService orderService;

    private CalculatorService singleService;  // 模式A：单机直连（启动时锁定一个IP）
    private CalculatorService balanceService; // 模式B：负载均衡（每次调用随机IP）

    @PostConstruct
    public void init() {
        try {
            String serviceName = CalculatorService.class.getName();

            // 1. 初始化负载均衡代理
            this.balanceService = new RpcClientProxy(serviceName).getProxy(CalculatorService.class);

            // 2. 初始化单机直连代理 (保留你的逻辑)
            List<String> allAddress = fetchFromRegistry(serviceName);
            if (allAddress.isEmpty()) {
                System.out.println("⚠️ 警告：注册中心无服务，无法锁定单机目标");
                return;
            }

            // 智能选址：优先选远程，没有则选第一个
            String target = allAddress.get(0);
            String myIp = InetAddress.getLocalHost().getHostAddress();
            for (String addr : allAddress) {
                if (!addr.startsWith(myIp) && !addr.startsWith("127.0.0.1")) {
                    target = addr;
                    break;
                }
            }
            System.out.println("【单机模式】已锁定目标节点: " + target);

            String[] parts = target.split(":");
            this.singleService = new RpcClientProxy(parts[0], Integer.parseInt(parts[1])).getProxy(CalculatorService.class);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // --- 你原来的业务接口 (保留) ---

    @GetMapping("/buy")
    public String buy(@RequestParam String id) {
        return orderService.createOrder("User1", id);
    }

    @GetMapping("/calc/single")
    public String calcSingle(@RequestParam(defaultValue = "10000") int count) {
        long start = System.currentTimeMillis();
        // 显式调用 singleService
        String result = singleService.calculatePi(count);
        long end = System.currentTimeMillis();
        return result + " <br>⏱️【耗时】" + (end - start) + " ms (模式：锁定单机)";
    }

    @GetMapping("/calc/balance")
    public String calcBalance(@RequestParam(defaultValue = "10000") int count) {
        long start = System.currentTimeMillis();
        // 显式调用 balanceService
        String result = balanceService.calculatePi(count);
        long end = System.currentTimeMillis();
        return result + " <br>⏱️【耗时】" + (end - start) + " ms (模式：负载均衡)";
    }

    // --- 【新增】给网页可视化专用的 JSON 接口 ---
    @GetMapping("/api/visualize")
    public Map<String, Object> visualize(@RequestParam(defaultValue = "2000000") int count) {
        Map<String, Object> response = new HashMap<>();
        long start = System.currentTimeMillis();

        // 既然是可视化负载均衡，当然用 balanceService
        String rawResult = balanceService.calculatePi(count);

        long end = System.currentTimeMillis();

        // 解析 IP 用于前端变色
        String nodeIp = "Unknown";
        if (rawResult != null && rawResult.contains("【节点")) {
            try {
                int s = rawResult.indexOf("【节点") + 4;
                int e = rawResult.indexOf("】");
                if (e > s) nodeIp = rawResult.substring(s, e).trim();
            } catch (Exception ignored) {}
        }

        response.put("node", nodeIp);
        response.put("cost", end - start);
        return response;
    }

    private List<String> fetchFromRegistry(String serviceName) {
        try {
            String url = "http://localhost:8888/registry/discover?service=" + serviceName;
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            String body = resp.body().replace("[", "").replace("]", "").replace("\"", "");
            if (body.trim().isEmpty()) return Collections.emptyList();
            return Arrays.asList(body.split(","));
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}