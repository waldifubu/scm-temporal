package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.shipping.DeliveryResponse;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.Shipment;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.entity.users.Distributor;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.model.enums.ShipmentStatus;
import com.supplychainmanagement.repository.OrderRepository;
import com.supplychainmanagement.repository.ShipmentPackageRepository;
import com.supplychainmanagement.repository.ShipmentRepository;
import com.supplychainmanagement.service.RoleService;
import com.supplychainmanagement.service.DeliveryService;
import com.supplychainmanagement.service.OrderProgressService;
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
import java.util.Objects;
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
    private final RoleService roleService;

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
        // DISPATCH_REQUESTED only, not READY: that status is set by assignDistributor and nothing
        // else, so requiring it makes the handover a step that has to happen. Before this, a carrier
        // could take on any ready shipment, nobody had to be assigned, and the distributor's work
        // list - which filters on distributor_id - stayed empty for everyone.
        return advance(shipmentId, EnumSet.of(ShipmentStatus.DISPATCH_REQUESTED),
                ShipmentStatus.ACCEPTED, userId);
    }

    @Override
    @Transactional
    public DeliveryResponse shipmentInTransit(Long shipmentId, Long userId) {
        return advance(shipmentId, EnumSet.of(ShipmentStatus.ACCEPTED),
                ShipmentStatus.IN_TRANSIT, userId);
    }

    @Override
    @Transactional
    public DeliveryResponse shipmentDelivered(Long shipmentId, Long userId) {
        return advance(shipmentId, EnumSet.of(ShipmentStatus.IN_TRANSIT),
                ShipmentStatus.DELIVERED, userId);
    }

    @Override
    @Transactional
    public DeliveryResponse assignTrackNumber(Long shipmentId, String trackingNumber, Long userId) {
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
        if(!Objects.equals(shipment.getDistributor().getId(), userId) && !roleService.isAdmin(userId)) {
            throw new APIException(HttpStatus.FORBIDDEN, "Shipment " + shipmentId + " is assigned to another distributor");
        }
        shipment.setTrackingNumber(trackingNumber);

        shipmentRepository.save(shipment);
        return DeliveryResponse.from(shipment);
    }

    /**
     * One step of the carrier's part of the process: the shipment moves on, and every order it
     * carries follows. Locked with findForUpdateById - two distributors reporting on the same
     * shipment would otherwise both pass the status check.
     *
     * @param allowedFrom the statuses the step may start from; anything else is a 409
     */
    private DeliveryResponse advance(Long shipmentId, Set<ShipmentStatus> allowedFrom,
                                     ShipmentStatus target, Long userId) {
        Shipment shipment = shipmentRepository.findForUpdateById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment", "id", shipmentId));

        // Asked before the status: a carrier poking at a shipment that is not theirs learns nothing
        // about where it stands.
        requireReportingDistributor(shipment, userId);

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

        // No order status is named here on purpose. This shipment is one of possibly several an
        // order travels in, so what it reports says nothing about the order as a whole - the carrier
        // only says which orders were touched, OrderProgressService works out where they stand.
        orderProgress.recompute(orderIdsOf(shipmentId), userId);

        // The carrier's own view - no package contents, see DeliveryResponse.
        return DeliveryResponse.from(shipment);
    }

    /**
     * Only the distributor the shipment was assigned to reports on it - any other carrier is a 403.
     * ADMIN is exempt, as the role that has to be able to correct things.
     * <p>
     * The missing assignment is a 409 and not reachable through {@code accept}, which needs
     * DISPATCH_REQUESTED and therefore an assignment. The later steps check it all the same rather
     * than trusting that chain to hold.
     */
    private void requireReportingDistributor(Shipment shipment, Long userId) {
        Distributor assigned = shipment.getDistributor();
        if (assigned == null) {
            throw new APIException(HttpStatus.CONFLICT, "Shipment " + shipment.getId()
                    + " has no distributor assigned - PUT /shipments/{id}/distributor/{id} comes first");
        }

        if (Objects.equals(assigned.getId(), userId) || roleService.isAdmin(userId)) {
            return;
        }

        throw new APIException(HttpStatus.FORBIDDEN,
                "Shipment " + shipment.getId() + " is assigned to another distributor");
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
