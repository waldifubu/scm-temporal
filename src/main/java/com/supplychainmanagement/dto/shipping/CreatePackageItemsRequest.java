package com.supplychainmanagement.dto.shipping;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Order lines to pack without putting them into a package yet. Everything created from one request
 * shares a runNo, which is how the items are found again as one packing run.
 * <p>
 * Read from either body shape: {@code {"items": [ ... ]}} through the record's canonical
 * constructor, or the bare array {@code [ ... ]} through {@link #of}. Both end up in the same record,
 * so the bean validation below applies to either.
 */
public record CreatePackageItemsRequest(
        @NotEmpty(message = "At least one item is required")
        List<@Valid PackItem> items
) {

    /**
     * Binds a bare JSON array as the item list. Next to it Jackson still uses the canonical
     * constructor for the object form - an explicit delegating creator does not switch that off.
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static CreatePackageItemsRequest of(List<PackItem> items) {
        return new CreatePackageItemsRequest(items);
    }
}
