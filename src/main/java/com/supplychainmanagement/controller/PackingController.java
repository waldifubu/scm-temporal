package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.common.PageResponse;
import com.supplychainmanagement.dto.order.OrderItemListDto;
import com.supplychainmanagement.dto.shipping.CreatePackageFromItemsRequest;
import com.supplychainmanagement.dto.shipping.CreatePackageItemsRequest;
import com.supplychainmanagement.dto.shipping.CreatePackageRequest;
import com.supplychainmanagement.dto.shipping.PackableItemDto;
import com.supplychainmanagement.dto.shipping.PackageItemIdsRequest;
import com.supplychainmanagement.dto.shipping.PackageItemResponse;
import com.supplychainmanagement.dto.shipping.ShipmentPackageResponse;
import com.supplychainmanagement.dto.shipping.UpdatePackageRequest;
import com.supplychainmanagement.entity.PackageItem;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.service.OrderHandlingService;
import com.supplychainmanagement.service.PackageItemResponseAssembler;
import com.supplychainmanagement.service.PackingService;
import jakarta.validation.Valid;
import jakarta.validation.groups.Default;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * Packing - everything under /packing: packing order lines into packages or loose items, changing
 * what a package holds, and completing it. The writing side of packages ({@link PackingService});
 * reading them is {@link PackageController}'s job. Also /order-items, the picked lines waiting to be
 * packed - the input of this step - and /order-items/packable, the same with what is left to pack.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping({"/api/{version}"})
public class PackingController {

    /**
     * What {@code sort} may name on the two order line lists ({@code /order-items} and
     * {@code /order-items/packable}): the order line's own columns. It is applied as a property path on a
     * projection query, so a joined column (product name, order number) is not resolvable - it would end
     * in a 500 for what is a query-string typo. Package private, so a test can walk every entry.
     */
    static final Set<String> ORDER_ITEM_SORTABLE = Set.of("id", "updatedAt", "quantity", "fulfillmentStatus");

    private final PackingService packingService;
    private final PackageItemResponseAssembler packageItemResponseAssembler;
    private final OrderHandlingService orderHandlingService;

    /**
     * Order lines in one fulfillment status across all orders - PICKED by default, the lines waiting
     * to be packed. Paged like the order list in {@code OrderController.list}, down to the parameter
     * names. Sorted by {@code updatedAt} by default, so the line that reached its status first comes
     * first. {@code sort} accepts the columns of the order line itself; a joined one such as the
     * product name fails, see {@code OrderItemRepository.findAllByFulfillmentStatus}.
     */
    @GetMapping(path = "/order-items", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER','WAREHOUSE')")
    public PageResponse<OrderItemListDto> getOrderItems(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "updatedAt") String sort,
            @RequestParam(defaultValue = "PICKED") FulfillmentStatus status,
            @RequestParam(defaultValue = "ASC") String order) {
        Pageable pageable = PageRequests.of(page, size, sort, order, ORDER_ITEM_SORTABLE);

        return PageResponse.of(orderHandlingService.getOrderItems(status, pageable));
    }

    /**
     * What package items can be made from: the lines that have been picked and still have room -
     * {@code PICKED} and {@code PACKING} together - each with how much of it is packed already and how
     * much remains, and the consumed reservation behind the pick (id, storehouse, time).
     * <p>
     * {@code GET /order-items} lists lines by one status, and cannot say this: it shows neither the
     * packed nor the remaining quantity, which is what a {@code PackItem} has to name (more than
     * remains is a 400, never clipped), and a half-packed line is {@code PACKING}, a status away from
     * the default. {@code orderNo} narrows it to one order - a package holds the items of one order
     * only, so that is the usual call before {@code POST /packing/{orderNo}}.
     * <p>
     * Read by the warehouse, which packs, and by the manager, who watches it; packing itself stays
     * with ADMIN and WAREHOUSE. Sorted by {@code updatedAt} by default - the line picked first comes
     * first; {@code sort} takes the columns of the line itself ({@code id}, {@code updatedAt},
     * {@code quantity}, {@code fulfillmentStatus}), anything else is a 400.
     */
    @GetMapping(path = "/order-items/packable", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER','WAREHOUSE')")
    public PageResponse<PackableItemDto> getPackableItems(
            @RequestParam(required = false) Long orderNo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "updatedAt") String sort,
            @RequestParam(defaultValue = "ASC") String order) {
        return PageResponse.of(packingService.findPackableItems(orderNo,
                PageRequests.of(page, size, sort, order, ORDER_ITEM_SORTABLE)));
    }

    /**
     * Packs the given lines into a new package, so items are required here - hence the WithItems
     * group next to Default. See {@link CreatePackageRequest#items()} for the use cases without.
     */
    @PostMapping(path = "/packing/{orderNo}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public ShipmentPackageResponse createShipmentPackageByOrder(
            @PathVariable Long orderNo,
            @Validated({Default.class, CreatePackageRequest.WithItems.class})
            @RequestBody CreatePackageRequest createPackageRequest) {
        return toResponse(packingService.createShipmentPackage(orderNo, createPackageRequest));
    }

