package com.supplychainmanagement.dto.shipping;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.PackageItem;
import com.supplychainmanagement.model.enums.FulfillmentStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A package item as it appears inside its package's list row. The same as PackageItemResponse minus
 * the package id - the row it sits in already is that package.
 */
public record ShipmentPackageItemDto(
        Long id,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime createdAt,
        Long orderItemId,
        Long orderNo,
        String sku,
        Integer quantity,
        Integer totalQuantity,
        UUID runNo,
        FulfillmentStatus fulfillmentStatus
) {

    public static ShipmentPackageItemDto from(PackageItem packageItem) {
        OrderItem orderItem = packageItem.getOrderItem();

        return new ShipmentPackageItemDto(
                packageItem.getId(),
                packageItem.getCreated(),
                orderItem.getId(),
                orderItem.getOrder().getOrderNo(),
                orderItem.getProduct().getSku().toString(),
                packageItem.getQuantity(),
                orderItem.getQuantity(),
                packageItem.getRunNo(),
                orderItem.getFulfillmentStatus()
        );
    }
}
