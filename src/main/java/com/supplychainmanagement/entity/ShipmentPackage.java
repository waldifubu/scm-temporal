package com.supplychainmanagement.entity;

import com.supplychainmanagement.model.enums.PackageStatus;
import com.supplychainmanagement.model.enums.PackageType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "shipment_package",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_shipment_package_number",
                        columnNames = {
                                "shipment_id",
                                "package_number"
                        }
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
public class ShipmentPackage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_id")
    private Shipment shipment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PackageStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = true)
    private PackageType type;

    private String packageNumber;
    private BigDecimal weight;
    private BigDecimal length;
    private BigDecimal width;
    private BigDecimal height;

    private LocalDateTime packedAt;

    @OneToMany(
            mappedBy = "shipmentPackage",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    private List<PackageItem> items = new ArrayList<>();


    public void complete() {

        if (status != PackageStatus.OPEN) {
            throw new IllegalStateException(
                    "Package is not open"
            );
        }

        status = PackageStatus.PACKED;
    }

    public BigDecimal getVolume() {

        if (length == null
                || width == null
                || height == null) {

            return BigDecimal.ZERO;
        }

        return length
                .multiply(width)
                .multiply(height);
    }

    public BigDecimal getWeight() {
        return items.stream()
                .map(PackageItem::getOrderItem)
                .map(OrderItem::getProduct)
                .map(Product::getWeight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @PrePersist
    void applyDefault() {
        if (status == null) {
            status = PackageStatus.OPEN;
        }

        createdAt = LocalDateTime.now();
    }
}