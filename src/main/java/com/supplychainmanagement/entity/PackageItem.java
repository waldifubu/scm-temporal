package com.supplychainmanagement.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Table(
        name = "package_item",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_package_order_item",
                        columnNames = {
                                "shipment_package_id",
                                "order_item_id"
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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_package_id", nullable = false)
    private ShipmentPackage shipmentPackage;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_item_id", nullable = false)
    private OrderItem orderItem;

    @Column(nullable = false)
    private Integer quantity;
}