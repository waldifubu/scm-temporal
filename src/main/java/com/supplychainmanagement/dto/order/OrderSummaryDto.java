package com.supplychainmanagement.dto.order;

import com.supplychainmanagement.model.enums.OrderStatus;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDateTime;

public record OrderSummaryDto(
        Long orderNo,
        Integer amountOfProducts,
        BigDecimal totalPrice,
        @JsonFormat(pattern = "yyyy-MM-dd")
        Date dueDate,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime createdDate,
        OrderStatus status
) {
}
