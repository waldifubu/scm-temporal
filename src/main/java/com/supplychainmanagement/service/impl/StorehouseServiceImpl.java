package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.storehouse.StorehouseResponse;
import com.supplychainmanagement.repository.StorehouseRepository;
import com.supplychainmanagement.service.StorehouseService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StorehouseServiceImpl implements StorehouseService {

    private final StorehouseRepository storehouseRepository;

    /**
     * One query. The {@code stocks} collection of a storehouse is never touched - the response does
     * not carry it - so nothing lazy is loaded, and sorting and paging happen in SQL.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<StorehouseResponse> findStorehouses(Pageable pageable) {
        return storehouseRepository.findAll(pageable).map(StorehouseResponse::from);
    }
}
