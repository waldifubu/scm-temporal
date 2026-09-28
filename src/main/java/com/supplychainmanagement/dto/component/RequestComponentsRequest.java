package com.supplychainmanagement.dto.component;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
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

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static RequestComponentsRequest of(List<Item> items) {
        return new RequestComponentsRequest(items);
    }

    /**
     * One line of the request.
     *
     * @param componentId the component's <strong>SKU</strong>, not its numeric id - the JSON key is
     *                    kept as the clients send it, the UUID type says which of the two it is
     * @param qty         how many are wanted, at least one
     * @param comment     optional, free text - why it is needed
     */
    public record Item(
            @NotNull(message = "componentId is required") UUID componentId,
            @NotNull(message = "qty is required") @Positive(message = "qty has to be at least 1") Long qty,
            @Size(max = 255, message = "comment is at most 255 characters") String comment) {
    }
}
