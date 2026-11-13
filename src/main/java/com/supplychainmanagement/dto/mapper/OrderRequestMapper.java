package com.supplychainmanagement.dto.mapper;

import com.supplychainmanagement.dto.order.OrderRequestDto;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.users.User;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * From what a client sent to the {@code Order} the service builds on: only what the request carries,
 * nothing a client could have injected ({@code id}, {@code orderNo}, {@code status}, {@code total}).
 * <p>
 * Written by hand and not with MapStruct: the entity has a dozen fields the server decides, which a
 * generated mapping would have to exclude one by one, and this is the whole of it. The request stays
 * ignorant of entities - it used to do this itself ({@code OrderRequestDto.toOrder()}).
 */
@Component
public class OrderRequestMapper {

    public Order mapToEntity(OrderRequestDto request) {
        Order order = new Order();
        order.setDueDate(request.dueDate());

        if (request.customer() != null) {
            User customer = new User();
            customer.setId(request.customer().id());
            order.setCustomer(customer);
        }

        if (request.orderItems() != null) {
            Set<OrderItem> lines = new LinkedHashSet<>();
            for (OrderRequestDto.Line line : request.orderItems()) {
                OrderItem item = new OrderItem();
                item.setQuantity(line.quantity());
                Product product = new Product();
                product.setArticleNo(line.product().articleNo());
                item.setProduct(product);
                lines.add(item);
            }
            order.setOrderItems(lines);
        }
        return order;
    }
}
