package com.supplychainmanagement.dto.component;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * What to order from one supplier: one line per entry, each becoming its own
 * {@code request_components} row.
 * <p>
 * Accepts the bare JSON array as well as the wrapped object, like
 * {@code CreatePackageItemsRequest} and the other id lists - the static factory carries
 * {@code @JsonCreator(mode = DELEGATING)} while the canonical constructor still reads
 * {@code {"items": [...]}}.
 *
 * @param items at least one line; the same component may appear more than once, and each appearance
 *              is a request of its own rather than a quantity to add up
 */
public record RequestComponentsRequest(@NotEmpty(message = "items is required") @Valid List<Item> items) {

    /**
     * The most that can be ordered on one line. Well below {@code Integer.MAX_VALUE} on purpose:
     * the hard limit is where the arithmetic breaks, and a limit a business can read is better than
     * one a type imposes. A million of a part is a bulk order; a billion is a typo.
     */
    public static final long MAX_QTY = 1_000_000L;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static RequestComponentsRequest of(List<Item> items) {
        return new RequestComponentsRequest(items);
    }

    /**
     * One line of the request.
     *
     * @param componentId the component's <strong>SKU</strong>, not its numeric id - the JSON key is
     *                    kept as the clients send it, the UUID type says which of the two it is
     * @param qty         how many are wanted, at least one and at most {@value #MAX_QTY}. The upper
     *                    bound is where the chain stays sound rather than where the column ends:
     *                    the goods receipt books the quantity into {@code Stock.onHand}, an
     *                    {@code int}, through {@code Long.intValue()}, so a quantity above
     *                    {@code Integer.MAX_VALUE} would truncate and come out negative - a receipt
     *                    that *lowers* the stock. A million is far below that and still reads as a
     *                    business limit rather than a type's. {@code receiveRequest} carries the
     *                    hard check as well, for rows that did not come through here
     * @param comment     optional, free text - why it is needed
     */
    public record Item(
            @NotNull(message = "componentId is required") UUID componentId,
            @NotNull(message = "qty is required")
            @Positive(message = "qty has to be at least 1")
            @Max(value = MAX_QTY, message = "qty is at most " + MAX_QTY) Long qty,
            @Size(max = 255, message = "comment is at most 255 characters") String comment) {
    }
}
