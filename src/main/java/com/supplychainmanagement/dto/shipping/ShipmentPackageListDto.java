package com.supplychainmanagement.dto.shipping;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.PackageItem;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
import com.supplychainmanagement.model.enums.ShipmentPackageType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public record ShipmentPackageListDto(
        Long id,
        String packageNumber,
        ShipmentPackageStatus status,
        ShipmentPackageType type,
        BigDecimal weight,
        BigDecimal packageWeight,
        BigDecimal volume,
        LocalDate dueDate,
        int packages,
        /*
         * DTOs, never the PackageItem entities: an entity serializes its shipmentPackage, whose items
         * serialize their shipmentPackage again - Jackson runs in circles - and drags the LAZY order
         * line, order and product along. ShipmentPackageItemDto does not name the package at all -
         * the row it sits in is that package.
         */
        List<ShipmentPackageItemDto> items
) {
    public static ShipmentPackageListDto from(ShipmentPackage shipmentPackage) {
        return new ShipmentPackageListDto(
                shipmentPackage.getId(),
                shipmentPackage.getPackageNumber(),
                shipmentPackage.getShipmentPackageStatus(),
                shipmentPackage.getShipmentPackageType(),
                shipmentPackage.getWeight(),
                shipmentPackage.getPackageWeight(),
                shipmentPackage.getVolume(),
                resolveDueDate(shipmentPackage.getItems()),
                shipmentPackage.getItems().size(),
                shipmentPackage.getItems().stream().map(ShipmentPackageItemDto::from).toList()
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
