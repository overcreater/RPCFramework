package org.example.rpc.core.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ReplayingDecoder;
import org.example.rpc.common.entity.RpcRequest;
import org.example.rpc.common.entity.RpcResponse;
import org.example.rpc.core.serializer.JsonSerializer;
import org.example.rpc.core.serializer.Serializer;

import java.util.List;

/**
 * 解码器的作用：把字节流 -> RpcRequest / RpcResponse 对象
 */
public class CommonDecoder extends ReplayingDecoder<Void> {

    private static final int MAGIC_NUMBER = 0xCAFEBABE;

    /**
     * 继承Netty框架的通信组件，实现编码函数decode
     */

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        // 1. 校验魔数
        int magic = in.readInt();
        if (magic != MAGIC_NUMBER) {
            System.out.println("不识别的协议包，断开连接！");
            ctx.close();
            return;
        }

        // 2. 读取版本号 (只需要读取)
        in.readByte();

        // 3. 读取序列化方式 (暂时没用，默认 JSON)
        in.readByte();

        // 4. 读取消息类型 (0:请求, 1:响应)
        byte packageType = in.readByte();

        // 5. 读取数据长度
        int length = in.readInt();

        // 6. 读取数据本体
        byte[] bytes = new byte[length];
        in.readBytes(bytes);

        // 7. 反序列化
        Serializer serializer = new JsonSerializer(); // 这里可以优化为单例
        Object obj = null;
        if (packageType == 0) {
            // 如果是请求包，转成 RpcRequest
            obj = serializer.deserialize(bytes, RpcRequest.class);
        } else if (packageType == 1) {
            // 如果是响应包，转成 RpcResponse
            obj = serializer.deserialize(bytes, RpcResponse.class);
        }

        if (obj != null) {
            out.add(obj);
        }
    }
}