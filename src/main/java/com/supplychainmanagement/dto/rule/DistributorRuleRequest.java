package com.supplychainmanagement.dto.rule;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;

/**
 * What a client sends to create or change a distributor rule. A condition left out ({@code null}) is no condition.
 * Weight is in kg, volume in cm&sup3;; bounds are {@code [min, max)}, so a minimum has to be below its maximum.
 */
public record DistributorRuleRequest(
        @NotNull Long distributorId,
        @Nullable Long customerId,
        @Nullable @DecimalMin("0") BigDecimal minWeight,
        @Nullable @DecimalMin("0") BigDecimal maxWeight,
        @Nullable @DecimalMin("0") BigDecimal minVolume,
        @Nullable @DecimalMin("0") BigDecimal maxVolume,
        @Nullable Integer priority
) {
}
