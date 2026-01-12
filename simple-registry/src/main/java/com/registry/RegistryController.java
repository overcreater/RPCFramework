package com.registry;

import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/registry")
public class RegistryController {

    // 内存注册表：Key=服务名(OrderService), Value=List<IP:Port>
    private static final Map<String, List<String>> SERVICE_MAP = new ConcurrentHashMap<>();

    // 动作：注册
    // URL: POST http://localhost:8888/registry/register?service=...&address=...
    @PostMapping("/register")
    public String register(@RequestParam String service, @RequestParam String address) {
        List<String> list = SERVICE_MAP.computeIfAbsent(service, k -> new ArrayList<>());
        if (!list.contains(address)) {
            list.add(address);
        }
        System.out.println("【注册中心】服务上线: " + service + " @ " + address);
        return "Success";
    }

    // 动作：发现
    // URL: GET http://localhost:8888/registry/discover?service=...
    @GetMapping("/discover")
    public List<String> discover(@RequestParam String service) {
        List<String> list = SERVICE_MAP.get(service);
        System.out.println("【注册中心】服务发现: " + service + " -> " + list);
        return list == null ? new ArrayList<>() : list;
    }

    // 动作：获取所有服务信息（用于可视化）
    // URL: GET http://localhost:8888/registry/all
    @GetMapping("/all")
    public Map<String, Object> getAllServices() {
        Map<String, Object> result = new HashMap<>();
        result.put("services", SERVICE_MAP);
        result.put("totalServices", SERVICE_MAP.size());
        int totalInstances = SERVICE_MAP.values().stream()
                .mapToInt(List::size)
                .sum();
        result.put("totalInstances", totalInstances);
        return result;
    }
}