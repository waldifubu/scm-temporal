package com.supplychainmanagement.dto.shipping;

import com.supplychainmanagement.entity.PackageItem;
import com.supplychainmanagement.entity.ShipmentPackage;

import java.util.UUID;

public record PackageItemResponse(
        Long id,
        Long orderItemId,
        String sku,
        Integer quantity,
        Integer totalQuantity,
        UUID runNo,
        Long shipmentPackageId
) {
    public static PackageItemResponse from(
            PackageItem packageItem) {

        ShipmentPackage shipmentPackage = packageItem.getShipmentPackage();

        return new PackageItemResponse(
                packageItem.getId(),
                packageItem.getOrderItem().getId(),
                packageItem.getOrderItem()
                        .getProduct()
                        .getSku().toString(),
                packageItem.getQuantity(),
                packageItem.getOrderItem().getQuantity(),
                packageItem.getRunNo(),
                // Null for items packed without a package - see PackingService.createPackageItems.
                shipmentPackage != null ? shipmentPackage.getId() : null
        );
    }

}