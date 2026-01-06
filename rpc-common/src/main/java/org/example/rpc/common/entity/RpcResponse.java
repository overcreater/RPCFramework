package org.example.rpc.common.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.rpc.common.enumeration.ResponseCode;

import java.io.Serializable;

/**
 * 任务 2：RpcResponse
 * 作用：服务端处理完后，把结果装在这里面发回来。
 * <T>：这是一个泛型类，因为返回值可能是 String，也可能是 User 对象，所以用 T 代表任意类型。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RpcResponse<T> implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 状态码 (200 成功，500 失败)
     */
    private Integer code;

    /**
     * 提示信息 (e.g., "调用成功")
     */
    private String message;

    /**
     * 实际数据 (服务端的返回值)
     */
    private T data;

    // --- 下面是两个辅助方法，为了以后写代码方便 ---

    /**
     * 快速生成一个“成功”的响应包
     */
    public static <T> RpcResponse<T> success(T data) {
        RpcResponse<T> response = new RpcResponse<>();
        response.setCode(ResponseCode.SUCCESS.getCode());
        response.setMessage(ResponseCode.SUCCESS.getMessage());
        response.setData(data);
        return response;
    }

    /**
     * 快速生成一个“失败”的响应包
     */
    public static <T> RpcResponse<T> fail(ResponseCode responseCode) {
        RpcResponse<T> response = new RpcResponse<>();
        response.setCode(responseCode.getCode());
        response.setMessage(responseCode.getMessage());
        return response;
    }
}