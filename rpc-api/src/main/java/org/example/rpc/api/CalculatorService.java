package org.example.rpc.api;

public interface CalculatorService {
    // 计算圆周率，precision 为迭代次数，次数越多越慢
    String calculatePi(int precision);
}