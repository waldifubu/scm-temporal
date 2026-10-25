package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.shipping.CreatePackageFromItemsRequest;
import com.supplychainmanagement.dto.shipping.CreatePackageItemsRequest;
import com.supplychainmanagement.dto.shipping.CreatePackageRequest;
import com.supplychainmanagement.dto.shipping.PackableItemDto;
import com.supplychainmanagement.dto.shipping.PackageItemIdsRequest;
import com.supplychainmanagement.dto.shipping.UpdatePackageRequest;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.PackageItem;
import com.supplychainmanagement.entity.ShipmentPackage;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface PackingService {

    /**
     * The order lines that have been picked and can still be packed - the stage package items are
     * made from: {@code PICKED} lines (nothing packed yet) and {@code PACKING} ones (some of it), each
     * with how much is packed already and how much remains, and the consumed reservation behind the
     * pick. {@code orderNo} narrows the list to one order, null lists every order. Read-only; paged by
     * the caller's {@code pageable}, with the line's id as the tie-break so paging stays stable.
     */
    Page<PackableItemDto> findPackableItems(Long orderNo, Pageable pageable);

    ShipmentPackage createShipmentPackage(Long orderNo, CreatePackageRequest createPackageRequest);

    List<PackageItem> createPackageItems(CreatePackageItemsRequest createPackageItemsRequest);

    void validateOrderItemPacking(OrderItem orderItem);

    ShipmentPackage createCustomShipment(CreatePackageRequest request);

    /**
     * Creates a package that holds these existing package items - loose ones, made by
     * {@code createPackageItems} - in one step. What {@code createCustomShipment} followed by
     * {@code addPackageItems} does in two calls, with the difference that it is all or nothing: one item
     * that cannot go in and there is no package, instead of an empty one left behind.
     * <p>
     * An unknown id is a 404 naming every one that is missing, an item already in a package a 409 (never
     * taken out of it silently), items of more than one order a 400, and two items of one order line from
     * one run a 409. The new package is {@code OPEN}.
     */
    ShipmentPackage createPackageFromItems(CreatePackageFromItemsRequest request);

    /** The package's own data - type, weight, dimensions, number. Its contents stay as they are. */
    ShipmentPackage updatePackageData(Long shipmentPackageId, UpdatePackageRequest updatePackageRequest);

    /** Puts loose package items into the package; items already in it are left as they are. */
    ShipmentPackage addPackageItems(Long shipmentPackageId, PackageItemIdsRequest packageItemIdsRequest);

    /** Makes the package hold exactly these items; the others go back to being loose. */
    ShipmentPackage updateCustomShipment(Long shipmentPackageId, PackageItemIdsRequest packageItemIdsRequest);

    /** Takes one item out of the package - it goes back to being loose, it is not deleted. */
    ShipmentPackage removePackageItem(Long shipmentPackageId, Long packageItemId);

    /** Closes an OPEN package with at least one item: PACKED, contents fixed, ready for a shipment. */
    ShipmentPackage completePackage(Long shipmentPackageId);
}
