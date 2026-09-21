package com.supplychainmanagement.dto.shipping;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

/**
 * The shipment's own data, without its packages - those change through the packages endpoints - and
 * without the customer, which the packages are bound to. Replaces every field it carries.
 */
public record UpdateShipmentRequest(
        @NotBlank(message = "shippingAddress is required")
        String shippingAddress,
        String shippingMethod,
        LocalDate requestedDeliveryDate
) {
}
