package com.supplychainmanagement.service.business;

import com.supplychainmanagement.dto.fullfillment.ProductionResultDto;
import com.supplychainmanagement.dto.reservation.ReservationSummary;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.service.FulfillmentService;
import com.supplychainmanagement.service.OrderService;
import com.supplychainmanagement.service.ProductionService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@EnableScheduling
public class AutomaticProductionService {

    private final ProductionService productionService;

    public AutomaticProductionService(ProductionService productionService) {
        this.productionService = productionService;
    }

    //    @Scheduled(initialDelay = 30, fixedDelay = 150, timeUnit = TimeUnit.SECONDS)
    public void assemble() {
        Pageable pageable = PageRequest.of(0, 100, Sort.unsorted());
        Page<ProductionResultDto> pageProducts = productionService.produce(pageable);
        log.info("Tried assembling  {}", LocalDateTime.now());
        log.info("Products: {}", pageProducts.getContent());
    }
}
