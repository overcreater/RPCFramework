package org.example.rpc.core.codec;

/**
 * 实现编码器，封包操作
 * 运用自定义的协议字段
 */

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.example.rpc.common.entity.RpcRequest;
import org.example.rpc.core.serializer.Serializer;


public class CommonEncoder extends MessageToByteEncoder {

    private static final int MAGIC_NUMBER = 0xCAFEBABE;
    private final Serializer serializer;

    public CommonEncoder(Serializer serializer) {
        this.serializer = serializer;
    }

    /**
     * 继承Netty框架的通信组件，实现编码函数encode
     */

    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) throws Exception {
        // 1. 写入魔数 (4 Bytes)
        out.writeInt(MAGIC_NUMBER);

        // 2. 写入版本号 (1 Byte) - 假设为 1
        out.writeByte(1);

        // 3. 写入序列化方式 (1 Byte) - 假设 1 代表 JSON
        out.writeByte(1);

        // 4. 写入消息类型 (1 Byte)
        // 如果是 RpcRequest 请求，标为 0；如果是 RpcResponse 响应，标为 1
        if (msg instanceof RpcRequest) {
            out.writeByte(0);
        } else {
            out.writeByte(1);
        }

        // 5. 获取数据内容 (Body)
        byte[] bytes = serializer.serialize(msg);

        // 6. 写入数据长度 (4 Bytes) - 解决粘包的关键，发送数据之前，告诉发送方，我要发送多少字节的数据
        out.writeInt(bytes.length);

        // 7. 写入数据本体
        out.writeBytes(bytes);
    }
}