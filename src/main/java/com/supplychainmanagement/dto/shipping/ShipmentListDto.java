package com.supplychainmanagement.dto.shipping;

import com.supplychainmanagement.entity.Shipment;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.model.enums.ShipmentStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A shipment as a list row: its data and the ids of its packages, not their contents - those come
 * with GET /shipments/{id}. The distributor is named by id and name, like the customer, and absent
 * (null) until the shipment has been handed to one.
 */
public record ShipmentListDto(
        Long id,
        Long customerId,
        String customerName,
        Long distributorId,
        String distributorName,
        ShipmentStatus status,
        String shippingAddress,
        String shippingMethod,
        LocalDate requestedDeliveryDate,
        LocalDateTime createdAt,
        int packageCount,
        List<Long> shipmentPackageIds
) {

    public static ShipmentListDto from(Shipment shipment) {
        List<Long> packageIds = shipment.getPackages().stream().map(ShipmentPackage::getId).sorted().toList();
        return new ShipmentListDto(
                shipment.getId(),
                shipment.getCustomer() != null ? shipment.getCustomer().getId() : null,
                ShipmentResponse.nameOf(shipment.getCustomer()),
                shipment.getDistributor() != null ? shipment.getDistributor().getId() : null,
                ShipmentResponse.nameOf(shipment.getDistributor()),
                shipment.getStatus(),
                shipment.getShippingAddress(),
                shipment.getShippingMethod(),
                shipment.getRequestedDeliveryDate(),
                shipment.getCreatedAt(),
                packageIds.size(),
                packageIds
        );
    }
}
