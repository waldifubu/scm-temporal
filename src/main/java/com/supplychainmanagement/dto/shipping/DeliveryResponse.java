package com.supplychainmanagement.dto.shipping;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.supplychainmanagement.entity.Shipment;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.model.enums.ShipmentStatus;
import com.supplychainmanagement.model.enums.ShipmentPackageType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * What the carrier gets back from its own steps - accept, in transit, delivered.
 * <p>
 * Deliberately not {@link ShipmentResponse}: that one carries every package with its items, and with
 * them the SKUs, quantities and order numbers of the customer's order. A distributor needs to know
 * where the shipment goes and how many packages weighing how much, not what is inside them.
 */
public record DeliveryResponse(
        Long shipmentId,
        ShipmentStatus status,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String customerName,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String shippingAddress,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String shippingMethod,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String trackingNumber,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        LocalDate requestedDeliveryDate,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        LocalDateTime shippedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        LocalDateTime deliveredAt,
        int packageCount,
        BigDecimal weight,
        List<String> packageNumbers,
        /*
         * What the carrier needs to read the shipment's page: whose it is, when it was made, and the
         * planner's note - which is also where the reason of a cancellation is kept. The customer's id
         * is that and nothing more; the contents stay out (see above).
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long customerId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        LocalDateTime createdAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String comment,
        /* Why this carrier turned the shipment down - only there once it did. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String distributorComment,
        /* When anything on the shipment last changed - a step of the carrier, the planner's edit. Left out while null: rows from before the column existed have none. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        LocalDateTime updatedAt
) {

    public static DeliveryResponse from(Shipment shipment) {
        List<ShipmentPackage> packages = shipment.getPackages();

        return new DeliveryResponse(
                shipment.getId(),
                shipment.getStatus(),
                ShipmentResponse.nameOf(shipment.getCustomer()),
                shipment.getShippingAddress(),
                shipment.getShippingMethod(),
                shipment.getTrackingNumber(),
                shipment.getRequestedDeliveryDate(),
                shipment.getShippedAt(),
                shipment.getDeliveredAt(),
                packages.size(),
                packages.stream().map(DeliveryResponse::grossWeightOf).reduce(BigDecimal.ZERO, BigDecimal::add),
                packages.stream().map(ShipmentPackage::getPackageNumber).filter(Objects::nonNull).sorted().toList(),
                shipment.getCustomer() != null ? shipment.getCustomer().getId() : null,
                shipment.getCreatedAt(),
                shipment.getComment(),
                shipment.getDistributorComment(),
                shipment.getUpdatedAt());
    }

    /**
     * Content plus packaging, read off the package's own weight rather than through
     * {@code getPackageWeight()}: that one sums the items, and the carrier's answer would then load
     * the contents it is deliberately not shown.
     */
    private static BigDecimal grossWeightOf(ShipmentPackage shipmentPackage) {
        BigDecimal content = shipmentPackage.getWeight() != null ? shipmentPackage.getWeight() : BigDecimal.ZERO;
        ShipmentPackageType type = shipmentPackage.getShipmentPackageType();
        return type != null ? content.add(type.getTareWeight()) : content;
    }
}
