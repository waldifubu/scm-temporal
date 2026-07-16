package com.supplychainmanagement.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "stock",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_stock_storehouse_sku",
                        columnNames = {
                                "storehouse_id",
                                "sku"
                        }
                )
        }
)
public class Stock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long id;

    // private ShippableItem shippableItem;
    private UUID sku;

    @Column(nullable = false)
    private int onHand;

    @Column(nullable = false)
    private int reserved;

    @Version
    @ColumnDefault("0")
    private long version; // <-- Optimistic Locking

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "storehouse_id",
            nullable = false
    )
    private Storehouse storehouse;

    public int getAvailable() {
        return onHand - reserved;
    }

    public void reserve(int qty) {
        if (qty <= 0) throw new IllegalArgumentException("qty must be > 0");
        if (getAvailable() < qty) {
            throw new IllegalStateException("Insufficient stock for " + sku);
        }
        this.reserved += qty;
        this.updatedAt = LocalDateTime.now();
    }

    public void release(int qty) {
        if (qty <= 0) throw new IllegalArgumentException("qty must be > 0");
        if (this.reserved < qty) {
            throw new IllegalStateException("Release exceeds reserved for " + sku);
        }
        this.reserved -= qty;
        this.updatedAt = LocalDateTime.now();
    }

    public void consume(int quantity) {
        if (reserved < quantity) {
            throw new IllegalStateException(
                    "Not enough reserved stock");
        }

        reserved -= quantity;
        onHand -= quantity;
    }
}
