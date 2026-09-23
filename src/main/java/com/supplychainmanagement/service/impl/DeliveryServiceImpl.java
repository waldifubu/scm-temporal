package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.shipping.DeliveryResponse;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.Shipment;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
import com.supplychainmanagement.model.enums.ShipmentStatus;
import com.supplychainmanagement.repository.OrderRepository;
import com.supplychainmanagement.repository.ShipmentPackageRepository;
import com.supplychainmanagement.repository.ShipmentRepository;
import com.supplychainmanagement.service.DeliveryService;
import com.supplychainmanagement.service.OrderProgressService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DeliveryServiceImpl implements DeliveryService {

    private final ShipmentRepository shipmentRepository;
    private final ShipmentPackageRepository shipmentPackageRepository;
    private final OrderRepository orderRepository;
    private final OrderProgressService orderProgress;

    @Override
    @Transactional
    public DeliveryResponse acceptShipment(Long shipmentId, Long userId) {
        return advance(shipmentId, EnumSet.of(ShipmentStatus.READY, ShipmentStatus.DISPATCH_REQUESTED),
                ShipmentStatus.ACCEPTED, OrderStatus.READY_FOR_DISPATCH, userId);
    }

    @Override
    @Transactional
    public DeliveryResponse shipmentInTransit(Long shipmentId, Long userId) {
        return advance(shipmentId, EnumSet.of(ShipmentStatus.ACCEPTED),
                ShipmentStatus.IN_TRANSIT, OrderStatus.IN_TRANSIT, userId);
    }

    @Override
    @Transactional
    public DeliveryResponse shipmentDelivered(Long shipmentId, Long userId) {
        return advance(shipmentId, EnumSet.of(ShipmentStatus.IN_TRANSIT),
                ShipmentStatus.DELIVERED, OrderStatus.DELIVERED, userId);
    }

    /**
     * One step of the carrier's part of the process: the shipment moves on, and every order it
     * carries follows. Locked with findForUpdateById - two distributors reporting on the same
     * shipment would otherwise both pass the status check.
     *
     * @param allowedFrom the statuses the step may start from; anything else is a 409
     * @param orderStatus what the orders of this shipment reach with it
     */
    private DeliveryResponse advance(Long shipmentId, Set<ShipmentStatus> allowedFrom,
                                     ShipmentStatus target, OrderStatus orderStatus, Long userId) {
        Shipment shipment = shipmentRepository.findForUpdateById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment", "id", shipmentId));

        if (!allowedFrom.contains(shipment.getStatus())) {
            throw new APIException(HttpStatus.CONFLICT, "Shipment " + shipmentId + " is "
                    + shipment.getStatus() + ", only " + allowedFrom + " can be moved to " + target);
        }

        shipment.setStatus(target);
        if (target == ShipmentStatus.IN_TRANSIT) {
            shipment.setShippedAt(LocalDateTime.now());
            dispatchPackages(shipment);
        }
        if (target == ShipmentStatus.DELIVERED) {
            shipment.setDeliveredAt(LocalDateTime.now());
        }
        shipmentRepository.save(shipment);

        // Which of the orders really move is OrderProgressService's rule, not the carrier's.
        orderProgress.advance(orderIdsOf(shipmentId), orderStatus, userId);

        // The carrier's own view - no package contents, see DeliveryResponse.
        return DeliveryResponse.from(shipment);
    }

    /**
     * The packages leave the house with the shipment: PACKED to DISPATCHED. One that is already
     * DISPATCHED stays as it is, and an OPEN one cannot occur - the shipment only took PACKED
     * packages, and from READY on nothing goes in or out any more.
     */
    private void dispatchPackages(Shipment shipment) {
        List<ShipmentPackage> dispatched = shipment.getPackages().stream()
                .filter(shipmentPackage -> shipmentPackage.getShipmentPackageStatus() == ShipmentPackageStatus.PACKED)
                .toList();
        dispatched.forEach(shipmentPackage -> shipmentPackage.setShipmentPackageStatus(ShipmentPackageStatus.DISPATCHED));
        shipmentPackageRepository.saveAll(dispatched);
    }

    /** The orders this shipment carries - which of them actually move is OrderProgressService's call. */
    private List<Long> orderIdsOf(Long shipmentId) {
        return orderRepository.findByShipmentId(shipmentId).stream().map(Order::getId).toList();
    }
}
