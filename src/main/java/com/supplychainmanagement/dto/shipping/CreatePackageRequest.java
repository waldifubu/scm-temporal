package com.supplychainmanagement.dto.shipping;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.supplychainmanagement.model.enums.PackageType;

import java.math.BigDecimal;
import java.util.List;

public record CreatePackageRequest(
        List<PackItem> items,
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
        PackageType packageType,
        BigDecimal weight,
        BigDecimal length,
        BigDecimal width,
        BigDecimal height,
        String packageNumber
) {}