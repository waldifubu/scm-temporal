package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.shipping.CancelShipmentRequest;
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
import com.supplychainmanagement.service.ShipmentService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DeliveryServiceImpl implements DeliveryService {

    private final ShipmentRepository shipmentRepository;
    private final ShipmentPackageRepository shipmentPackageRepository;
    private final OrderRepository orderRepository;
    private final OrderProgressService orderProgress;
    private final ShipmentService shipmentService;

    @Override
    @Transactional(readOnly = true)
    public Page<DeliveryResponse> findShipmentsForDistributor(Long distributorId, ShipmentStatus status,
                                                              Pageable pageable) {
        Page<Shipment> page = status == null
                ? shipmentRepository.findAllWithCustomerByDistributorId(distributorId, pageable)
                : shipmentRepository.findAllWithCustomerByDistributorIdAndStatus(distributorId, status, pageable);
        if (page.isEmpty()) {
            return page.map(DeliveryResponse::from);
        }

        List<Long> ids = page.getContent().stream().map(Shipment::getId).toList();
        Map<Long, Shipment> withPackages = shipmentRepository.findWithPackagesByIdIn(ids).stream()
                .collect(Collectors.toMap(Shipment::getId, Function.identity(), (first, same) -> first));

        // The page decides order and totals; the second query only supplies the packages.
        return page.map(shipment -> DeliveryResponse.from(withPackages.getOrDefault(shipment.getId(), shipment)));
    }

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

    @Override
    @Transactional
    public DeliveryResponse assignTrackNumber(Long shipmentId, String trackingNumber) {
        Shipment shipment = shipmentRepository.findForUpdateById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment", "id", shipmentId));
        if(trackingNumber == null || trackingNumber.isBlank()) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Tracking number cannot be null or blank");
        }
        if(trackingNumber.length() > 70) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Tracking number cannot exceed 70 characters");
        }
        if(shipment.getStatus() == ShipmentStatus.IN_TRANSIT || shipment.getStatus() == ShipmentStatus.DELIVERED) {
            throw new APIException(HttpStatus.CONFLICT, "Cannot assign tracking number to shipment in status " + shipment.getStatus());
        }
        shipment.setTrackingNumber(trackingNumber);

        shipmentRepository.save(shipment);
        return DeliveryResponse.from(shipment);
    }

    @Override
    @Transactional
    public DeliveryResponse cancelShipment(Long shipmentId, CancelShipmentRequest request, Long userId) {
        // Not reimplemented here: which statuses may still be called off, and what has to be wound
        // back with it - packages loose again, order lines and orders a step back - is the shipment's
        // own business and already lives on the planning side. A second copy would drift from it.
        shipmentService.cancelShipment(shipmentId, request, userId);

        // Read back rather than mapped from the planning answer: the carrier gets its own view. The
        // shipment is in this transaction's persistence context by now, so findById costs no query,
        // and its packages were detached above - the answer reports none, like the planning one.
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment", "id", shipmentId));
        return DeliveryResponse.from(shipment);
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
