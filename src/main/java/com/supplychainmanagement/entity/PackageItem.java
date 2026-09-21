package com.supplychainmanagement.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/*
 * One item per order line and packing run in a package. The run is part of the key so that a line
 * packed in two runs - two loose items, 5 + 5 - can still go into one package: its quantity there is
 * the sum of its items, and each keeps the runNo it was packed under. Loose items (no package) are
 * not constrained at all, NULLs are distinct in a unique index.
 */
@Table(
        name = "package_item",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_package_item_order_item_run",
                        columnNames = {
                                "shipment_package_id",
                                "order_item_id",
                                "run_no"
                        }
                )
        }
)
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Entity
public class PackageItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID runNo;

    // Optional: createPackageItems packs lines without a package, and optional = false would make
    // Hibernate reject the null before the nullable column ever comes into play.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_package_id")
    private ShipmentPackage shipmentPackage;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_item_id", nullable = false)
    private OrderItem orderItem;

    // Last line of defence: bean validation checks it again before the row is written.
    @Min(value = 1, message = "quantity must be at least 1")
    @Column(nullable = false)
    private Integer quantity;

    private LocalDateTime created;

    @PrePersist
    void applyDefaultValues() {
        this.created = LocalDateTime.now();
    }
}