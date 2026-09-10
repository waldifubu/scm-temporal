package com.supplychainmanagement.entity;

import com.supplychainmanagement.model.enums.ReservationStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Getter
@Setter
@RequiredArgsConstructor
/*
 * One line, at most one reservation - and it is the table that says so, not just the mapping.
 * <p>
 * It replaces a constraint over (order_id, sku, storehouse_id), which read "one reservation per
 * order, article and storehouse". That was the weaker rule: it would have allowed the same line to
 * be held in two storehouses, while a line is covered by exactly one storehouse or not at all
 * (see ProductionServiceImpl.findEligibleStock). Deliberately no splitting across storehouses.
 * <p>
 * Named explicitly rather than left to `unique = true` on the @JoinColumn: that produces a
 * generated name, which is awkward to reference in a migration and invisible when reading the
 * table. The name matches the one the migration in issues.txt creates.
 */
@Table(
        name = "reservation",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reservation_order_item",
                columnNames = "order_item_id"
        )
)
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The order line this reservation was made for. A line holds at most one reservation - a released
     * one is deleted, a consumed one blocks a new one - so the relation is 1:1, enforced by
     * uk_reservation_order_item above.
     * <p>
     * Deliberately unidirectional: OrderItem does not point back. A back reference would drag
     * Order -> orderItems -> reservation -> orderItem into every response that serializes a
     * Reservation, which PickingController does directly.
     * <p>
     * LAZY is honoured here because Reservation owns the foreign key. On the inverse side of a
     * OneToOne Hibernate cannot do lazy without bytecode enhancement.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_item_id")
    private OrderItem orderItem;

    @Column(nullable = false, length = 64)
    private String orderId;

    @Column(nullable = false, length = 64)
    private UUID sku;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReservationStatus status;

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime expiresAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "storehouse_id",
            nullable = false
    )
    private Storehouse storehouse;

    private Reservation(OrderItem orderItem, String orderId, UUID sku, int quantity,
                        Storehouse storehouse, ReservationStatus reservationStatus) {
        this.orderItem = orderItem;
        this.orderId = orderId;
        this.sku = sku;
        this.quantity = quantity;
        this.storehouse = storehouse;
        this.status = reservationStatus;
        this.expiresAt = LocalDateTime.now().plusHours(24); // default expiration time of 24 hours
    }

    /**
     * {@code orderId}, {@code sku} and {@code qty} are passed in rather than read off the order
     * item on purpose: both {@code OrderItem.order} and {@code OrderItem.product} are LAZY, and the
     * reserve path holds nothing but a proxy here. Deriving them would cost two extra selects per
     * line.
     */
    public static Reservation active(OrderItem orderItem, String orderId, UUID sku, int quantity,
                                     Storehouse storehouse) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be greater than zero");
        }

        if (storehouse == null) {
            throw new IllegalArgumentException("Storehouse must not be null");
        }

        if (orderItem == null) {
            throw new IllegalArgumentException("Order item must not be null");
        }

        return new Reservation(orderItem, orderId, sku, quantity, storehouse, ReservationStatus.ACTIVE);
    }

    public void release() {
        if (status != ReservationStatus.ACTIVE) {
            throw new IllegalStateException("Only ACTIVE reservation can be released");
        }

        status = ReservationStatus.RELEASED;
    }

    public void consume() {
        if (status != ReservationStatus.ACTIVE) {
            throw new IllegalStateException("Only ACTIVE reservation can be consumed");
        }

        status = ReservationStatus.CONSUMED;
    }
}
