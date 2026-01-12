package org.example.demo.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.example.rpc.api.CalculatorService;
import org.example.rpc.api.OrderService;
import org.example.rpc.core.proxy.RpcClientProxy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@RestController
public class OrderController {

    @Autowired(required = false) // 加上 required=false 防止启动报错，后续手动初始化
    private OrderService orderService;

    private CalculatorService singleService;  // 模式A：单机直连
    private CalculatorService balanceService; // 模式B：负载均衡

    // 连接池对比测试用的服务实例
    private CalculatorService singleServiceWithPool;    // 使用连接池
    private CalculatorService singleServiceWithoutPool; // 不使用连接池

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

            // 智能选址：使用localhost连接本机服务（即使注册中心无服务也尝试连接）
            String localhost = "localhost";
            int port = 9999;

            if (allAddress.isEmpty()) {
                System.out.println("⚠️ 警告：注册中心无服务，将尝试直接连接 localhost:" + port);
            } else {
                System.out.println("【单机模式】锁定本机: " + localhost + ":" + port);
            }

            this.singleService = new RpcClientProxy(localhost, port).getProxy(CalculatorService.class);

            // 初始化连接池对比测试用的服务实例（无论注册中心是否有服务都初始化）
            this.singleServiceWithPool = new RpcClientProxy(localhost, port, true).getProxy(CalculatorService.class);
            this.singleServiceWithoutPool = new RpcClientProxy(localhost, port, false).getProxy(CalculatorService.class);
            System.out.println("【连接池对比】已初始化：使用连接池=" + true + ", 不使用连接池=" + false);
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
            } catch (Exception ignored) {
            }
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

    // --- 连接池对比测试接口 ---

    /**
     * 使用连接池的测试
     */
    @GetMapping("/api/benchmark/with-pool")
    public Map<String, Object> benchmarkWithPool(
            @RequestParam(defaultValue = "50") int requests,
            @RequestParam(defaultValue = "5000000") int complexity
    ) {
        return runPoolComparisonBenchmark(requests, complexity, true);
    }

    /**
     * 不使用连接池的测试
     */
    @GetMapping("/api/benchmark/without-pool")
    public Map<String, Object> benchmarkWithoutPool(
            @RequestParam(defaultValue = "50") int requests,
            @RequestParam(defaultValue = "5000000") int complexity
    ) {
        return runPoolComparisonBenchmark(requests, complexity, false);
    }

    /**
     * 连接池对比测试的核心逻辑
     */
    private Map<String, Object> runPoolComparisonBenchmark(int requests, int complexity, boolean usePool) {
        Map<String, Object> result = new HashMap<>();

        // 检查服务实例是否初始化
        CalculatorService service = usePool ? singleServiceWithPool : singleServiceWithoutPool;
        if (service == null) {
            result.put("error", true);
            result.put("message", "服务未初始化，请检查服务提供者是否已启动");
            System.err.println("【连接池测试】错误：服务实例为null，usePool=" + usePool);
            return result;
        }

        // 清空连接池统计（如果使用连接池）
        if (usePool) {
            System.out.println("\n【连接池测试】开始测试 - 使用连接池模式");
            System.out.println("【连接池测试】并发数=" + requests + ", 计算强度=" + complexity);
        } else {
            System.out.println("\n【连接池测试】开始测试 - 不使用连接池模式");
            System.out.println("【连接池测试】并发数=" + requests + ", 计算强度=" + complexity);
        }

        ExecutorService executor = Executors.newFixedThreadPool(requests);
        List<Callable<String>> tasks = new ArrayList<>();

        long start = System.currentTimeMillis();

        // 组装任务
        for (int i = 0; i < requests; i++) {
            final int taskId = i;
            tasks.add(() -> {
                try {
                    return service.calculatePi(complexity);
                } catch (Exception e) {
                    System.err.println("【连接池测试】任务 " + taskId + " 失败: " + e.getMessage());
                    return "Error: " + e.getMessage();
                }
            });
        }

        // 执行并获取结果
        List<String> nodes = new ArrayList<>();
        int successCount = 0;
        int errorCount = 0;
        try {
            List<Future<String>> futures = executor.invokeAll(tasks);

            for (Future<String> f : futures) {
                try {
                    String resultStr = f.get();
                    nodes.add(extractIp(resultStr));
                    successCount++;
                } catch (Exception e) {
                    errorCount++;
                    nodes.add("Error");
                }
            }
        } catch (Exception e) {
            System.err.println("【连接池测试】执行异常: " + e.getMessage());
            e.printStackTrace();
            result.put("error", true);
            result.put("message", "测试执行异常: " + e.getMessage());
            executor.shutdown();
            return result;
        }

        long end = System.currentTimeMillis();
        executor.shutdown();

        long totalTime = end - start;
        double avgTime = requests > 0 ? (double) totalTime / requests : 0;

        // 返回统计数据
        result.put("totalTime", totalTime);
        result.put("avgTime", Math.round(avgTime * 100.0) / 100.0);
        result.put("requests", requests);
        result.put("successCount", successCount);
        result.put("errorCount", errorCount);
        result.put("nodes", nodes);
        result.put("usePool", usePool);
        result.put("mode", usePool ? "使用连接池" : "不使用连接池");
        result.put("error", false);

        System.out.println("【连接池测试】测试完成 - " + (usePool ? "使用连接池" : "不使用连接池"));
        System.out.println("【连接池测试】总耗时=" + totalTime + "ms, 平均耗时=" + avgTime + "ms");
        System.out.println("【连接池测试】成功=" + successCount + ", 失败=" + errorCount + "\n");

        return result;
    }

    // 获取注册中心所有服务信息（用于可视化）
    @GetMapping("/api/registry/all")
    public Map<String, Object> getAllRegistryServices() {
        try {
            String url = "http://localhost:8888/registry/all";
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                // 使用Jackson解析JSON
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> registryData = mapper.readValue(resp.body(),
                        new TypeReference<Map<String, Object>>() {
                        });

                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("data", registryData);
                return result;
            } else {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("message", "注册中心返回错误: " + resp.statusCode());
                return result;
            }
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "连接注册中心失败: " + e.getMessage());
            return result;
        }
    }
}