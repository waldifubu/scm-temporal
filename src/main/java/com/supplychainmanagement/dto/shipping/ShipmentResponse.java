package com.supplychainmanagement.dto.shipping;

import com.supplychainmanagement.entity.Shipment;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.ShipmentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One shipment with its packages and their contents - the answer of the single-shipment endpoints
 * and of every change. {@code weight} is what the carrier carries: the gross weight of all packages,
 * content plus packaging. Customer and distributor appear as id and name only - the User entities
 * would carry the password hash and roles into the response.
 */
public record ShipmentResponse(
        Long id,
        Long customerId,
        String customerName,
        ShipmentStatus status,
        String shippingAddress,
        String shippingMethod,
        String trackingNumber,
        LocalDate requestedDeliveryDate,
        LocalDateTime createdAt,
        LocalDateTime shippedAt,
        LocalDateTime deliveredAt,
        int packageCount,
        BigDecimal weight,
        Long distributorId,
        String distributorName,
        List<ShipmentPackageListDto> packages
) {

    /**
     * @param packages the shipment's packages with their contents loaded - taken as an argument so
     *                 the caller can fetch them for all packages in one query
     */
    public static ShipmentResponse from(Shipment shipment, List<ShipmentPackage> packages) {
        return new ShipmentResponse(
                shipment.getId(),
                shipment.getCustomer() != null ? shipment.getCustomer().getId() : null,
                nameOf(shipment.getCustomer()),
                shipment.getStatus(),
                shipment.getShippingAddress(),
                shipment.getShippingMethod(),
                shipment.getTrackingNumber(),
                shipment.getRequestedDeliveryDate(),
                shipment.getCreatedAt(),
                shipment.getShippedAt(),
                shipment.getDeliveredAt(),
                packages.size(),
                packages.stream().map(ShipmentPackage::getPackageWeight).reduce(BigDecimal.ZERO, BigDecimal::add),
                shipment.getDistributor() != null ? shipment.getDistributor().getId() : null,
                nameOf(shipment.getDistributor()),
                packages.stream().map(ShipmentPackageListDto::from).toList()
        );
    }

    /** First and last name of a customer or distributor; null without one. */
    static String nameOf(User user) {
        if (user == null) {
            return null;
        }
        return (user.getFirstName() + " " + user.getLastName()).trim();
    }
}
