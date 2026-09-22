package com.supplychainmanagement.entity;

import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
import com.supplychainmanagement.model.enums.ShipmentPackageType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

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

    @CreationTimestamp
    @Column(nullable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_id")
    private Shipment shipment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShipmentPackageStatus shipmentPackageStatus;

    @Enumerated(EnumType.STRING)
    private ShipmentPackageType shipmentPackageType;

    private String packageNumber;

    /**
     * Always the weight of the contents, 0 for a package without items - never set from outside,
     * hence no setter. The package keeps it current itself: on insert, and whenever its contents
     * change through {@link #addItem} / {@link #removeItem}. Stored rather than only computed so the
     * column can be queried.
     */
    @Setter(AccessLevel.NONE)
    private BigDecimal weight;
    private BigDecimal length;
    private BigDecimal width;
    private BigDecimal height;

    private LocalDateTime packedAt;

    /**
     * The items currently in this package. A PackageItem outlives its package: loose items exist
     * without one (POST /packing), and an item taken out of a package goes back to being loose. So
     * neither orphanRemoval nor a REMOVE cascade here - either would delete the row, and with it the
     * packed quantity, while the order line still reads PACKING or PACKED; the same quantity could
     * then be packed a second time. Taking an item out means setting its shipmentPackage to null.
     * PERSIST and MERGE stay, so a package created together with its items still saves them in one go.
     * <p>
     * No setter: an existing package changes its contents through {@link #addItem} and
     * {@link #removeItem}, which also keep the weight. Adding to {@code getItems()} directly is left
     * to building a new package, whose weight is set on insert.
     */
    @Setter(AccessLevel.NONE)
    @OneToMany(
            mappedBy = "shipmentPackage",
            cascade = {CascadeType.PERSIST, CascadeType.MERGE}
    )
    private List<PackageItem> items = new ArrayList<>();

    /**
     * A product without a weight contributes nothing rather than failing the whole sum.
     */
    private static BigDecimal weightOf(Product product) {
        return product == null || product.getWeight() == null ? BigDecimal.ZERO : product.getWeight();
    }

    /**
     * OPEN to PACKED, stamped with packedAt. Only an OPEN package with at least one item - the
     * exceptions here are a last line of defence: PackingServiceImpl.completePackage checks both first
     * and answers 409 / 400, an IllegalStateException would reach the client as a 500.
     */
    public void complete() {
        if (shipmentPackageStatus != ShipmentPackageStatus.OPEN) {
            throw new IllegalStateException("Package is not open and cannot be completed");
        }
        if (items.isEmpty()) {
            throw new IllegalStateException("Package holds no items. Add at least one item before completing the package");
        }

        packedAt = LocalDateTime.now();
        shipmentPackageStatus = ShipmentPackageStatus.PACKED;
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
     * The weight of what is inside, summed over the packed units - what {@code weight} is set to.
     * Deliberately NOT called {@code getWeight}: that name belongs to the {@code weight} field, and a
     * hand-written getter of that name suppresses Lombok's.
     * <p>
     * Reads the items with their order lines and products, so call it while they can still be
     * loaded - in the service, never from a flush callback on an unloaded package.
     */
    public BigDecimal getContentWeight() {
        return items.stream()
                .map(
                        item -> weightOf(item.getOrderItem().getProduct()).multiply(BigDecimal.valueOf(item.getQuantity()))
                )
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * What the carrier is billed for: content plus the packaging itself. Always computed - there is
     * no declared weight. An unset {@code type} contributes no tare rather than failing, which would
     * otherwise turn every package created without one into an NPE.
     */
    public BigDecimal getPackageWeight() {
        BigDecimal tare = shipmentPackageType != null ? shipmentPackageType.getTareWeight() : BigDecimal.ZERO;
        return getContentWeight().add(tare);
    }

    @PrePersist
    void onCreate() {
        if (shipmentPackageStatus == null) {
            shipmentPackageStatus = ShipmentPackageStatus.OPEN;
        }

        // A new package holds its items in memory, so this loads nothing.
        recalculateWeight();
        if (length == null) {
            length = BigDecimal.ZERO;
        }
        if (width == null) {
            width = BigDecimal.ZERO;
        }
        if (height == null) {
            height = BigDecimal.ZERO;
        }
    }

    /**
     * Puts the item into this package - both sides of the relation, the item owns the foreign key -
     * and updates the weight.
     */
    public void addItem(PackageItem item) {
        item.setShipmentPackage(this);
        items.add(item);
        recalculateWeight();
    }

    /**
     * Takes the item out of this package and updates the weight. The item goes back to being loose;
     * it is never deleted - see {@link #items}.
     */
    public void removeItem(PackageItem item) {
        item.setShipmentPackage(null);
        items.remove(item);
        recalculateWeight();
    }

    /**
     * Sets {@code weight} to the weight of the current contents; 0 without items.
     * <p>
     * Called when the contents change and on insert, never from a flush callback such as
     * {@code @PreUpdate}: that would not run at all for a change of contents - only package_item rows
     * change, the package is not dirty - and where it did run, it would load the items in the middle
     * of a flush.
     */
    public void recalculateWeight() {
        weight = getContentWeight();
    }
}