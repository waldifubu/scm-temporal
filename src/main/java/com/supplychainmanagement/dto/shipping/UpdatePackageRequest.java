package com.supplychainmanagement.dto.shipping;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.supplychainmanagement.model.enums.ShipmentPackageType;

import java.math.BigDecimal;

/**
 * The package's own data, without its contents - those are changed through the items endpoints.
 * Replaces every field, with the defaults a new package gets: no type means OTHER. Only the package
 * number is kept when left out, since a printed label may already carry it. There is no weight -
 * the package computes it from its contents.
 */
public record UpdatePackageRequest(
        // On the property, not the enum - see CreatePackageRequest.shipmentPackageType.
        @JsonFormat(with = {
                JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_VALUES,
                JsonFormat.Feature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE
        })
        ShipmentPackageType shipmentPackageType,
        BigDecimal length,
        BigDecimal width,
        BigDecimal height,
        String packageNumber
) {
}
