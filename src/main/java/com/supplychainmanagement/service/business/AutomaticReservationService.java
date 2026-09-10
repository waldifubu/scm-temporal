package com.supplychainmanagement.service.business;

import com.supplychainmanagement.dto.fullfillment.AvailableOrderItemDto;
import com.supplychainmanagement.dto.reservation.ReservationSummary;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.service.FulfillmentService;
import com.supplychainmanagement.service.OrderService;
import com.supplychainmanagement.service.ProductionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The scheduled counterpart to the fulfillment endpoints. {@code @EnableScheduling} is already
 * switched on by {@code AutomaticProductionService}, so a {@code @Scheduled} here is enough.
 */
@Slf4j
@Service
public class AutomaticReservationService {

    private final FulfillmentService fulfillmentService;
    private final OrderService orderService;
    private final ProductionService productionService;

    public AutomaticReservationService(FulfillmentService fulfillmentService,
                                       OrderService orderService,
                                       ProductionService productionService) {
        this.fulfillmentService = fulfillmentService;
        this.orderService = orderService;
        this.productionService = productionService;
    }

    /**
     * The acting user recorded in the audit trail for everything this service does on its own.
     * Resolving it is best effort - OrderHistory.user_id is nullable precisely because not every
     * status change has a person behind it.
     */
    private static final String SYSTEM_USER = "system";

    /**
     * Reports how far the orders that just came in could be fulfilled right now.
     * <p>
     * Read only: {@code checkItems} reserves nothing and writes nothing, which is why this one runs
     * while {@code tryToReserve} and {@code tryToRelease} stay switched off - they change state.
     * <p>
     * The value is the signal. An order in CREATED has not been acknowledged yet, and whether it is
     * coverable today is exactly what decides whether it can be confirmed - and with which delivery
     * date, see {@code OrderService.acknowledge}.
     */
    @Scheduled(initialDelay = 60, fixedDelay = 300, timeUnit = TimeUnit.SECONDS)
    public void checkCreatedOrders() {
        Pageable pageable = PageRequest.of(0, 100, Sort.unsorted());

        orderService.findAllByStatus(OrderStatus.CREATED, pageable).forEach(order -> {
            try {
                // findAllByStatus fetches orderItems and their products through an @EntityGraph -
                // without it checkItems would walk into a LazyInitializationException out here,
                // where no open-in-view session covers for a detached entity.
                List<AvailableOrderItemDto> items = productionService.checkItems(order);
                long covered = items.stream().filter(AvailableOrderItemDto::available).count();

                if (items.isEmpty()) {
                    log.warn("Order {} has no line left to check", order.getOrderNo());
                } else if (covered == items.size()) {
                    log.info("Order {}: all {} line(s) coverable - ready to acknowledge", order.getOrderNo(), items.size());
                    orderService.acknowledge(order, null);
                } else {
                    log.info("Order {}: {} of {} line(s) coverable, missing {}",
                            order.getOrderNo(), covered, items.size(), describeUncovered(items));
                }
            } catch (Exception e) {
                // One order must not stop the run - the next one picks this up again.
                log.error("Failed to check order {}", order.getOrderNo(), e);
            }
        });
    }

    private String describeUncovered(List<AvailableOrderItemDto> items) {
        return items.stream()
                .filter(item -> !item.available())
                .map(item -> "article " + item.articleNo() + " (requested " + item.orderQuantity() + ")")
                .collect(Collectors.joining(", "));
    }

    //    @Scheduled(initialDelay = 30, fixedDelay = 150, timeUnit = TimeUnit.SECONDS)
    public void tryToReserve() {
        Pageable pageable = PageRequest.of(0, 100, Sort.unsorted());
        var orders = orderService.findAllByStatus(OrderStatus.IN_FULFILLMENT, pageable);
        orders.forEach(order -> {
                    ReservationSummary reservationSummary = fulfillmentService.reserveItems(order, SYSTEM_USER);
                    log.info("Reserved for order {}: {}", order.getOrderNo(), reservationSummary.created());
                }
        );
    }

    /**
     * The counterpart to {@link #tryToReserve}: nothing on the request path ever looks at
     * {@code Reservation.expiresAt}, so an abandoned reservation keeps its stock booked forever -
     * invisible to every other order, and unreservable for its own because the per-SKU guard sees
     * it as still held. This sweep hands that stock back.
     * <p>
     * Per order, and only the expired reservations of it: an order can hold a fresh reservation
     * next to an expired one when its lines were reserved in separate calls.
     */
    //    @Scheduled(initialDelay = 60, fixedDelay = 300, timeUnit = TimeUnit.SECONDS)
    public void tryToRelease() {
        fulfillmentService.findExpiredReservationsByOrder().forEach((order, expired) -> {
            try {
                var released = fulfillmentService.releaseItems(order, expired, SYSTEM_USER);
                log.info("Released {} expired reservation(s) for order {}", released.size(), order.getOrderNo());
            } catch (Exception e) {
                // One order must not stop the sweep - the next run picks this one up again.
                log.error("Failed to release expired reservations for order {}", order.getOrderNo(), e);
            }
        });
    }
}
