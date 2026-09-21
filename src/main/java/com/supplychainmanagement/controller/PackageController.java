package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.common.PageResponse;
import com.supplychainmanagement.dto.shipping.PackageItemResponse;
import com.supplychainmanagement.dto.shipping.ShipmentPackageListDto;
import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
import com.supplychainmanagement.service.PackageQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading packages and package items - the lists and single entries. Changing them is
 * {@link PackingController}'s job.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping({"/api/{version}"})
public class PackageController {

    private final PackageQueryService packageQueryService;

    /**
     * All package items - loose ones from POST /packing as well as those in a package, which is what
     * {@code shipmentPackageId} tells apart. Paged like the other lists; {@code sort} takes the fields
     * of the package item itself (id, created, quantity, runNo, ...).
     */
    @GetMapping(path = "/packages", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public PageResponse<PackageItemResponse> getPackageItems(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "id") String sort,
            @RequestParam(defaultValue = "ASC") String order) {
        return PageResponse.of(packageQueryService.findPackageItems(pageRequest(page, size, sort, order)));
    }

    /**
     * Shipment packages in one status - OPEN by default, the ones still being filled. Paged like the
     * order list in {@code OrderController.list}, down to the parameter names. {@code sort} takes the
     * fields of the package itself (id, packageNumber, createdAt, ...).
     */
    @GetMapping(path = "/shipment-packages", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'LOGISTICS')")
    public PageResponse<ShipmentPackageListDto> getShipmentPackages(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "id") String sort,
            @RequestParam(defaultValue = "OPEN") ShipmentPackageStatus status,
            @RequestParam(defaultValue = "") String packageNumber,
            @RequestParam(defaultValue = "ASC") String order) {
        return PageResponse.of(packageQueryService.findShipmentPackages(status, packageNumber, pageRequest(page, size, sort, order)));
    }

    /**
     * The package items not in any package yet - loose ones from POST /packing, the candidates for
     * POST /packing/shipment/{id}/items. Same paging and sort fields as /packages.
     */
    @GetMapping(path = "/lonely-packages", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public PageResponse<PackageItemResponse> getLonelyPackageItems(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "id") String sort,
            @RequestParam(defaultValue = "ASC") String order) {
        return PageResponse.of(packageQueryService.findLoosePackageItems(pageRequest(page, size, sort, order)));
    }

    /** One package item, in the same shape as the /packages list. Unknown id: 404. */
    @GetMapping(path = "/packages/{packageItemId}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public PackageItemResponse getPackageItem(@PathVariable Long packageItemId) {
        return packageQueryService.findPackageItem(packageItemId);
    }

    /** One package with its items, in the same shape as the /shipment-packages list. Unknown id: 404. */
    @GetMapping(path = "/shipment-packages/{shipmentPackageId}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE','LOGISTICS')")
    public ShipmentPackageListDto getShipmentPackage(@PathVariable Long shipmentPackageId) {
        return packageQueryService.findShipmentPackage(shipmentPackageId);
    }

    private static Pageable pageRequest(int page, int size, String sort, String order) {
        Sort.Direction direction = "DESC".equalsIgnoreCase(order) ? Sort.Direction.DESC : Sort.Direction.ASC;
        return PageRequest.of(page, size, Sort.by(direction, sort));
    }
}
