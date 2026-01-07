package org.example.demo.consumer;

import org.example.rpc.api.OrderService;
import org.example.rpc.core.proxy.RpcClientProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RpcConfig {

    @Bean
    public OrderService orderService() {
        // 🔥 这里填服务端的 IP 和端口 🔥
        // 如果是本地测试填 "127.0.0.1"
        // 如果是室友电脑，填他的局域网IP，例如 "192.168.31.50"
        String remoteHost = "127.0.0.1";
        int remotePort = 9999;

        // 1. 创建代理工厂 (传入目标 IP 和端口)
        RpcClientProxy proxy = new RpcClientProxy(remoteHost, remotePort);

        // 2. 获取接口的代理对象
        return proxy.getProxy(OrderService.class);
    }
}