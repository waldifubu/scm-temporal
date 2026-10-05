package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.component.RequestComponentResponseDto;
import com.supplychainmanagement.dto.mapper.RequestComponentMapper;
import com.supplychainmanagement.repository.RequestComponentRepository;
import com.supplychainmanagement.service.RequestComponentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RequestComponentServiceImpl implements RequestComponentService {
    private final RequestComponentRepository requestComponentRepository;
    private final RequestComponentMapper requestComponentMapper;

    /**
     * Mapped here, while the transaction is open, like every other answer of this codebase - the
     * controller used to do it afterwards, which only worked because open-in-view kept a session
     * around to resolve the references from. The rows arrive with their component fetched, so the
     * list costs one query instead of one per row and level.
     */
    @Override
    @Transactional(readOnly = true)
    public List<RequestComponentResponseDto> findMyRequests(Long userId) {
        return requestComponentRepository.findBySupplierId(userId).stream()
                .map(requestComponentMapper::mapToDto)
                .toList();
    }
}
