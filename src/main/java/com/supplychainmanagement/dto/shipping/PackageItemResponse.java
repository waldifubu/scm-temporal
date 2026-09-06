package com.supplychainmanagement.dto.shipping;

import com.supplychainmanagement.entity.PackageItem;

public record PackageItemResponse(
        Long orderItemId,
        String sku,
        Integer quantity
) {
    public static PackageItemResponse from(
            PackageItem packageItem) {

        return new PackageItemResponse(
                packageItem.getOrderItem().getId(),
                packageItem.getOrderItem()
                        .getProduct()
                        .getSku().toString(),
                packageItem.getQuantity()
        );
    }

}