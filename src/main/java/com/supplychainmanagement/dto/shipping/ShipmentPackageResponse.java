package com.supplychainmanagement.dto.shipping;

import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
import com.supplychainmanagement.model.enums.ShipmentPackageType;

import java.math.BigDecimal;
import java.util.List;

public record ShipmentPackageResponse(
        Long id,
        String packageNumber,
        ShipmentPackageStatus status,
        ShipmentPackageType type,
        BigDecimal weight,
        BigDecimal packageWeight,
        BigDecimal length,
        BigDecimal width,
        BigDecimal height,
        List<PackageItemResponse> items
) {
    /**
     * @param items the package's items as responses - built by PackageItemResponseAssembler, since
     *              their siblings need a database lookup this record cannot make
     */
    public static ShipmentPackageResponse from(ShipmentPackage shipmentPackage, List<PackageItemResponse> items) {
        return new ShipmentPackageResponse(
                shipmentPackage.getId(),
                shipmentPackage.getPackageNumber(),
                shipmentPackage.getShipmentPackageStatus(),
                shipmentPackage.getShipmentPackageType(),
                shipmentPackage.getWeight(),
                shipmentPackage.getPackageWeight(),
                shipmentPackage.getLength(),
                shipmentPackage.getWidth(),
                shipmentPackage.getHeight(),
                items
        );
    }

}