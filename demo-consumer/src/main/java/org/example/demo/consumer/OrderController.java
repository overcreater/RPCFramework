package org.example.demo.consumer;

import org.example.rpc.api.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {
    @Autowired
    private OrderService orderService; // 注入上面的 Bean

    @GetMapping("/buy")
    public String buy(@RequestParam String id) {
        return orderService.createOrder("WebUser", id);
    }
}