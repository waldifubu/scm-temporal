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
    private PackageType packageType;

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

    /**
     * A product without a weight contributes nothing rather than failing the whole sum.
     */
    private static BigDecimal weightOf(Product product) {
        return product == null || product.getWeight() == null ? BigDecimal.ZERO : product.getWeight();
    }

    public void complete() {

        if (status != PackageStatus.OPEN) {
            throw new IllegalStateException(
                    "Package is not open"
            );
        }

        packedAt = LocalDateTime.now();
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

    /**
     * The weight of what is inside, summed over the packed units. Deliberately NOT called
     * {@code getWeight}: that name belongs to the {@code weight} field, and a hand-written getter
     * of that name suppresses Lombok's - which made the declared weight write-only, stored in the
     * column and unreadable through the accessor.
     * <p>
     * So the two are different numbers and both are wanted: {@code weight} is what was declared or
     * measured for the carrier, gross and including the packaging itself; this one is the net
     * content and can serve as a plausibility check against it.
     */
    public BigDecimal getContentWeight() {
        return items.stream()
                .map(
                        item -> weightOf(item.getOrderItem().getProduct()).multiply(BigDecimal.valueOf(item.getQuantity()))
                )
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * What the carrier is billed for: content plus the packaging itself.
     * <p>
     * A weight that was declared or actually measured wins over the calculation - it is the more
     * accurate number and the one the label was printed from. Only without it is the gross weight
     * estimated, and then the type has to be known: an unset {@code type} contributes no tare rather
     * than failing, which would otherwise turn every package created without one into an NPE.
     */
    public BigDecimal getPackageWeight() {
        BigDecimal tare = packageType != null ? packageType.getTareWeight() : BigDecimal.ZERO;
        return getContentWeight().add(tare);
    }

    @PrePersist
    void applyDefault() {
        if (status == null) {
            status = PackageStatus.OPEN;
        }

        createdAt = LocalDateTime.now();
        if (weight == null) {
            weight = getContentWeight();
        }
    }
}