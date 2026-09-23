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
        List<String> packageNumbers
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
                packages.stream().map(ShipmentPackage::getPackageNumber).filter(Objects::nonNull).sorted().toList());
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
