package org.example.rpc.api;

public interface OrderService {
    String createOrder(String userId, String productId);
}