package org.example.demo.provider;

import org.example.rpc.api.OrderService;
import org.example.rpc.core.transport.RpcServer;

public class ProviderApp {
    public static void main(String[] args) {
        // 1. 定义监听端口
        int port = 9999;

        // 2. 创建 RpcServer 实例
        // ⚠️注意：第一个参数建议填 "0.0.0.0"，这样局域网内的室友才能连上你
        RpcServer server = new RpcServer("0.0.0.0", port);

        // 3. 发布服务 (将接口实现注册到 Server 内部的注册表中)
        server.publishService(new OrderServiceImpl());

        // 4. 启动服务端
        System.out.println("====== 服务端启动成功 (Port:" + port + ") ======");
        server.start();
    }
}