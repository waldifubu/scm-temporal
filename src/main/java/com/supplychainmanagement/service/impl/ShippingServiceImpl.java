package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.shipping.PackageItemResponse;
import com.supplychainmanagement.dto.shipping.ShipmentPackageListDto;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
import com.supplychainmanagement.repository.PackageItemRepository;
import com.supplychainmanagement.repository.ShipmentPackageRepository;
import com.supplychainmanagement.service.PackageItemResponseAssembler;
import com.supplychainmanagement.service.ShippingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ShippingServiceImpl implements ShippingService {

    private final ShipmentPackageRepository shipmentPackageRepository;
    private final PackageItemRepository packageItemRepository;
    private final PackageItemResponseAssembler packageItemResponseAssembler;

    /**
     * One page of packages in the given status, mapped to list rows while the transaction is open.
     * <p>
     * Two queries instead of one: the page itself, then the packages of that page together with
     * their items, order lines, products and orders. Every row reads the content weight and the due
     * date through those. Fetching them in the page query would make Hibernate page in memory - a
     * collection fetch cannot be limited in SQL - and not fetching them at all costs a query per
     * package and per item.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<ShipmentPackageListDto> findShipmentPackages(ShipmentPackageStatus status, String packageNumber, Pageable pageable) {
        // No number to look for means no number filter at all. Containing("") would still be applied
        // as LIKE '%%', and that never matches a package whose number is NULL - it would silently
        // drop out of an unfiltered list.
        Page<ShipmentPackage> page = packageNumber == null || packageNumber.isBlank()
                ? shipmentPackageRepository.findAllByShipmentPackageStatus(status, pageable)
                : shipmentPackageRepository.findAllByShipmentPackageStatusAndPackageNumberContaining(
                        status, packageNumber.trim(), pageable);
        if (page.isEmpty()) {
            return page.map(ShipmentPackageListDto::from);
        }

        List<Long> ids = page.getContent().stream().map(ShipmentPackage::getId).toList();
        Map<Long, ShipmentPackage> withContents = shipmentPackageRepository.findWithItemsByIdIn(ids).stream()
                .collect(Collectors.toMap(ShipmentPackage::getId, Function.identity(), (first, same) -> first));

        // The page decides order and totals; the second query only supplies the contents.
        return page.map(shipmentPackage -> ShipmentPackageListDto.from(
                withContents.getOrDefault(shipmentPackage.getId(), shipmentPackage)));
    }

    /**
     * Every package item, mapped while the transaction is open. The order line and its product come
     * with the page query; the package is only read for its id, which a proxy answers unloaded.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<PackageItemResponse> findPackageItems(Pageable pageable) {
        return packageItemResponseAssembler.toResponses(packageItemRepository.findAllWithProductBy(pageable));
    }

    /**
     * The package items still waiting for a package - what POST /packing/shipment/{id}/items can
     * put into one. Mapped like {@link #findPackageItems}.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<PackageItemResponse> findLoosePackageItems(Pageable pageable) {
        return packageItemResponseAssembler.toResponses(
                packageItemRepository.findAllWithProductByShipmentPackageIsNull(pageable));
    }

    /** One package item - the same row the lists show; unknown id is a 404. */
    @Override
    @Transactional(readOnly = true)
    public PackageItemResponse findPackageItem(Long packageItemId) {
        return packageItemRepository.findWithProductById(packageItemId)
                .map(packageItemResponseAssembler::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("PackageItem", "id", packageItemId));
    }

    /** One package with its items - the same row the list shows; unknown id is a 404. */
    @Override
    @Transactional(readOnly = true)
    public ShipmentPackageListDto findShipmentPackage(Long shipmentPackageId) {
        return shipmentPackageRepository.findWithItemsById(shipmentPackageId)
                .map(ShipmentPackageListDto::from)
                .orElseThrow(() -> new ResourceNotFoundException("ShipmentPackage", "id", shipmentPackageId));
    }
}
