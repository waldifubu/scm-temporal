package com.supplychainmanagement.dto.shipping;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.PackageItem;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.model.enums.FulfillmentStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PackageItemResponse(
        Long id,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime createdAt,
        Long orderItemId,
        Long orderNo,
        String sku,
        Integer quantity,
        Integer totalQuantity,
        List<Long> siblings,
        UUID runNo,
        Long shipmentPackageId,
        FulfillmentStatus fulfillmentStatus
) {
    /**
     * @param siblings the ids of the other package items of the same order line - computed, not
     *                 stored. Built by PackageItemResponseAssembler, which looks them up for all
     *                 items of a response in one query.
     */
    public static PackageItemResponse from(PackageItem packageItem, List<Long> siblings) {

        ShipmentPackage shipmentPackage = packageItem.getShipmentPackage();

        return new PackageItemResponse(
                packageItem.getId(),
                packageItem.getCreated(),
                packageItem.getOrderItem().getId(),
                packageItem.getOrderItem().getOrder().getOrderNo(),
                packageItem.getOrderItem()
                        .getProduct()
                        .getSku().toString(),
                packageItem.getQuantity(),
                packageItem.getOrderItem().getQuantity(),
                siblings,
                packageItem.getRunNo(),
                // Null for items packed without a package - see PackingService.createPackageItems.
                shipmentPackage != null ? shipmentPackage.getId() : null,
                packageItem.getOrderItem().getFulfillmentStatus()
        );
    }

}