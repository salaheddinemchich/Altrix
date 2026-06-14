package com.example.altrix.order;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    private String id;
    private String customerEmail;
    private String product;
    private int quantity;
    private BigDecimal total;
    private OrderStatus status;
    private Instant createdAt;
    private Instant updatedAt;

    public static Order create(String customerEmail, String product, int quantity, BigDecimal total) {
        Instant now = Instant.now();
        return Order.builder()
                .id(UUID.randomUUID().toString())
                .customerEmail(customerEmail)
                .product(product)
                .quantity(quantity)
                .total(total)
                .status(OrderStatus.CREATED)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
