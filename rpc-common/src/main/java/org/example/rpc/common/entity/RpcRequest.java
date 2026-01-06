package org.example.rpc.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 任务 1：RpcRequest
 * 作用：封装客户端的请求信息。
 * 解释：如果不把这些信息发过去，服务端根本不知道你要调哪个类、哪个方法。
 */
@Data // Lombok注解：自动生成 getter, setter, toString
@Builder // Lombok注解：提供建造者模式，方便创建对象
@AllArgsConstructor // Lombok注解：生成全参构造器
@NoArgsConstructor  // Lombok注解：生成无参构造器
public class RpcRequest implements Serializable {
    // 必须实现 Serializable 接口，因为这个对象要在网络管道里传输，必须能被序列化成二进制流
    private static final long serialVersionUID = 1L;

    /**
     * 接口名称 (e.g., "org.example.rpc.api.HelloService")
     * 告诉服务端：我要找哪个服务？
     */
    private String interfaceName;

    /**
     * 方法名称 (e.g., "hello")
     * 告诉服务端：我要调在这个服务里的哪个方法？
     */
    private String methodName;

    /**
     * 参数列表 (e.g., ["你好", 123])
     * 告诉服务端：我传给方法的参数值是什么？
     */
    private Object[] parameters;

    /**
     * 参数类型 (e.g., [String.class, Integer.class])
     * 告诉服务端：防止重载方法搞混，明确参数类型。
     */
    private Class<?>[] paramTypes;
}