    /**
     * Packs order lines without a package. Everything this call creates shares one runNo, carried by
     * every item in the response.
     * <p>
     * Answered in the paged shape of the list endpoints - one page holding exactly the items created
     * here - and as DTOs: PackageItem reaches Order through its order item, and Order leads back to
     * its items, a cycle Jackson cannot serialize.
     */
    @PostMapping(path = "/packing", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public PageResponse<PackageItemResponse> createPackageItems(
            @Valid @RequestBody CreatePackageItemsRequest createPackageItemsRequest) {

        List<PackageItem> packageItems = packingService.createPackageItems(createPackageItemsRequest);
        List<PackageItemResponse> responses = packageItemResponseAssembler.toResponses(packageItems);

        return PageResponse.of(new PageImpl<>(responses));
    }

    // @TODO: Check if the order lines belong to the same order as the package, and if the quantity is valid. If not, throw an APIException with a message indicating the issue.
    /**
     * Creates a shipment package without an order, for custom shipments. Validate the request and return the created shipment package.
     */
    @PostMapping(path = "/packing/shipment", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public ShipmentPackageResponse createCustomShipment(@Valid @RequestBody CreatePackageRequest createPackageRequest) {
        return toResponse(packingService.createCustomShipment(createPackageRequest));
    }

    /**
     * Creates a package that holds these existing package items, in one call. Body:
     * <pre>{@code
     * { "shipmentPackageType": "OTHER", "packageItems": [1, 2, 3, 4],
     *   "packageNumber": null, "length": null, "width": null, "height": null }
     * }</pre>
     * {@code packageItems} are package item ids - the loose items {@code POST /packing} made - not order
     * line ids; for those {@code POST /packing/shipment} packs the lines itself. All or nothing: an
     * unknown id (404), an item already in a package (409), items of two orders (400) or two items of
     * one line from one run (409) refuse the whole request and leave no package behind, which the
     * two calls {@code POST /packing/shipment} and {@code POST /packing/shipment/{id}/items} do not
     * promise. The weight is not sent: the package computes it from what it holds.
     */
    @PostMapping(path = "/packing/shipment/from-items", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public ShipmentPackageResponse createPackageFromItems(@Valid @RequestBody CreatePackageFromItemsRequest request) {
        return toResponse(packingService.createPackageFromItems(request));
    }

    /**
     * Makes the package hold exactly these package items - body {@code [101, 102]} or
     * {@code {"packageItemIds": [...]}}. The ones it held and that are left out go back to being
     * loose, they are not deleted. An empty list empties the package.
     */
    @PutMapping(path = "/packing/shipment/{shipmentPackageId}/items", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public ShipmentPackageResponse updateCustomShipment(
            @PathVariable Long shipmentPackageId,
            @Valid @RequestBody PackageItemIdsRequest packageItemIdsRequest) {
        return toResponse(packingService.updateCustomShipment(shipmentPackageId, packageItemIdsRequest));
    }

    /**
     * Puts loose package items into the package, same body as above. Items already in it stay, so a
     * repeated call changes nothing.
     */
    @PostMapping(path = "/packing/shipment/{shipmentPackageId}/items", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public ShipmentPackageResponse addPackageItems(
            @PathVariable Long shipmentPackageId,
            @Valid @RequestBody PackageItemIdsRequest packageItemIdsRequest) {
        return toResponse(packingService.addPackageItems(shipmentPackageId, packageItemIdsRequest));
    }

    /** Takes one item out of the package; it goes back to being loose, it is not deleted. */
    @DeleteMapping(path = "/packing/shipment/{shipmentPackageId}/items/{packageItemId}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public ShipmentPackageResponse removePackageItem(
            @PathVariable Long shipmentPackageId,
            @PathVariable Long packageItemId) {
        return toResponse(packingService.removePackageItem(shipmentPackageId, packageItemId));
    }

    /**
     * The package's own data - type, weight, dimensions, number. Its contents are changed through
     * the /items endpoints above.
     */
    @PutMapping(path = "/packing/shipment/{shipmentPackageId}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public ShipmentPackageResponse updatePackageData(
            @PathVariable Long shipmentPackageId,
            @Valid @RequestBody UpdatePackageRequest updatePackageRequest) {
        return toResponse(packingService.updatePackageData(shipmentPackageId, updatePackageRequest));
    }

    /**
     * Closes the package: OPEN to PACKED, after which its contents are fixed and it can go into a
     * shipment. Not OPEN: 409. Without items: 400. Unknown id: 404.
     */
    @PutMapping(path = "/packing/shipment/{shipmentPackageId}/complete", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public ShipmentPackageResponse completePackage(
            @PathVariable Long shipmentPackageId) {
        return toResponse(packingService.completePackage(shipmentPackageId));
    }

    /** The package in the response shape, its items with their siblings. */
    private ShipmentPackageResponse toResponse(ShipmentPackage shipmentPackage) {
        return ShipmentPackageResponse.from(shipmentPackage,
                packageItemResponseAssembler.toResponses(shipmentPackage.getItems()));
    }

}
