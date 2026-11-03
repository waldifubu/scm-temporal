package com.supplychainmanagement.dto.user;

import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

/**
 * What a carrier is looked up for: {@code POST /users/distributors/query}. Every field is optional - one
 * that is left out matches only the rules that do not set that dimension.
 *
 * @param customerId the customer the shipment is for
 * @param weight     the weight of the shipment in kg
 * @param volume     the volume of the shipment in cm&sup3; (the unit the packages work it out in, not the
 *                   m&sup3; the pages show)
 */
public record DistributorQueryRequest(
        Long customerId,
        @DecimalMin(value = "0", message = "weight must not be negative") BigDecimal weight,
        @DecimalMin(value = "0", message = "volume must not be negative") BigDecimal volume
) {
}
