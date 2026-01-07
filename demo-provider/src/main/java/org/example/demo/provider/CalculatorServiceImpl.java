package org.example.demo.provider;

import org.example.rpc.api.CalculatorService;
import java.net.InetAddress;

public class CalculatorServiceImpl implements CalculatorService {
    @Override
    public String calculatePi(int precision) {
        // 模拟耗时计算
        double pi = 0;
        for (int i = 0; i < precision; i++) {
            pi += Math.pow(-1, i) / (2 * i + 1);
        }
        pi *= 4;

        String serverIp = "Unknown";
        try { serverIp = InetAddress.getLocalHost().getHostAddress(); } catch (Exception e) {}

        // 返回结果带上 IP，证明是谁算的
        return "【节点 " + serverIp + "】 计算结果: " + pi;
    }
}