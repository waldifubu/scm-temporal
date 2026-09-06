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
import java.util.Objects;

@Service
@AllArgsConstructor
public class PackingServiceImpl implements PackingService {

    private final ReservationRepository reservationRepository;
    private final OrderItemRepository orderItemRepository;
    private final ShipmentPackageRepository shipmentPackageRepository;

    @Override
    public PickingOrderDto packingReservationByIdComplete(Long reservationId) {
        var reservation = reservationRepository.findByIdAndStatus(reservationId, ReservationStatus.CONSUMED).orElseThrow(() -> new ResourceNotFoundException("Reservation", "id not found", reservationId));

        var orderItem = reservation.getOrderItem();
        if (orderItem.getFulfillmentStatus() == FulfillmentStatus.PACKED) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Order item is already in PACKED status, cannot move to PACKED");
        }
        if (orderItem.getFulfillmentStatus() != FulfillmentStatus.PACKING) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Order item is not in PACKING status, cannot move to PACKED");
        }
        orderItem.setFulfillmentStatus(FulfillmentStatus.PACKED);
        orderItemRepository.save(orderItem);

//        return toDto(findOrder(reservation.getOrderId()), reservation);
        return null;
    }


    @Override
    @Transactional
    public ShipmentPackage createPackage(Long orderNo, CreatePackageRequest request) {
        ShipmentPackage shipmentPackage = new ShipmentPackage();

        shipmentPackage.setStatus(PackageStatus.OPEN);
        shipmentPackage.setWeight(request.weight());
        shipmentPackage.setLength(request.length());
        shipmentPackage.setWidth(request.width());
        shipmentPackage.setHeight(request.height());
        shipmentPackage.setPackageNumber(request.packageNumber() != null ? request.packageNumber() : generatePackageNumber());

        for (PackItem packItem : request.items()) {
            OrderItem orderItem = orderItemRepository.findById(packItem.orderItemId()).orElseThrow(
                    () -> new IllegalArgumentException("OrderItem not found: " + packItem.orderItemId())
            );

            // ----------------------------------------------------
            // 1. Check: OrderItem belongs to the correct orderNo
            // ----------------------------------------------------
            if (!Objects.equals(orderItem.getOrder().getOrderNo(), orderNo)) {
                throw new IllegalStateException("OrderItem does not belong to order " + orderNo);
            }

            // ----------------------------------------------------
            // 2. Only allow packing if the OrderItem is in PICKED or PACKING status
            // ----------------------------------------------------
            FulfillmentStatus status = orderItem.getFulfillmentStatus();

            if (status != FulfillmentStatus.PICKED && status != FulfillmentStatus.PACKING) {
//                throw new IllegalStateException("OrderItem " + orderItem.getId() + " is not ready for packing, maybe already packed");
                continue;
            }

            // ----------------------------------------------------
            // 3. Check: requested qty is valid
            // ----------------------------------------------------
            int requestedQuantity = packItem.qty();

            if (requestedQuantity <= 0) {
                throw new IllegalArgumentException("Quantity must be greater than zero");
            }

            if (requestedQuantity > orderItem.getQuantity()) {
                throw new IllegalStateException("Cannot pack more than ordered qty for OrderItem " + orderItem.getId()+ ". Requested qty: " + requestedQuantity + ", ordered qty: " + orderItem.getQuantity());
            }

            // ----------------------------------------------------
            // 4. Check: already packed qty
            // ----------------------------------------------------
            int alreadyPacked = shipmentPackageRepository.sumQuantityByOrderItemId(orderItem.getId());

            // ----------------------------------------------------
            // 5. Check: Number of items to pack does not exceed ordered qty
            // alreadyPacked + requested <= ordered
            // ----------------------------------------------------
            int newPackedTotal = alreadyPacked + requestedQuantity;

            if (newPackedTotal > orderItem.getQuantity()) {
                throw new IllegalStateException(
                        "Cannot pack more than ordered qty. "
                                + "Ordered: " + orderItem.getQuantity()
                                + ", already packed: " + alreadyPacked
                                + ", requested: "
                                + requestedQuantity);
            }

            // ----------------------------------------------------
            // 6. Create PackageItem
            // ----------------------------------------------------
            PackageItem packageItem = new PackageItem();

            packageItem.setShipmentPackage(shipmentPackage);
            packageItem.setOrderItem(orderItem);
            packageItem.setQuantity(requestedQuantity);
            shipmentPackage.getItems().add(packageItem);

            // ----------------------------------------------------
            // 7. Update FulfillmentStatus
            // ----------------------------------------------------
            if (newPackedTotal == orderItem.getQuantity()) {
                orderItem.setFulfillmentStatus(FulfillmentStatus.PACKED);
            } else if (orderItem.getFulfillmentStatus() != FulfillmentStatus.PACKING) {
                orderItem.setFulfillmentStatus(FulfillmentStatus.PACKING);
            }

        } // end for each packItem


        // ----------------------------------------------------
        // 8. Check: ShipmentPackage has at least one item
        // ----------------------------------------------------
        if (shipmentPackage.getItems().isEmpty()) {
            throw new IllegalStateException("No valid items to pack for order " + orderNo);
        }

        // ----------------------------------------------------
        // 9. Save ShipmentPackage
        // ----------------------------------------------------
        return shipmentPackageRepository.save(shipmentPackage);
    }

    private String generatePackageNumber() {
        LocalDateTime now = LocalDateTime.now();
        return "PKG-" + now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")) + "-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
