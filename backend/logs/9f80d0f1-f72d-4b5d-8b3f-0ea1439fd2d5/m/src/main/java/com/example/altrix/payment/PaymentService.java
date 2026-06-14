package com.example.altrix.payment;

import com.example.altrix.common.PubsubConfig;
import com.example.altrix.order.OrderService;
import com.example.altrix.pubsub.PubsubService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@ApplicationScoped
public class PaymentService {

    @Inject
    PaymentRepository repository;

    @Inject
    OrderService orderService;

    @Inject
    PubsubService pubsubService;

    // Kafka Producer for Payments Completed Topic
    @Inject
    KafkaProducer<String, String> kafkaProducer;

    public Payment recordPayment(String orderId, BigDecimal amount, PaymentMethod method) {
        Payment payment = Payment.create(orderId, amount, method);
        repository.save(payment);
        // Pretend the gateway always succeeds in this sample
        payment.setStatus(PaymentStatus.COMPLETED);
        repository.save(payment);
        orderService.markPaid(orderId);
        publishPaymentCompleted(payment);
        return payment;
    }

    public Optional<Payment> findById(String id) {
        return repository.findById(id);
    }

    public List<Payment> findByOrderId(String orderId) {
        return repository.findByOrderId(orderId);
    }

    public Collection<Payment> findAll() {
        return repository.findAll();
    }

    private void publishPaymentCompleted(Payment payment) {
        Map<String, String> attributes = new HashMap<>();
        attributes.put("paymentId", payment.getId());
        attributes.put("orderId", payment.getOrderId());
        attributes.put("amount", payment.getAmount().toPlainString());
        attributes.put("method", payment.getMethod().name());
        attributes.put("eventType", "payment.completed");

        String payload = "{\"paymentId\":\"" + payment.getId() + "\",\"orderId\":\"" + payment.getOrderId() + "\",\"amount\":" + payment.getAmount() + ",\"status\":\"" + payment.getStatus() + "\"}";

        // Publish to Kafka Topic
        ProducerRecord<String, String> record = new ProducerRecord<>(
                PubsubConfig.PAYMENTS_COMPLETED,
                payment.getOrderId(), // Key
                payload
        );

        // Set attributes as headers (simplified, as Kafka doesn't directly map to PubSub attributes)
        record.headers().add("paymentId", payment.getId().getBytes(StandardCharsets.UTF_8));
        record.headers().add("orderId", payment.getOrderId().getBytes(StandardCharsets.UTF_8));
        record.headers().add("amount", payment.getAmount().toPlainString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("method", payment.getMethod().name().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", "payment.completed".getBytes(StandardCharsets.UTF_8));

        // Send with acknowledgment
        try {
            kafkaProducer.send(record).get(); // Wait for acknowledgment
            log.info("Published payment.completed event for paymentId={} orderId={}", payment.getId(), payment.getOrderId());
        } catch (Exception e) {
            log.error("Error publishing to Kafka", e);
        }
    }
}