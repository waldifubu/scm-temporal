package com.supplychainmanagement.dto.outbound;

import jakarta.validation.constraints.Size;

/**
 * What a carrier says when they take a shipment on - kept in the shipment's {@code distributorComment}, the
 * same column a decline writes its reason to. The carrier's own note on the shipment, whichever way they
 * answered it.
 * <p>
 * Shaped like {@link DeclineShipmentRequest} and named {@code reason} like it - the page has one text field for
 * both answers - but <strong>optional</strong> here: taking a shipment on needs no explanation, turning it down
 * does. A call without a body, an empty body or a blank text is a plain acceptance and leaves the comment as
 * it was. Bounded to the column for the same reason as the decline: a longer text is a 400 that names the
 * field, not a 500 from the database.
 */
public record AcceptShipmentRequest(
        @Size(max = DeclineShipmentRequest.MAX_REASON_LENGTH, message = "reason is too long (at most 255 characters)")
        String reason
) {
}
