package com.supplychainmanagement.dto.shipping;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Existing package items, by id, to put into a package or to make up its contents. Read from either
 * body shape: {@code {"packageItemIds": [101, 102]}} or the bare array {@code [101, 102]}.
 * <p>
 * An empty list is valid here on purpose - replacing a package's contents with nothing empties it.
 * Adding nothing is refused by the service instead.
 */
public record PackageItemIdsRequest(
        @NotNull(message = "packageItemIds is required")
        List<@NotNull(message = "a package item id must not be null") Long> packageItemIds
) {

    /** Binds a bare JSON array of ids, next to the object form through the canonical constructor. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static PackageItemIdsRequest of(List<Long> packageItemIds) {
        return new PackageItemIdsRequest(packageItemIds);
    }
}
