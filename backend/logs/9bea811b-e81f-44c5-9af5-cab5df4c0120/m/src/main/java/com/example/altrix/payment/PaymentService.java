package com.example.altrix.payment;

import com.example.altrix.common.PubsubConfig;
import com.example.altrix.order.OrderService;
import com.example.altrix.pubsub.PubsubService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

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

        ProducerRecord<String, String> record = new ProducerRecord<>(PubsubConfig.PAYMENTS_COMPLETED, payment.getOrderId(), payload);
        kafkaProducer.send(record).get(); // Blocking send for simplicity, consider async in production

        log.info("Published payment.completed event for paymentId={} orderId={}", payment.getId(), payment.getOrderId());
    }
}