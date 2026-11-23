package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.storehouse.StorehouseRequest;
import com.supplychainmanagement.dto.storehouse.StorehouseResponse;
import com.supplychainmanagement.entity.Storehouse;
import com.supplychainmanagement.exception.ResourceNotFoundException;
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

    @Override
    @Transactional
    public StorehouseResponse create(StorehouseRequest request) {
        Storehouse storehouse = new Storehouse();
        apply(storehouse, request);
        return StorehouseResponse.from(storehouseRepository.save(storehouse));
    }

    @Override
    @Transactional
    public StorehouseResponse update(Long id, StorehouseRequest request) {
        Storehouse storehouse = storehouseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Storehouse", "id", id));
        apply(storehouse, request);
        return StorehouseResponse.from(storehouse);
    }

    private static void apply(Storehouse storehouse, StorehouseRequest request) {
        storehouse.setName(request.name().trim());
        storehouse.setAddress(blankToNull(request.address()));
        storehouse.setCity(blankToNull(request.city()));
        storehouse.setCountry(blankToNull(request.country()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
