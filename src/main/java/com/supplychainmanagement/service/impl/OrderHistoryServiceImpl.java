package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.common.DisplayNames;
import com.supplychainmanagement.dto.order.OrderHistoryResponse;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderHistory;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.repository.OrderHistoryRepository;
import com.supplychainmanagement.repository.UserRepository;
import com.supplychainmanagement.service.OrderHistoryService;
import com.supplychainmanagement.service.OrderService;
import com.supplychainmanagement.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderHistoryServiceImpl implements OrderHistoryService {

    private final OrderService orderService;
    private final OrderHistoryRepository orderHistoryRepository;
    private final UserRepository userRepository;
    private final RoleService roleService;

    /**
     * The order is fetched through {@code findByOrderNoForUser}, the same call {@code GET /orders/{orderNo}}
     * makes, so the rule that decides who may see an order is not written a second time here - and
     * cannot drift from it. Everything after that line runs only for a caller who may see the order.
     * <p>
     * Two queries however many rows there are: the rows, then the names of everyone they mention.
     * The acting user is a plain id column on the row, not an association, so there is nothing to
     * fetch-join and a lookup per row would be one query each.
     */
    @Override
    public List<OrderHistoryResponse> findHistory(Long orderNo,
                                                  org.springframework.security.core.userdetails.User authUser) {
        Order order = orderService.findByOrderNoForUser(orderNo, authUser);
        List<OrderHistory> rows = orderHistoryRepository.findByOrderIdOrderByChangedAtDescIdDesc(order.getId());

        // Staff see who did it, a customer does not - and a customer's lookup is skipped altogether.
        boolean showActor = roleService.canReadAnyOrder(authUser);
        Map<Long, String> names = showActor ? namesOfActors(rows) : Map.of();

        return rows.stream().map(row -> OrderHistoryResponse.of(row, showActor, names)).toList();
    }

    /** One query for every user the rows name; an id nobody answers to simply has no name. */
    private Map<Long, String> namesOfActors(List<OrderHistory> rows) {
        Set<Long> ids = rows.stream()
                .map(OrderHistory::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }

        return userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, DisplayNames::of));
    }
}
