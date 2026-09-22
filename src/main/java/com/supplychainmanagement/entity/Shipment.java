package com.supplychainmanagement.entity;

import com.supplychainmanagement.entity.users.Customer;
import com.supplychainmanagement.entity.users.Distributor;
import com.supplychainmanagement.model.enums.ShipmentStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "shipment")
@Getter
@Setter
@NoArgsConstructor
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "distributor_id")
    private Distributor distributor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShipmentStatus status;

    @Column(name = "requested_delivery_date")
    private LocalDate requestedDeliveryDate;

    @Column(name = "tracking_number")
    private String trackingNumber;

    @Column(name = "shipping_method")
    private String shippingMethod;

    @Column(name = "shipping_address")
    private String shippingAddress;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "shipped_at")
    private LocalDateTime shippedAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    /**
     * The packages travelling with this shipment. The same arrangement as ShipmentPackage.items: the
     * package owns the foreign key and outlives the shipment - taken out, it is free again, never
     * deleted - so no cascade and no orphanRemoval. No setter: the contents change through
     * {@link #addPackage} and {@link #removePackage}, which keep both sides of the relation.
     */
    @Setter(AccessLevel.NONE)
    @OneToMany(mappedBy = "shipment")
    private List<ShipmentPackage> packages = new ArrayList<>();

    /** Puts the package into this shipment - both sides, the package owns the foreign key. */
    public void addPackage(ShipmentPackage shipmentPackage) {
        shipmentPackage.setShipment(this);
        packages.add(shipmentPackage);
    }

    /** Takes the package out; it is free again for another shipment. */
    public void removePackage(ShipmentPackage shipmentPackage) {
        shipmentPackage.setShipment(null);
        packages.remove(shipmentPackage);
    }

    @PrePersist
    void onCreate() {
        if (status == null) {
            status = ShipmentStatus.CREATED;
        }
    }
}
