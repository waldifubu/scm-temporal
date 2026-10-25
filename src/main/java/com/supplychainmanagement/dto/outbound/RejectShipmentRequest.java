package com.supplychainmanagement.dto.outbound;

import com.supplychainmanagement.dto.shipping.CancelShipmentRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Why a carrier turned a shipment down. Required: it is the only thing the rejection records besides the
 * status, and the planner who has to hand the shipment to somebody else reads it to know why. It is
 * kept in the shipment's {@code distributorComment}.
 * <p>
 * Shaped like {@link CancelShipmentRequest}. The length is bounded because the column is a plain
 * {@code varchar(255)}: a longer reason would otherwise come back as a 500 from the database instead of a
 * 400 that names the field.
 */
public record RejectShipmentRequest(
        @NotBlank(message = "reason is required")
        @Size(max = RejectShipmentRequest.MAX_REASON_LENGTH, message = "reason is too long (at most 255 characters)")
        String reason
) {

    /** What {@code shipment.distributor_comment} holds. */
    public static final int MAX_REASON_LENGTH = 255;
}
