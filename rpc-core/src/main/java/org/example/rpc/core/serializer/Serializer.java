package org.example.rpc.core.serializer;

/**
 * 序列化接口（暂时使用json格式，后续方斌拓展成protobuf）
 * 目的：不用改动核心代码
 */
public interface Serializer {

    /**
     * 序列化：Java对象 -> 字节数组
     */
    byte[] serialize(Object obj);

    /**
     * 反序列化：字节数组 -> Java对象
     */
    <T> T deserialize(byte[] bytes, Class<T> clazz);
}