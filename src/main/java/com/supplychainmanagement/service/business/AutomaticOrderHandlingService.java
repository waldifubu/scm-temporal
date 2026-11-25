package com.supplychainmanagement.service.business;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.service.FulfillmentService;
import com.supplychainmanagement.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
@Service
@EnableScheduling
public class AutomaticOrderHandlingService {

    private final static Long autocompleteAfterDays = 2L; // Set

    private final static String SYSTEM_USER = "system";

    private final OrderService orderService;
    private final FulfillmentService fulfillmentService;

    public AutomaticOrderHandlingService(OrderService orderService, FulfillmentService fulfillmentService) {
        this.orderService = orderService;
        this.fulfillmentService = fulfillmentService;
    }

    /**
     * Rejecting an order writes its status only; the stock it holds is handed back here and its lines end
     * CANCELLED - also where nothing is held (any more). The order stays REJECTED.
     */
    @Scheduled(initialDelay = 90, fixedDelay = 150, timeUnit = TimeUnit.SECONDS)
    public void releaseRejectedOrders() {
        fulfillmentService.findRejectedOrdersToClose().forEach(order -> {
            try {
                var released = fulfillmentService.releaseRejectedOrder(order, SYSTEM_USER);
                log.info("Released {} reservation(s) of rejected order {}", released.size(), order.getOrderNo());
            } catch (Exception e) {
                // One order must not stop the run - the next one picks this up again.
                log.error("Failed to release the reservations of rejected order {}", order.getOrderNo(), e);
            }
        });
    }

    /** Completes the orders that have been DELIVERED or CANCELLED and unchanged for two days. */
    @Scheduled(initialDelay = 120, fixedDelay = 150, timeUnit = TimeUnit.SECONDS)
    public void completeOrder() {
        completeUnchanged(OrderStatus.DELIVERED, order -> orderService.complete(order, null));
        completeUnchanged(OrderStatus.CANCELLED, orderService::completeCancelled);
    }

    private void completeUnchanged(OrderStatus status, Consumer<Order> completion) {
        Pageable pageable = PageRequest.of(0, 999, Sort.unsorted());
        orderService.findAllByStatus(status, pageable).forEach(order -> {
            try {
                if (order.getUpdated().plusDays(autocompleteAfterDays).isAfter(LocalDateTime.now())) {
                    return;
                }
                completion.accept(order);
                log.info("Order with ID {} has been completed.", order.getId());
            } catch (Exception e) {
                // One order must not stop the run - the next one picks this up again.
                log.error("Failed to complete order {}", order.getOrderNo(), e);
            }
        });
    }
}
