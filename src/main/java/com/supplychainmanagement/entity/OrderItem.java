package com.supplychainmanagement.entity;

import com.supplychainmanagement.model.enums.FulfillmentStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

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

    /**
     * Maintained by Hibernate, like {@code Stock.updatedAt} and {@code Product.updatedAt}. It used
     * to be written by hand at each call site, which meant every path that forgot to do so left the
     * timestamp stale - and one of them did.
     */
    @UpdateTimestamp
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Enumerated(EnumType.STRING)
    private FulfillmentStatus fulfillmentStatus = FulfillmentStatus.WAITING;
}