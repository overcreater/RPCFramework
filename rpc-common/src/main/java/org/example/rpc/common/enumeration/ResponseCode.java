package org.example.rpc.common.enumeration;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 任务 3：ResponseCode
 * 作用：统一“成功”和“失败”的定义。
 * 解释：避免在代码里写死 200 或 500 这样的数字，防止硬编码带来的程序修改困难。
 */
@AllArgsConstructor
@Getter
public enum ResponseCode {

    SUCCESS(200, "调用方法成功"),
    FAIL(500, "调用方法失败"),
    METHOD_NOT_FOUND(404, "未找到指定方法"),
    CLASS_NOT_FOUND(404, "未找到指定类");

    private final int code;
    private final String message;
}