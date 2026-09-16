package com.supplychainmanagement.dto.shipping;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.supplychainmanagement.model.enums.ShipmentPackageType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.math.BigDecimal;
import java.util.List;

public record CreatePackageRequest(
        /*
         * Required or not depending on the use case: only validated as required in the WithItems
         * group, so an endpoint that creates the package on its own can leave it out. Items that are
         * sent are validated either way - the @Valid on the element belongs to the Default group,
         * which is why an endpoint requiring items validates {Default, WithItems}, not WithItems alone.
         */
        @NotEmpty(groups = CreatePackageRequest.WithItems.class, message = "At least one item is required")
        List<@Valid PackItem> items,
        /*
         * On the property, not on the enum declaration: Jackson resolves these two features from
         * the property's format overrides, and a @JsonFormat on the enum class itself is not
         * consulted there. Putting it on ShipmentPackage has no effect either - that entity is
         * never read from JSON, this record is the only thing bound from a request body.
         */
        @JsonFormat(with = {
                JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_VALUES,
                JsonFormat.Feature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE
        })
        ShipmentPackageType shipmentPackageType,
        BigDecimal weight,
        BigDecimal length,
        BigDecimal width,
        BigDecimal height,
        String packageNumber
) {

    /**
     * Validation group for the use cases that pack items together with the package. Use it as
     * {@code @Validated({Default.class, CreatePackageRequest.WithItems.class})}; a plain {@code @Valid}
     * validates the Default group only and lets {@code items} be null or empty.
     */
    public interface WithItems {
    }
}