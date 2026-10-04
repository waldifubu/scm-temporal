package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.RequestComponent;
import com.supplychainmanagement.repository.RequestComponentRepository;
import com.supplychainmanagement.service.RequestComponentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RequestComponentServiceImpl implements RequestComponentService {
    private final RequestComponentRepository requestComponentRepository;


    @Override
    public List<RequestComponent> findMyRequests(Long userId) {
        return requestComponentRepository.findBySupplierId(userId);
    }
}
