package com.supplychainmanagement.controller;

import com.supplychainmanagement.model.enums.FulfillmentStatus;
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
import java.util.Set;

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
     * of the package item itself (id, created, quantity, runNo, ...). Without {@code sort}: by
     * {@code created}, then by order number.
     */
    // Packages belong to WAREHOUSE, shipments to LOGISTICS, and the two do not overlap: everything
    // under /packages and /shipment-packages is packing work, everything under /shipments is
    // planning. The planning side gets what it needs from a view of its own,
    // GET /shipments/packages - see the note on getShipmentPackages.
    @GetMapping(path = "/packages", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public PageResponse<PackageItemResponse> getPackageItems(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "ASC") String order,
            @RequestParam(required = false) FulfillmentStatus fulfillmentStatus) {
        return PageResponse.of(packageQueryService.findPackageItems(itemPageRequest(page, size, sort, order), fulfillmentStatus));
    }

    /**
     * Shipment packages in one status - OPEN by default, the ones still being filled. Paged like the
     * order list in {@code OrderController.list}, down to the parameter names. {@code sort} takes the
     * fields of the package itself (id, packageNumber, createdAt, ...).
     */
    // LOGISTICS removed on purpose, see above. This list - with status=PACKED - used to be the only
    // way to find the free packages POST /shipments takes, which is why the planning side was on it.
    // It has GET /shipments/packages of its own now, and that one is the better list anyway: it
    // leaves out the packages already travelling in a shipment, which this one still shows.
    @GetMapping(path = "/shipment-packages", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE', 'LOGISTICS')")
    public PageResponse<ShipmentPackageListDto> getShipmentPackages(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "id") String sort,
            @RequestParam(defaultValue = "OPEN") ShipmentPackageStatus status,
            @RequestParam(defaultValue = "") String packageNumber,
            @RequestParam(defaultValue = "ASC") String order) {
        return PageResponse.of(packageQueryService.findShipmentPackages(status, packageNumber, pageRequest(page, size, sort, order, SHIPMENT_PACKAGE_SORTABLE)));
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
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "ASC") String order,
            @RequestParam(required = false) FulfillmentStatus fulfillmentStatus) {
        return PageResponse.of(packageQueryService.findLoosePackageItems(itemPageRequest(page, size, sort, order), fulfillmentStatus));
    }

    /** One package item, in the same shape as the /packages list. Unknown id: 404. */
    @GetMapping(path = "/packages/{packageItemId}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public PackageItemResponse getPackageItem(@PathVariable Long packageItemId) {
        return packageQueryService.findPackageItem(packageItemId);
    }

    /** One package with its items, in the same shape as the /shipment-packages list. Unknown id: 404. */
    @GetMapping(path = "/shipment-packages/{shipmentPackageId}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE', 'LOGISTICS')")
    public ShipmentPackageListDto getShipmentPackage(@PathVariable Long shipmentPackageId) {
        return packageQueryService.findShipmentPackage(shipmentPackageId);
    }

    /**
     * What {@code sort} may name on the two package item lists: the columns of the item itself, and what a
     * table shows next to them - the order line it belongs to (id, quantity, fulfillment status), its order
     * number and the package it sits in. Anything else is a 400 naming these - it used to be a 500 for what is
     * a typo in a query string. Package private, so a test can walk every entry against the real query.
     * <p>
     * {@code shipmentPackage.id} is a path through an <em>optional</em> association: a loose item has no
     * package. Spring Data sorts through a left join there, so loose items are sorted, not dropped from the list
     * the moment somebody clicks that column - {@code PackageListSortTest} holds it with real rows.
     */
    static final Set<String> PACKAGE_ITEM_SORTABLE = Set.of("id", "runNo", "quantity", "created",
            "orderItem.id", "orderItem.order.orderNo", "orderItem.quantity", "orderItem.fulfillmentStatus",
            "shipmentPackage.id");

    /** The same for the package list: the package's own columns, under the entity's names. */
    static final Set<String> SHIPMENT_PACKAGE_SORTABLE = Set.of("id", "packageNumber", "createdAt", "packedAt",
            "weight", "length", "width", "height", "shipmentPackageStatus", "shipmentPackageType");

    /**
     * The page of an item list. Without a {@code sort} the list is ordered by <strong>when the item was made,
     * then by the number of its order</strong> - two keys, so it cannot be a default value of the parameter -
     * and {@code order} turns both. With a {@code sort} it is that one key, as before; the service adds what
     * keeps the pages apart.
     */
    private static Pageable itemPageRequest(int page, int size, String sort, String order) {
        if (sort != null && !sort.isBlank()) {
            return pageRequest(page, size, sort, order, PACKAGE_ITEM_SORTABLE);
        }
        Pageable byCreated = pageRequest(page, size, "created", order, PACKAGE_ITEM_SORTABLE);
        Sort.Direction direction = byCreated.getSort().iterator().next().getDirection();
        return PageRequest.of(page, size, byCreated.getSort().and(Sort.by(direction, "orderItem.order.orderNo")));
    }

    private static Pageable pageRequest(int page, int size, String sort, String order, Set<String> sortable) {
        return PageRequests.of(page, size, sort, order, sortable);
    }
}
