package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.fullfillment.AvailableOrderItemDto;
import com.supplychainmanagement.dto.fullfillment.ProductionResultDto;
import com.supplychainmanagement.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface ProductionService {
    Page<ProductionResultDto> produce(Pageable pageable);

    List<AvailableOrderItemDto> checkItems(Order order);
}
