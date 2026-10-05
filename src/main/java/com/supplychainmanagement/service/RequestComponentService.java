package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.component.RequestComponentResponseDto;

import java.util.List;

public interface RequestComponentService {

    /**
     * The requests placed with this supplier, as the API answers them - mapped here rather than in
     * the controller, so it happens while the transaction is open.
     * <p>
     * DTOs all the way down: {@link RequestComponentResponseDto} carries a
     * {@code ComponentResponseDto} and not the {@code Component} entity, which used to drag the
     * component's product into the response and resolve the graph one LAZY reference at a time.
     *
     * @param userId the supplier asking - the list is filtered by {@code supplier_id}, so anybody
     *               else gets an empty one. The warehouse has {@code GET /components/requests}
     */
    List<RequestComponentResponseDto> findMyRequests(Long userId);
}
