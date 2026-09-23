package com.supplychainmanagement.dto.shipping;

import com.fasterxml.jackson.annotation.JsonInclude;
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
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long customerId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String customerName,
        ShipmentStatus status,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String shippingAddress,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String shippingMethod,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String comment,
        String trackingNumber,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        LocalDate requestedDeliveryDate,
        LocalDateTime createdAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        LocalDateTime shippedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        LocalDateTime deliveredAt,
        int packageCount,
        BigDecimal weight,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long distributorId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String distributorName,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String message,
        List<ShipmentPackageListDto> packages
) {

    /**
     * @param packages the shipment's packages with their contents loaded - taken as an argument so
     *                 the caller can fetch them for all packages in one query
     */
    public static ShipmentResponse from(Shipment shipment, List<ShipmentPackage> packages, String message) {
        return new ShipmentResponse(
                shipment.getId(),
                shipment.getCustomer() != null ? shipment.getCustomer().getId() : null,
                nameOf(shipment.getCustomer()),
                shipment.getStatus(),
                shipment.getShippingAddress(),
                shipment.getShippingMethod(),
                shipment.getComment(),
                shipment.getTrackingNumber(),
                shipment.getRequestedDeliveryDate(),
                shipment.getCreatedAt(),
                shipment.getShippedAt(),
                shipment.getDeliveredAt(),
                packages.size(),
                packages.stream().map(ShipmentPackage::getPackageWeight).reduce(BigDecimal.ZERO, BigDecimal::add),
                shipment.getDistributor() != null ? shipment.getDistributor().getId() : null,
                nameOf(shipment.getDistributor()),
                message,
                packages.stream().map(ShipmentPackageListDto::from).toList()
        );
    }

    /**
     * First and last name of a customer or distributor; null without one.
     */
    static String nameOf(User user) {
        if (user == null) {
            return null;
        }
        return (user.getFirstName() + " " + user.getLastName()).trim();
    }
}
