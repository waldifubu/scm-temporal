package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.fullfillment.AvailableOrderItemDto;
import com.supplychainmanagement.dto.fullfillment.ProductionResultDto;
import com.supplychainmanagement.dto.reservation.ReservationSummary;
import com.supplychainmanagement.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface FulfillmentService {

    ReservationSummary reserveItems(Order order, String username);

    void releaseItems(Order order);
}
