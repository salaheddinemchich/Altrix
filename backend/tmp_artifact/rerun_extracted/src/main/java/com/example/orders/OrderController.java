package com.example.orders;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST entry points to publish an order and check its status — lets the
 * messaging path (including the payments round-trip) be exercised end to
 * end.
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderPublisher publisher;
    private final OrderStore orderStore;

    public OrderController(OrderPublisher publisher, OrderStore orderStore) {
        this.publisher = publisher;
        this.orderStore = orderStore;
    }

    @PostMapping
    public String create(@RequestBody Order order) {
        orderStore.markCreated(order.id());
        publisher.publish(order);
        return "published " + order.id();
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<String> status(@PathVariable String id) {
        OrderStore.Status status = orderStore.statusOf(id);
        if (status == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(status.name());
    }
}
