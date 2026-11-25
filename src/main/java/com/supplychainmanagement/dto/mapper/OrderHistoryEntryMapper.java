package com.supplychainmanagement.dto.mapper;

import com.supplychainmanagement.dto.common.DisplayNames;
import com.supplychainmanagement.dto.order.OrderHistoryEntryDto;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderHistory;
import org.mapstruct.Mapper;

import java.util.Map;

/**
 * Written by hand, see {@link ComponentMapper}: the answer package is {@code @NullMarked}, and MapStruct refuses to
 * map an entity property that may be null (the status columns carry no NOT NULL) into a component that may not.
 * <p>
 * Two things a mapping by property name cannot give: the customer's name is a rule ({@link DisplayNames}), and the
 * acting user is a plain id column on the row, so the names come from <em>one</em> lookup for the whole page that
 * the caller passes in - never one query per row.
 */
@Mapper(componentModel = "spring")
public interface OrderHistoryEntryMapper {

    /**
     * @param names user id to display name, for the ids of the page this row is on
     */
    default OrderHistoryEntryDto mapToDto(OrderHistory row, Map<Long, String> names) {
        if (row == null) {
            return null;
        }
        Order order = row.getOrder();
        var customer = order.getCustomer();
        Long actor = row.getUserId();
        return new OrderHistoryEntryDto(
                row.getId(),
                order.getOrderNo(),
                customer == null ? null : customer.getId(),
                customer == null ? null : DisplayNames.of(customer),
                row.getPreviousStatus(),
                row.getNewStatus(),
                row.getChangedAt(),
                actor,
                actor == null ? DisplayNames.SYSTEM : names.get(actor));
    }
}
