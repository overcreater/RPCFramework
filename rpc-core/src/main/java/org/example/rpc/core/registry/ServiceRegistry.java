package org.example.rpc.core.registry;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务注册表
 * 作用：保存 接口名 -> 服务实现类对象 的映射关系
 */
public class ServiceRegistry {

    // 使用 ConcurrentHashMap 保证线程安全
    private final Map<String, Object> serviceMap = new ConcurrentHashMap<>();
    private final Set<String> registeredService = ConcurrentHashMap.newKeySet();

    /**
     * 注册服务
     *
     * @param service 服务实现类的对象 (比如 HelloServiceImpl 的实例)
     */
    public <T> void register(T service) {
        // 获取该对象实现的接口名称 (e.g. "org.example.rpc.api.HelloService")
        // 这里的逻辑主要针对只有一个接口的情况，简化处理
        String serviceName = service.getClass().getInterfaces()[0].getCanonicalName();
        if (registeredService.contains(serviceName)) {
            return;
        }
        registeredService.add(serviceName);
        serviceMap.put(serviceName, service);
        System.out.println("服务已注册: " + serviceName);
    }

    /**
     * 获取服务对象
     */
    public Object getService(String serviceName) {
        Object service = serviceMap.get(serviceName);
        if (service == null) {
            throw new RuntimeException("未找到服务: " + serviceName);
        }
        return service;
    }
}