package com.supplychainmanagement.dto.order;

/**
 * A line of an order that has not fully arrived yet: what was ordered against what a delivered
 * shipment actually brought.
 * <p>
 * The difference matters because a line may be packed in several runs and travel in several
 * shipments - 5 of 10 delivered is neither "delivered" nor "nothing arrived".
 *
 * @param orderItemId the line
 * @param ordered     the quantity the customer asked for
 * @param delivered   what has arrived so far, 0 when nothing of the line has
 */
public record UndeliveredLine(Long orderItemId, int ordered, long delivered) {

    /** True while the line has not arrived in full. */
    public boolean isShort() {
        return delivered < ordered;
    }

    /** For the error message of an order that cannot be completed yet. */
    public String describe() {
        return "line " + orderItemId + ": " + delivered + " of " + ordered + " delivered";
    }
}
