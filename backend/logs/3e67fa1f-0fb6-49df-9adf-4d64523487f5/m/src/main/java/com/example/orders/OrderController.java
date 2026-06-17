package com.example.orders;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tiny REST entry point to publish an order — lets the messaging path be
 * exercised end to end.
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderPublisher publisher;

    public OrderController(OrderPublisher publisher) {
        this.publisher = publisher;
    }

    @PostMapping
    public String create(@RequestBody Order order) {
        publisher.publish(order);
        return "published " + order.id();
    }
}
