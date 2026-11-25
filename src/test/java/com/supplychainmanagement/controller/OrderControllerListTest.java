package com.supplychainmanagement.controller;

import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * The page {@code GET /orders} asks the service for. The id as the tie-break is held here and not by a context
 * test: a small table does not reorder its ties on its own.
 */
@ExtendWith(MockitoExtension.class)
class OrderControllerListTest {

    @Mock
    private OrderService orderService;
    @InjectMocks
    private OrderController controller;

    @BeforeEach
    void answerNothing() {
        lenient().when(orderService.findAllByUserAndStatus(any(), any(), any())).thenReturn(Page.empty());
    }

    @Test
    void settlesTiesByTheId() {
        controller.list(1, 10, "CREATED", "status", "DESC", null);

        verify(orderService).findAllByUserAndStatus(any(), eq(OrderStatus.CREATED),
                eq(PageRequest.of(1, 10, Sort.by(Sort.Order.desc("status"), Sort.Order.asc("id")))));
    }
}
