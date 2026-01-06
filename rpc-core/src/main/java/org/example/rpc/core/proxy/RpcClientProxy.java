package org.example.rpc.core.proxy;

import org.example.rpc.common.entity.RpcRequest;
import org.example.rpc.common.entity.RpcResponse;
import org.example.rpc.core.transport.RpcClient;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 动态代理
 * 把接口方法调用变成网络请求
 */
public class RpcClientProxy implements InvocationHandler {

    private final String host;
    private final int port;

    public RpcClientProxy(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * 获取代理对象
     */
    @SuppressWarnings("unchecked")
    public <T> T getProxy(Class<T> clazz) {
        // 创建代理对象
        return (T) Proxy.newProxyInstance(
                clazz.getClassLoader(),
                new Class<?>[]{clazz},
                this
        );
    }

    /**
     * 这里的 invoke 方法会在你调用 helloService.hello() 时被自动执行
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // 1. 封装请求对象
        RpcRequest request = RpcRequest.builder()
                .interfaceName(method.getDeclaringClass().getName())
                .methodName(method.getName())
                .parameters(args)
                .paramTypes(method.getParameterTypes())
                .build();

        // 2. 创建 RpcClient 发送网络请求
        RpcClient client = new RpcClient(host, port);

        // 3. 发送并等待结果
        RpcResponse response = client.sendRequest(request);

        // 4. 返回结果给用户
        if (response != null && response.getCode() == 200) {
            return response.getData();
        }
        return null;
    }
}