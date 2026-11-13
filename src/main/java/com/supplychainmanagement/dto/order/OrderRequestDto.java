package com.supplychainmanagement.dto.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * What a client may send to place an order - and nothing else.
 * <p>
 * It replaces the {@code Order} entity as the body of {@code POST /orders}. Bound from JSON, the entity
 * took every field it has: {@code id}, {@code orderNo}, {@code status}, {@code total}, {@code created},
 * and through {@code orderItems} whole {@code Product} and {@code Component} graphs, with their ids. The
 * description a client generates its types from showed all of that as the request, and none of it was
 * meant to be sent. Anything not listed here is ignored now.
 * <p>
 * The shape on the wire is the one the order form always sent, so no client changes:
 * <pre>{"dueDate": "2026-12-01", "orderItems": [{"quantity": 2, "product": {"articleNo": 1001}}]}</pre>
 * {@code customer} is for the roles that order on somebody's behalf (ADMIN, MANAGER); a customer's own
 * order takes the logged-in user and ignores it. {@code OrderRequestMapper} turns it into the entity.
 *
 * @param dueDate    the day the customer wants the goods; optional
 * @param customer   the customer an ADMIN or MANAGER orders for; required for them, ignored for a customer
 * @param orderItems the lines; an article is named once, a repeat is folded into the first line
 */
public record OrderRequestDto(
        LocalDate dueDate,
        @Valid CustomerRef customer,
        @Valid List<Line> orderItems
) {

    /** The customer by id. */
    public record CustomerRef(@NotNull Long id) {
    }

    /** One line: how many of which article. */
    public record Line(
            @NotNull @Min(value = 1, message = "Your amount must be at least 1") Integer quantity,
            @NotNull @Valid ProductRef product
    ) {
    }

    /** An article by its number - the one the rest of the API names a product by. */
    public record ProductRef(@NotNull Long articleNo) {
    }
}
