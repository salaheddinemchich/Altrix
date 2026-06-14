package com.example.altrix.order;

import com.example.altrix.common.PubsubConfig;
import com.example.altrix.pubsub.PubsubService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@ApplicationScoped
public class OrderService {

    @Inject
    OrderRepository repository;

    @Inject
    PubsubService pubsubService;

    public Order createOrder(String customerEmail, String product, int quantity, BigDecimal total) {
        Order order = Order.create(customerEmail, product, quantity, total);
        repository.save(order);
        publishOrderCreated(order);
        return order;
    }

    public Optional<Order> findById(String id) {
        return repository.findById(id);
    }

    public Collection<Order> findAll() {
        return repository.findAll();
    }

    public boolean cancel(String id) {
        return repository.findById(id).map(o -> {
            o.setStatus(OrderStatus.CANCELLED);
            o.setUpdatedAt(java.time.Instant.now());
            repository.save(o);
            return true;
        }).orElse(false);
    }

    public void markPaid(String orderId) {
        repository.findById(orderId).ifPresent(o -> {
            o.setStatus(OrderStatus.PAID);
            o.setUpdatedAt(java.time.Instant.now());
            repository.save(o);
            log.info("Order {} marked as PAID", orderId);
        });
    }

    private void publishOrderCreated(Order order) {
        Map<String, String> attributes = new HashMap<>();
        attributes.put("orderId", order.getId());
        attributes.put("customerEmail", order.getCustomerEmail());
        attributes.put("total", order.getTotal().toPlainString());
        attributes.put("eventType", "order.created");

        String payload = "{\"orderId\":\"" + order.getId() + "\",\"product\":\"" + order.getProduct() + "\",\"quantity\":" + order.getQuantity() + ",\"total\":" + order.getTotal() + "}";
        ProducerRecord<String, String> record = new ProducerRecord<>(PubsubConfig.ORDERS_CREATED, order.getId(), payload);
        pubsubService.getKafkaProducer().send(record).get(); // Blocking send for simplicity; consider async in production
        log.info("Published order.created event for orderId={}", order.getId());
    }
}