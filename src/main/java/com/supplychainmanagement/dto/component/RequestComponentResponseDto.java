package com.supplychainmanagement.dto.component;

import com.supplychainmanagement.model.enums.RequestStatus;

import java.time.LocalDateTime;

/**
 * One placed component request, as {@code GET /components/my-requests} answers it to the supplier it
 * was placed with.
 * <p>
 * {@code qty} is <strong>how many were ordered</strong>, a {@code Long} like
 * {@code RequestComponent.qty}. Not to be confused with {@code component.qty}, which is the
 * bill-of-materials quantity - how many of the part go into one unit of the product. Those two
 * sitting in one response under the same name is how this field came to be an {@code Integer},
 * copied from the component's: MapStruct narrowed the ordered quantity without a word, while the
 * column behind it is a {@code bigint}.
 * <p>
 * {@code component} is a {@link ComponentResponseDto} and no longer the {@code Component}
 * <strong>entity</strong>. An entity in a response serializes what it points at - the component's
 * product came along - and resolves every LAZY reference as the writer touches it, one query at a
 * time through the open-in-view session. Responses are DTOs here, all the way down.
 * <p>
 * It deliberately does not carry {@code assignedBy}: the supplier sees their own request, and which
 * person inside the house last moved it is none of their business. The warehouse view of the same
 * rows, {@code GET /components/requests}, does name them.
 */
public record RequestComponentResponseDto(
        Long id,
        String comment,
        LocalDateTime requestDate,
        RequestStatus requestStatus,
        LocalDateTime updated,
        Long qty,
        ComponentResponseDto component
) {
}
