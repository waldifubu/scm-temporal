package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.shipping.PackageItemResponse;
import com.supplychainmanagement.dto.shipping.ShipmentPackageListDto;
import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Reading shipment packages for the outbound side. Changing what a package holds is
 * {@link PackingService}'s job; this one lists them.
 */
public interface PackageQueryService {

    /** One page of the packages in the given status, as list rows. */
    Page<ShipmentPackageListDto> findShipmentPackages(ShipmentPackageStatus status, String packageNumber, Pageable pageable);

    /** One page of all package items, loose ones and those in a package. */
    Page<PackageItemResponse> findPackageItems(Pageable pageable);

    /** One page of the loose package items - the ones not in any package yet. */
    Page<PackageItemResponse> findLoosePackageItems(Pageable pageable);

    /** One package item, in the shape of the /packages list. */
    PackageItemResponse findPackageItem(Long packageItemId);

    /** One package with its items, in the shape of the /shipment-packages list. */
    ShipmentPackageListDto findShipmentPackage(Long shipmentPackageId);
}
