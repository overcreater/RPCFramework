package org.example.rpc.core.transport;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.example.rpc.common.entity.RpcRequest;
import org.example.rpc.common.entity.RpcResponse;
import org.example.rpc.core.registry.ServiceRegistry;

import java.lang.reflect.Method;

/**
 * 业务处理器
 * 作用：处理解码后的 RpcRequest，执行方法，返回 RpcResponse
 */
public class RpcServerHandler extends SimpleChannelInboundHandler<RpcRequest> {

    private final ServiceRegistry serviceRegistry;

    public RpcServerHandler(ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RpcRequest msg) {
        try {
            // 1. 打印日志：收到什么请求了？
            System.out.println("服务端收到调用: " + msg.getInterfaceName() + "." + msg.getMethodName());

            // 2. 从注册表中查找服务实例
            Object service = serviceRegistry.getService(msg.getInterfaceName());

            // 3. 利用 Java 反射机制调用方法
            Method method = service.getClass().getMethod(msg.getMethodName(), msg.getParamTypes());
            Object result = method.invoke(service, msg.getParameters());

            // 4. 封装成功结果
            RpcResponse<Object> response = RpcResponse.success(result);

            // 5. 写回客户端 (writeAndFlush 是异步的，加个监听器处理错误)
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);

        } catch (Exception e) {
            e.printStackTrace();
            // 发生异常，返回失败结果
            // RpcResponse response = RpcResponse.fail(ResponseCode.FAIL);
            // ctx.writeAndFlush(response);
        }
    }
}