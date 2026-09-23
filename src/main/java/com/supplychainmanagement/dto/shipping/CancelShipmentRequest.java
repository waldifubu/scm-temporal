package com.supplychainmanagement.dto.shipping;

import jakarta.validation.constraints.NotBlank;

/**
 * Why a shipment was called off. Required: a cancelled shipment hands its packages back and takes
 * its orders a step back, and nothing else records who did that for which reason - the reason is
 * kept in the shipment's {@code comment}.
 */
public record CancelShipmentRequest(
        @NotBlank(message = "reason is required")
        String reason
) {
}
