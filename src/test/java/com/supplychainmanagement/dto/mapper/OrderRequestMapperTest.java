package com.supplychainmanagement.dto.mapper;

import com.supplychainmanagement.dto.order.OrderRequestDto;
import com.supplychainmanagement.entity.Order;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderRequestMapperTest {

    private final OrderRequestMapper mapper = new OrderRequestMapper();

    @Test
    void carriesOnlyWhatTheRequestNamesAndLeavesTheServersFieldsEmpty() {
        OrderRequestDto request = new OrderRequestDto(LocalDate.of(2026, 12, 1),
                new OrderRequestDto.CustomerRef(7L),
                List.of(new OrderRequestDto.Line(2, new OrderRequestDto.ProductRef(1001L))));

        Order order = mapper.mapToEntity(request);

        assertThat(order.getDueDate()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(order.getCustomer().getId()).isEqualTo(7L);
        assertThat(order.getOrderItems()).singleElement().satisfies(line -> {
            assertThat(line.getQuantity()).isEqualTo(2);
            assertThat(line.getProduct().getArticleNo()).isEqualTo(1001L);
        });
        assertThat(order.getId()).isNull();
        assertThat(order.getOrderNo()).isNull();
        assertThat(order.getStatus()).isNull();
    }

    @Test
    void anEmptyRequestGivesAnEmptyOrder() {
        Order order = mapper.mapToEntity(new OrderRequestDto(null, null, null));

        assertThat(order.getCustomer()).isNull();
        assertThat(order.getDueDate()).isNull();
    }
}
