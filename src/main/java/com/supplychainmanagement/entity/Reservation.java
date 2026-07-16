package com.supplychainmanagement.entity;

import com.supplychainmanagement.model.enums.ReservationStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Getter
@Setter
//@AllArgsConstructor
//@RequiredArgsConstructor
@NoArgsConstructor
@Table(name = "reservation", uniqueConstraints = @UniqueConstraint(columnNames = {"order_id", "sku"}))
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String orderId;

    @Column(nullable = false, length = 64)
    private UUID sku;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReservationStatus status;

    private LocalDateTime expiresAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "storehouse_id",
            nullable = false
    )
    private Storehouse storehouse;

    public Reservation(String orderId, @NotNull UUID sku, int quantity, ReservationStatus reservationStatus) {
        this.orderId = orderId;
        this.sku = sku;
        this.quantity = quantity;
        this.status = reservationStatus;
        this.expiresAt = LocalDateTime.now();
    }

    public static Reservation active(String orderId, UUID sku, int quantity) {

        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be greater than zero");
        }

        return new Reservation(orderId, sku, quantity, ReservationStatus.ACTIVE);
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
