package com.supplychainmanagement.dto.shipping;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.PackageItem;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.model.enums.PackageStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public record ShipmentPackageListDto(
        Long id,
        String packageNumber,
        PackageStatus status,
        BigDecimal weight,
        BigDecimal volume,
        LocalDate dueDate
) {
    public static ShipmentPackageListDto from(ShipmentPackage shipmentPackage) {
        return new ShipmentPackageListDto(
                shipmentPackage.getId(),
                shipmentPackage.getPackageNumber(),
                shipmentPackage.getStatus(),
                shipmentPackage.getWeight(),
                shipmentPackage.getVolume(),
                resolveDueDate(shipmentPackage.getItems())
        );
    }

    private static LocalDate resolveDueDate(List<PackageItem> items) {
        return items.stream()
                .map(PackageItem::getOrderItem)
                .map(OrderItem::getOrder)
                .map(Order::getDueDate)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}
