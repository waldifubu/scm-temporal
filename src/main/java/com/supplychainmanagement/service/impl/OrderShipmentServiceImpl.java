package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.common.DisplayNames;
import com.supplychainmanagement.dto.order.OrderShipmentResponse;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.Shipment;
import com.supplychainmanagement.repository.ShipmentRepository;
import com.supplychainmanagement.service.OrderService;
import com.supplychainmanagement.service.OrderShipmentService;
import com.supplychainmanagement.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderShipmentServiceImpl implements OrderShipmentService {

    private final OrderService orderService;
    private final ShipmentRepository shipmentRepository;
    private final RoleService roleService;

    /**
     * The order is fetched through {@code findByOrderNoForUser}, the call {@code GET /orders/{orderNo}} makes, so
     * the rule on who may see an order is not written a second time. Two queries however many shipments there are:
     * the shipments, then the package numbers of all of them.
     */
    @Override
    public List<OrderShipmentResponse> findShipments(Long orderNo,
                                                     org.springframework.security.core.userdetails.User authUser) {
        Order order = orderService.findByOrderNoForUser(orderNo, authUser);
        List<Shipment> shipments = shipmentRepository.findByOrderId(order.getId());
        if (shipments.isEmpty()) {
            return List.of();
        }

        Map<Long, List<String>> packageNumbers = new HashMap<>();
        for (Object[] row : shipmentRepository.findPackageNumbersByOrderId(order.getId())) {
            if (row[1] != null) {
                packageNumbers.computeIfAbsent((Long) row[0], id -> new ArrayList<>()).add((String) row[1]);
            }
        }

        // The carrier is the house's business; a customer is not told who it is.
        boolean showCarrier = roleService.canReadAnyOrder(authUser);
        return shipments.stream()
                .map(shipment -> OrderShipmentResponse.of(
                        shipment,
                        showCarrier && shipment.getDistributor() != null ? DisplayNames.of(shipment.getDistributor()) : null,
                        packageNumbers.getOrDefault(shipment.getId(), List.of())))
                .toList();
    }
}
