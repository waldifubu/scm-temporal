package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.picking.PickingOrderDto;
import com.supplychainmanagement.dto.shipping.CreatePackageItemsRequest;
import com.supplychainmanagement.dto.shipping.CreatePackageRequest;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.PackageItem;
import com.supplychainmanagement.entity.ShipmentPackage;

import java.util.List;

public interface PackingService {
    ShipmentPackage createShipmentPackage(Long orderNo, CreatePackageRequest createPackageRequest);

    List<PackageItem> createPackageItems(CreatePackageItemsRequest createPackageItemsRequest);

    void validateOrderItemPacking(OrderItem orderItem);

    ShipmentPackage createEmptyShipment(CreatePackageRequest request);
}
