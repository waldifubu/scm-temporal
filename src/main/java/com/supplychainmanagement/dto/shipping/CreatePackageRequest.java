package com.supplychainmanagement.dto.shipping;

import java.math.BigDecimal;
import java.util.List;

public record CreatePackageRequest(
        List<PackItem> items,
        BigDecimal weight,
        BigDecimal length,
        BigDecimal width,
        BigDecimal height,
        String packageNumber
) {}