package com.supplychainmanagement.dto.shipping;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * A new shipment: the customer it goes to, at least one package for that customer, and where it is
 * delivered. A shipment is never created empty - every package has to belong to an order of the
 * given customer, which ShipmentServiceImpl checks against the packages' contents.
 */
public record CreateShipmentRequest(
        @NotEmpty(message = "At least one shipment package is required")
        List<@NotNull(message = "a shipment package id must not be null") Long> shipmentPackageIds,

        String shippingAddress,
        String shippingMethod,
        LocalDate requestedDeliveryDate
) {
}
