package com.supplychainmanagement.dto.shipping;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.PackageItem;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
import com.supplychainmanagement.model.enums.ShipmentPackageType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

public record ShipmentPackageListDto(
        Long id,
        String packageNumber,
        ShipmentPackageStatus status,
        ShipmentPackageType type,
        /*
         * When the package was made, and when it was completed (OPEN to PACKED) - null until then, and
         * null for a package completed before the column existed. The same pattern as the items in the
         * same row, so one document does not format its timestamps two ways.
         */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime createdAt,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime packedAt,
        BigDecimal weight,
        BigDecimal packageWeight,
        /*
         * The dimensions as the package has them, in the order they are written down and sent
         * (length, width, height). Null where none were given - a new package fills them with 0 on
         * insert, so null is only an older row. volume is worked out from them.
         */
        BigDecimal length,
        BigDecimal width,
        BigDecimal height,
        BigDecimal volume,
        LocalDate dueDate,
        /*
         * The customer the package goes to, read off the order of its first item - a package holds the items of one
         * order only, so it has exactly one. Null for an empty package. Only the id: the customer is a LAZY
         * reference, and its id is served by the proxy without loading it.
         */
        Long customerId,
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
                shipmentPackage.getCreatedAt(),
                shipmentPackage.getPackedAt(),
                shipmentPackage.getWeight(),
                shipmentPackage.getPackageWeight(),
                shipmentPackage.getLength(),
                shipmentPackage.getWidth(),
                shipmentPackage.getHeight(),
                shipmentPackage.getVolume(),
                resolveDueDate(shipmentPackage.getItems()),
                resolveCustomerId(shipmentPackage.getItems()),
                shipmentPackage.getItems().size(),
                shipmentPackage.getItems().stream().map(ShipmentPackageItemDto::from).toList()
        );
    }

    private static Long resolveCustomerId(List<PackageItem> items) {
        return items.stream()
                .map(PackageItem::getOrderItem)
                .map(OrderItem::getOrder)
                .map(Order::getCustomer)
                .filter(Objects::nonNull)
                .map(customer -> customer.getId())
                .findFirst()
                .orElse(null);
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
