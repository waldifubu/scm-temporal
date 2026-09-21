package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.shipping.CreatePackageItemsRequest;
import com.supplychainmanagement.dto.shipping.CreatePackageRequest;
import com.supplychainmanagement.dto.shipping.PackageItemIdsRequest;
import com.supplychainmanagement.dto.shipping.UpdatePackageRequest;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.PackageItem;
import com.supplychainmanagement.entity.ShipmentPackage;

import java.util.List;

public interface PackingService {
    ShipmentPackage createShipmentPackage(Long orderNo, CreatePackageRequest createPackageRequest);

    List<PackageItem> createPackageItems(CreatePackageItemsRequest createPackageItemsRequest);

    void validateOrderItemPacking(OrderItem orderItem);

    ShipmentPackage createCustomShipment(CreatePackageRequest request);

    /** The package's own data - type, weight, dimensions, number. Its contents stay as they are. */
    ShipmentPackage updatePackageData(Long shipmentPackageId, UpdatePackageRequest updatePackageRequest);

    /** Puts loose package items into the package; items already in it are left as they are. */
    ShipmentPackage addPackageItems(Long shipmentPackageId, PackageItemIdsRequest packageItemIdsRequest);

    /** Makes the package hold exactly these items; the others go back to being loose. */
    ShipmentPackage updateCustomShipment(Long shipmentPackageId, PackageItemIdsRequest packageItemIdsRequest);

    /** Takes one item out of the package - it goes back to being loose, it is not deleted. */
    ShipmentPackage removePackageItem(Long shipmentPackageId, Long packageItemId);
}
