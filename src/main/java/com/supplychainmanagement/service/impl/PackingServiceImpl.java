package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.picking.PickingOrderDto;
import com.supplychainmanagement.dto.shipping.CreatePackageRequest;
import com.supplychainmanagement.dto.shipping.PackItem;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.PackageItem;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.PackageStatus;
import com.supplychainmanagement.model.enums.PackageType;
import com.supplychainmanagement.model.enums.ReservationStatus;
import com.supplychainmanagement.repository.OrderItemRepository;
import com.supplychainmanagement.repository.ReservationRepository;
import com.supplychainmanagement.repository.ShipmentPackageRepository;
import com.supplychainmanagement.service.PackingService;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Objects;

@Service
@AllArgsConstructor
public class PackingServiceImpl implements PackingService {

    private final ReservationRepository reservationRepository;
    private final OrderItemRepository orderItemRepository;
    private final ShipmentPackageRepository shipmentPackageRepository;

    @Override
    @Transactional
    public PickingOrderDto packingReservationByIdComplete(Long reservationId) {
        var reservation = reservationRepository.findByIdAndStatus(reservationId, ReservationStatus.CONSUMED)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", "id not found", reservationId));

        var orderItem = reservation.getOrderItem();

        if (orderItem.getFulfillmentStatus() == FulfillmentStatus.READY_FOR_DISPATCH) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Order item is already in READY_FOR_DISPATCH status, cannot move to PACKED");
        }
        if (orderItem.getFulfillmentStatus() == FulfillmentStatus.PACKED) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Order item is already in PACKED status, cannot move to PACKED");
        }
        if (orderItem.getFulfillmentStatus() != FulfillmentStatus.PACKING) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Order item is not in PACKING status, cannot move to PACKED");
        }

        orderItem.setFulfillmentStatus(FulfillmentStatus.PACKED);
        orderItemRepository.save(orderItem);

        // Same row shape the picking list and every other fulfillment action answer with, and
        // mapped in here while the transaction is open - orderItem.product and reservation.storehouse
        // are LAZY.
        return PickingOrderDto.of(orderItem.getOrder(), reservation);
    }


    @Override
    @Transactional
    public ShipmentPackage createPackage(Long orderNo, CreatePackageRequest request) {
        ShipmentPackage shipmentPackage = new ShipmentPackage();

        shipmentPackage.setStatus(PackageStatus.OPEN);
        // Without a type there is no tare weight to add, so an unstated one counts as OTHER rather
        // than staying null - see PackageType.
        shipmentPackage.setPackageType(request.packageType() != null ? request.packageType() : PackageType.OTHER);
        // Weight is optional
        shipmentPackage.setWeight(request.weight());
        shipmentPackage.setLength(request.length());
        shipmentPackage.setWidth(request.width());
        shipmentPackage.setHeight(request.height());
        shipmentPackage.setPackageNumber(request.packageNumber() != null ? request.packageNumber() : generatePackageNumber());

        // Processed in a stable order because packLine locks each line: two concurrent requests
        // touching the same two lines in opposite orders would otherwise deadlock each other.
        request.items().stream()
                .sorted(Comparator.comparing(PackItem::orderItemId))
                .forEach(packItem -> packLine(shipmentPackage, packItem, orderNo));

        // Every line was skipped, so there is nothing to ship - an empty package is not a result.
        if (shipmentPackage.getItems().isEmpty()) {
            throw new IllegalStateException("No valid items to pack for order " + orderNo);
        }

        return shipmentPackageRepository.save(shipmentPackage);
    }

    /**
     * Packs one order line into the package under construction.
     * <p>
     * A line that is neither PICKED nor PACKING is skipped rather than rejected: a caller may hand
     * in a whole order and let the ones that are ready be packed. The emptiness check at the end of
     * {@link #createPackage} is what turns "nothing was ready at all" into an error.
     */
    private void packLine(ShipmentPackage shipmentPackage, PackItem packItem, Long orderNo) {
        // Locked, not just read: see OrderItemRepository.findForUpdateById. The lock is held until
        // createPackage commits, so a concurrent packer waits and then sees the updated total.
        OrderItem orderItem = orderItemRepository.findForUpdateById(packItem.orderItemId()).orElseThrow(
                () -> new IllegalArgumentException("OrderItem not found: " + packItem.orderItemId())
        );

        if (!Objects.equals(orderItem.getOrder().getOrderNo(), orderNo)) {
            throw new IllegalStateException("OrderItem does not belong to order " + orderNo);
        }

        FulfillmentStatus status = orderItem.getFulfillmentStatus();
        if (status != FulfillmentStatus.PICKED && status != FulfillmentStatus.PACKING) {
            return;
        }

        int requestedQuantity = packItem.qty();
        if (requestedQuantity <= 0) {
            throw new IllegalArgumentException("Quantity must be greater than zero");
        }

        // Measured against what earlier packages of this line already hold, not against the ordered
        // quantity alone - the latter would let two half packages add up to more than was ordered.
        // The package being built counts too: its items are not written yet, so the query cannot see
        // them, and the same line listed twice in one request would otherwise pass twice.
        int alreadyPacked = shipmentPackageRepository.sumQuantityByOrderItemId(orderItem.getId())
                + packedInThisPackage(shipmentPackage, orderItem);
        int newPackedTotal = alreadyPacked + requestedQuantity;
        if (newPackedTotal > orderItem.getQuantity()) {
            throw new IllegalStateException(
                    "Cannot pack more than ordered qty for OrderItem " + orderItem.getId()
                            + ". Ordered: " + orderItem.getQuantity()
                            + ", already packed: " + alreadyPacked
                            + ", requested: " + requestedQuantity);
        }

        PackageItem packageItem = new PackageItem();
        packageItem.setShipmentPackage(shipmentPackage);
        packageItem.setOrderItem(orderItem);
        packageItem.setQuantity(requestedQuantity);
        shipmentPackage.getItems().add(packageItem);

        // PACKED only once every ordered unit sits in a package, PACKING while some are still open.
        // Assigned unconditionally: writing the status a line already has is a no-op, and guarding
        // against it only risked skipping whatever else the branch did.
        orderItem.setFulfillmentStatus(newPackedTotal == orderItem.getQuantity()
                ? FulfillmentStatus.PACKED
                : FulfillmentStatus.PACKING);
    }

    private int packedInThisPackage(ShipmentPackage shipmentPackage, OrderItem orderItem) {
        return shipmentPackage.getItems().stream()
                .filter(item -> Objects.equals(item.getOrderItem().getId(), orderItem.getId()))
                .mapToInt(PackageItem::getQuantity)
                .sum();
    }

    private String generatePackageNumber() {
        LocalDateTime now = LocalDateTime.now();
        return "PKG-" + now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")) + "-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
