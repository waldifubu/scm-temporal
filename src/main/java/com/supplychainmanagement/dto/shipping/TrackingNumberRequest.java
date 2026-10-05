package com.supplychainmanagement.dto.shipping;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The carrier's own reference for a shipment: {@code { "trackingNumber": "DHL-123" }}.
 * <p>
 * The endpoint took a bare {@code @RequestBody String}, which is not a JSON object at all - a client
 * had to send the number as a raw quoted string and nothing said so. A record gives the field a name
 * in the body the way every other endpoint here has one, and the validation moves to where a client
 * can see which field was wrong: a blank or over-long number is a 400 naming {@code trackingNumber}
 * instead of a message about a value with no name.
 * <p>
 * {@code DeliveryServiceImpl.assignTrackingNumber} keeps its own null/blank and length checks. They
 * are not redundant: the service is reachable from inside the application, where no bean validation
 * runs, and the rule belongs to the operation rather than to one way of calling it.
 *
 * @param trackingNumber at most {@value #MAX_LENGTH} characters, matching the service's own limit
 */
public record TrackingNumberRequest(
        @NotBlank(message = "trackingNumber is required")
        @Size(max = MAX_LENGTH, message = "trackingNumber is at most " + MAX_LENGTH + " characters")
        String trackingNumber
) {

    /** The same bound the service enforces - kept in one place so the two cannot drift apart. */
    public static final int MAX_LENGTH = 70;
}
