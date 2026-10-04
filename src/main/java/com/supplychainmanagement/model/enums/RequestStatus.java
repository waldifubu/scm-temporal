package com.supplychainmanagement.model.enums;

/**
 * The life of a component request, from placing it to the goods being on the shelf.
 * <p>
 * {@code DELIVERED} and {@code IN_STOCK} look alike and are not: the first is what the supplier
 * claims - handed over at our dock - the second what we found when we unpacked it. Only the second
 * books stock. That is the classic goods receipt, and it is also why the two belong to different
 * roles.
 * <p>
 * It ends at {@code IN_STOCK}. There used to be an {@code ASSEMBLED} after it ("part is assembled in
 * a product"), which could never be set correctly: {@code Stock} is keyed by
 * {@code (storehouse_id, sku)} and holds a quantity, with no batch and no reference back to the
 * request the pieces came from, and {@code produce()} only decrements that quantity. Nothing records
 * whose screws went into which product, and one status per row could not express "4 of 12 built in"
 * either. What happens to the material after the goods receipt is the business of {@code Stock} and
 * production, not of a procurement document.
 */
public enum RequestStatus {

    /** Placed with the supplier and waiting for their answer. */
    OPEN(0),

    /** The supplier accepted it. */
    APPROVED(1),

    /** The supplier has sent the goods. */
    IN_TRANSIT(2),

    /**
     * The supplier reports the goods handed over - their part is done. A claim, not a check: nothing
     * is booked yet, and the warehouse has not seen the pallet.
     */
    DELIVERED(3),

    /**
     * The warehouse checked the delivery and booked it in: {@code Stock.onHand} for this component's
     * SKU in the receiving storehouse grew by the requested quantity. The end of the request's life.
     */
    IN_STOCK(4);

    RequestStatus(int i) {

    }
}
