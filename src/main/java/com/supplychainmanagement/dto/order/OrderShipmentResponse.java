package com.supplychainmanagement.dto.order;

import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.supplychainmanagement.entity.Shipment;
import com.supplychainmanagement.model.enums.ShipmentStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One shipment an order travels in: where it stands, where it goes, when it left and arrived, and which of the
 * order's packages are in it. Not {@code ShipmentResponse}: that one names the customer and carries every package
 * with its contents, which is the planning side's view.
 * <p>
 * <strong>{@code distributorName} is for staff only.</strong> A customer is told when and where something is on
 * its way, not which carrier the house chose; for them the key is absent. It is also absent for a shipment nobody
 * has taken on yet. Absent values are left out of the JSON, except the dates, whose null says "not yet".
 */
public record OrderShipmentResponse(
        Long id,
        ShipmentStatus status,
        @JsonInclude(JsonInclude.Include.NON_NULL) String trackingNumber,
        @JsonInclude(JsonInclude.Include.NON_NULL) String shippingAddress,
        @Nullable LocalDateTime shippedAt,
        @Nullable LocalDateTime deliveredAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) String distributorName,
        List<String> packageNumbers
) {

    public static OrderShipmentResponse of(Shipment shipment, String distributorName, List<String> packageNumbers) {
        return new OrderShipmentResponse(shipment.getId(), shipment.getStatus(), shipment.getTrackingNumber(),
                shipment.getShippingAddress(), shipment.getShippedAt(), shipment.getDeliveredAt(),
                distributorName, packageNumbers);
    }
}
