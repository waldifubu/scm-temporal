package com.supplychainmanagement.dto.shipping;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.supplychainmanagement.model.enums.ShipmentPackageType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * A new package made of package items that already exist - the loose ones {@code POST /packing}
 * leaves behind. {@code POST /packing/shipment/from-items}.
 * <p>
 * Not {@link CreatePackageRequest}: that one carries {@code items}, <em>order lines</em> to be packed
 * now ({@code orderItemId} and {@code qty}), which makes new package items. These are ids of package
 * items that exist, so the key is another one ({@code packageItems}) and a client cannot mistake one
 * for the other - nor send both, which on a shared record would have been silently half ignored.
 * <p>
 * No weight: the package computes it from its contents. A {@code "weight"} key sent anyway is
 * ignored like any unknown property, as on {@link CreatePackageRequest}.
 */
public record CreatePackageFromItemsRequest(
        /*
         * The package item ids, not the order item ids: a package item is one run's share of an order
         * line and has an id of its own. At least one - a package is never empty - and none of them
         * null. A repeated id is the same item once.
         */
        @NotEmpty(message = "At least one package item id is required")
        List<@NotNull(message = "a package item id must not be null") Long> packageItems,
        /*
         * On the property, as on CreatePackageRequest: Jackson reads these two features from the
         * property's format overrides, not from the enum declaration. Missing or null means OTHER.
         */
        @JsonFormat(with = {
                JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_VALUES,
                JsonFormat.Feature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE
        })
        ShipmentPackageType shipmentPackageType,
        BigDecimal length,
        BigDecimal width,
        BigDecimal height,
        // Null (or missing) lets the service generate one.
        String packageNumber
) {
}
