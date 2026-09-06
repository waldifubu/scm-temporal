package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.common.PageResponse;
import com.supplychainmanagement.dto.fullfillment.AvailableDto;
import com.supplychainmanagement.dto.fullfillment.AvailableOrderItemDto;
import com.supplychainmanagement.dto.fullfillment.ProductionResultDto;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.service.FulfillmentService;
import com.supplychainmanagement.service.OrderHandlingService;
import com.supplychainmanagement.service.OrderService;
import com.supplychainmanagement.service.ProductionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping({"/api/{version}"})
public class ProductionController {

    private final OrderService orderService;
    private final FulfillmentService fulfillmentService;
    private final OrderHandlingService orderHandlingService;
    private final ProductionService productionService;

    @PostMapping(path = "/orders/{orderNo}/check", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER')")
    public AvailableDto checkOrder(@PathVariable Long orderNo,
                                   @AuthenticationPrincipal User authUser) {
        Order order = orderService.findByOrderNo(orderNo);

        List<AvailableOrderItemDto> items = productionService.checkItems(order);
        boolean allAvailable = items.stream().allMatch(AvailableOrderItemDto::available);

        String message = allAvailable
                ? "All items are available for order " + orderNo
                : "Some items are not available for order " + orderNo;

        return new AvailableDto(
                String.valueOf(orderNo),
                order.getStatus(),
                order.getOrderDate(),
                items,
                message,
                allAvailable
        );
    }

    @PostMapping(path = "/produce", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','MANAGER', 'WAREHOUSE')")
    public PageResponse<ProductionResultDto> produce() {
        Pageable pageable = PageRequest.of(0, 100, Sort.unsorted());
        Page<ProductionResultDto> productionPage = productionService.produce(pageable);
        return PageResponse.of(productionPage);
    }
}
