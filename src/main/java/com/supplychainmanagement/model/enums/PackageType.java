package com.supplychainmanagement.model.enums;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

import java.math.BigDecimal;

/**
 * The kind of packaging a shipment package is made of, with the empty weight that comes with it.
 * <p>
 * The tare matters because a carrier is billed for what it actually carries: a Euro pallet adds
 * some 25 kg before a single article is on it, a pallet cage close to 70. Only the sum of content
 * and tare is the weight that goes on the label - see {@code ShipmentPackage.getPackageWeight()}.
 * <p>
 * Values are kilograms and deliberately rough: they are typical figures for the packaging type, not
 * a measurement of the individual one. Anything that has been weighed belongs in
 * {@code ShipmentPackage.weight}, which takes precedence over this estimate.
 */
public enum PackageType {

    CARTON(new BigDecimal("0.5")),
    WOODEN_BOX(new BigDecimal("8.0")),
    STACKABLE_CONTAINER(new BigDecimal("3.0")),
    PALLET(new BigDecimal("20.0")),
    EURO_PALLET(new BigDecimal("25.0")),
    PALLET_CAGE(new BigDecimal("70.0")),
    PLASTIC_CRATE(new BigDecimal("2.0")),
    METAL_CONTAINER(new BigDecimal("15.0")),
    DRUM(new BigDecimal("12.0")),
    BAG(new BigDecimal("0.2")),

    /** Unknown packaging - contributes nothing rather than an invented number. */
    @JsonEnumDefaultValue
    OTHER(BigDecimal.ZERO);

    private final BigDecimal tareWeight;

    PackageType(BigDecimal tareWeight) {
        this.tareWeight = tareWeight;
    }

    /** Empty weight of this packaging in kilograms. */
    public BigDecimal getTareWeight() {
        return tareWeight;
    }
}
