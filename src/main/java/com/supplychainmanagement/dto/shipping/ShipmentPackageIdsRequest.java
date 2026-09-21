package com.supplychainmanagement.dto.shipping;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Existing shipment packages, by id, to put into a shipment or to make up its contents. Read from
 * either body shape: {@code {"shipmentPackageIds": [5, 7]}} or the bare array {@code [5, 7]} - like
 * {@link PackageItemIdsRequest}.
 * <p>
 * An empty list passes here; the service refuses it, because a shipment is never left empty.
 */
public record ShipmentPackageIdsRequest(
        @NotNull(message = "shipmentPackageIds is required")
        List<@NotNull(message = "a shipment package id must not be null") Long> shipmentPackageIds
) {

    /** Binds a bare JSON array of ids, next to the object form through the canonical constructor. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static ShipmentPackageIdsRequest of(List<Long> shipmentPackageIds) {
        return new ShipmentPackageIdsRequest(shipmentPackageIds);
    }
}
