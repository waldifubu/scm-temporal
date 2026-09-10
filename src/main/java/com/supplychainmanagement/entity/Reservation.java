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
@Table(name = "reservation", uniqueConstraints = @UniqueConstraint(columnNames = {"order_id", "sku", "storehouse_id"}))
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The order line this reservation was made for. A line holds at most one reservation at a time -
     * a released one is deleted, a consumed one blocks a new one - so the relation is 1:1, enforced
     * by the unique constraint on the FK column.
     * <p>
     * Deliberately unidirectional: OrderItem does not point back. A back reference would drag
     * Order -> orderItems -> reservation -> orderItem into every response that serializes a
     * Reservation, which PickingController does directly.
     * <p>
     * LAZY is honoured here because Reservation owns the foreign key. On the inverse side of a
     * OneToOne Hibernate cannot do lazy without bytecode enhancement.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_item_id", unique = true)
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
