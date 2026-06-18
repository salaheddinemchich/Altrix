package com.example.orders;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Single deployable hosting three bounded contexts (orders, payments,
 * notifications) — {@code @ComponentScan} widens past the default
 * same-package-and-below scan since payments/notifications are siblings of
 * orders, not sub-packages of it.
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.example")
public class OrdersApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrdersApplication.class, args);
    }
}
