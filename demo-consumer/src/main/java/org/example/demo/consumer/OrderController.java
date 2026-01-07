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
import java.util.concurrent.*; // 新增：用于多线程并发

@RestController
public class OrderController {

    @Autowired(required = false) // 加上 required=false 防止启动报错，后续手动初始化
    private OrderService orderService;

    private CalculatorService singleService;  // 模式A：单机直连
    private CalculatorService balanceService; // 模式B：负载均衡

    @PostConstruct
    public void init() {
        try {
            String serviceName = CalculatorService.class.getName();

            // 1. 初始化负载均衡代理
            this.balanceService = new RpcClientProxy(serviceName).getProxy(CalculatorService.class);

            // 2. 初始化订单服务 (防止 Autowired 失败)
            this.orderService = new RpcClientProxy(OrderService.class.getName()).getProxy(OrderService.class);

            // 3. 初始化单机直连代理
            List<String> allAddress = fetchFromRegistry(serviceName);
            if (allAddress.isEmpty()) {
                System.out.println("⚠️ 警告：注册中心无服务，无法锁定单机目标");
                return;
            }

            // 智能选址
            String myLocalIp = "10.206.255.171";
            int myPort = 9999;
            System.out.println("【单机模式】强制锁定本机: " + myLocalIp + ":" + myPort);
            this.singleService = new RpcClientProxy(myLocalIp, myPort).getProxy(CalculatorService.class);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // --- 业务接口 ---

    @GetMapping("/buy")
    public String buy(@RequestParam String id) {
        return orderService.createOrder("User1", id);
    }

    @GetMapping("/calc/single")
    public String calcSingle(@RequestParam(defaultValue = "10000") int count) {
        return singleService.calculatePi(count);
    }

    @GetMapping("/calc/balance")
    public String calcBalance(@RequestParam(defaultValue = "10000") int count) {
        return balanceService.calculatePi(count);
    }

    @GetMapping("/api/visualize")
    public Map<String, Object> visualize(@RequestParam(defaultValue = "2000000") int count, @RequestParam String mode) {
        // 保留旧接口，用于单个调试
        long start = System.currentTimeMillis();
        String res = "single".equals(mode) ? singleService.calculatePi(count) : balanceService.calculatePi(count);
        long end = System.currentTimeMillis();

        Map<String, Object> map = new HashMap<>();
        map.put("node", extractIp(res));
        map.put("cost", end - start);
        return map;
    }

    // --- 【核心修改】服务端侧并发压测接口 ---
    @GetMapping("/api/benchmark/strict")
    public Map<String, Object> strictBenchmark(
            @RequestParam(defaultValue = "50") int requests,
            @RequestParam(defaultValue = "5000000") int complexity,
            @RequestParam String mode
    ) {
        Map<String, Object> result = new HashMap<>();

        // 1. 创建线程池，模拟 consumer 内部的高并发
        // 使用 CachedThreadPool 或 FixedThreadPool 都可以，Fixed 更能模拟固定用户数
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        List<Callable<String>> tasks = new ArrayList<>();

        long start = System.currentTimeMillis();

        // 2. 组装任务
        for (int i = 0; i < requests; i++) {
            tasks.add(() -> {
                if ("single".equals(mode)) {
                    return singleService.calculatePi(complexity);
                } else {
                    return balanceService.calculatePi(complexity);
                }
            });
        }

        // 3. 执行并获取结果
        List<String> nodes = new ArrayList<>();
        try {
            // invokeAll 会等待所有任务完成
            List<Future<String>> futures = executor.invokeAll(tasks);

            for (Future<String> f : futures) {
                // 提取 IP 用于前端绘图
                nodes.add(extractIp(f.get()));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        long end = System.currentTimeMillis();
        executor.shutdown();

        // 4. 返回统计数据
        result.put("totalTime", end - start);
        result.put("nodes", nodes); // 把所有处理节点的 IP 列表返给前端

        return result;
    }

    // 辅助方法：从结果字符串中提取 IP
    private String extractIp(String rawResult) {
        if (rawResult != null && rawResult.contains("【节点")) {
            try {
                int s = rawResult.indexOf("【节点") + 4;
                int e = rawResult.indexOf("】");
                if (e > s) return rawResult.substring(s, e).trim();
            } catch (Exception ignored) {}
        }
        return "Unknown";
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