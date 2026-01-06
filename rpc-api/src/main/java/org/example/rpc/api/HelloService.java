package org.example.rpc.api;

/**
 * 任务 4：HelloService (菜单/接口)
 * 作用：定义服务契约。
 * 解释：客户端只看这个接口点菜，服务端必须实现这个接口做菜。
 * 这是 RPC 能够“解耦”的关键。
 */
public interface HelloService {

    /**
     * 一个简单的测试方法
     *
     * @param object 随便传个什么东西
     * @return 服务端处理后的字符串
     */
    String hello(Object object);
}