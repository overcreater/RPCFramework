package com.registry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.util.Collections;

@SpringBootApplication
public class SimpleRegistryApp {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(SimpleRegistryApp.class);
        // 手动设置端口，不想写 application.yml 了
        app.setDefaultProperties(Collections.singletonMap("server.port", "8888"));
        app.run(args);
    }
}