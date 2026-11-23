package com.supplychainmanagement.dto.rule;

import com.supplychainmanagement.dto.common.DisplayNames;
import com.supplychainmanagement.entity.DistributorRule;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;

/**
 * One row of {@code distributor_rule}: which carrier a shipment goes to. Every condition that is {@code null} is
 * no condition. Weight is in kg, volume in cm&sup3;, bounds are {@code [min, max)}.
 */
public record DistributorRuleResponse(
        Long id,
        Long distributorId,
        String distributorName,
        @Nullable Long customerId,
        @Nullable String customerName,
        @Nullable BigDecimal minWeight,
        @Nullable BigDecimal maxWeight,
        @Nullable BigDecimal minVolume,
        @Nullable BigDecimal maxVolume,
        int priority
) {

    public static DistributorRuleResponse from(DistributorRule rule) {
        var customer = rule.getCustomer();
        return new DistributorRuleResponse(
                rule.getId(),
                rule.getDistributor().getId(),
                DisplayNames.of(rule.getDistributor()),
                customer == null ? null : customer.getId(),
                customer == null ? null : DisplayNames.of(customer),
                rule.getMinWeight(),
                rule.getMaxWeight(),
                rule.getMinVolume(),
                rule.getMaxVolume(),
                rule.getPriority());
    }
}
