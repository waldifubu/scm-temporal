package com.supplychainmanagement.service.business;

import com.supplychainmanagement.dto.fullfillment.ProductionResultDto;
import com.supplychainmanagement.dto.reservation.ReservationSummary;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.service.FullfillmentService;
import com.supplychainmanagement.service.OrderService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.LocalDateTime;

@Slf4j
@Configuration
@EnableScheduling
@AllArgsConstructor
public class AutomaticProductionService {

    final FullfillmentService fullfillmentService;
    private final OrderService orderService;

    //    @Scheduled(initialDelay = 30, fixedDelay = 150, timeUnit = TimeUnit.SECONDS)
    public void assemble() {
        Pageable pageable = PageRequest.of(0, 100, Sort.unsorted());
        Page<ProductionResultDto> pageProducts = fullfillmentService.produce(pageable);
        log.info("Tried assembling  {}", LocalDateTime.now());
        log.info("Products: {}", pageProducts.getContent());
    }

    public void tryToReserve() {
        Pageable pageable = PageRequest.of(0, 100, Sort.unsorted());
        var orders = orderService.findAllByStatus(OrderStatus.IN_FULFILLMENT, pageable);
        orders.forEach(order -> {
                    ReservationSummary reservationSummary = fullfillmentService.reserveItems(order, "system");
                    log.info("Reserved for order {}: {}", order.getOrderNo(), reservationSummary.created());
                }
        );
    }
}
