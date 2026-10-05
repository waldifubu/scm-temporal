package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.common.PageResponse;
import com.supplychainmanagement.dto.order.OrderItemListDto;
import com.supplychainmanagement.dto.shipping.CreatePackageItemsRequest;
import com.supplychainmanagement.dto.shipping.CreatePackageRequest;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Packing - everything under /packing: packing order lines into packages or loose items, changing
 * what a package holds, and completing it. The writing side of packages ({@link PackingService});
 * reading them is {@link PackageController}'s job. Also /order-items, the picked lines waiting to be
 * packed - the input of this step.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping({"/api/{version}"})
public class PackingController {

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
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public PageResponse<OrderItemListDto> getOrderItems(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "updatedAt") String sort,
            @RequestParam(defaultValue = "PICKED") FulfillmentStatus status,
            @RequestParam(defaultValue = "ASC") String order) {
        Sort.Direction dir = "DESC".equalsIgnoreCase(order) ? Sort.Direction.DESC : Sort.Direction.ASC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(dir, sort));

        return PageResponse.of(orderHandlingService.getOrderItems(status, pageable));
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
