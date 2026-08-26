package com.supplychainmanagement.entity;

import com.supplychainmanagement.model.enums.FullfillmentStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "order_items")
public class OrderItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "quantity", nullable = false)
    @Max(value = 10, message = "Your amount is above our limit")
    @Min(value = 1, message = "Your amount must be at least 1")
    private Integer quantity;

    @Enumerated(EnumType.STRING)
    private FullfillmentStatus fullfillmentStatus = FullfillmentStatus.WAITING;
}