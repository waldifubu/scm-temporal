package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.storehouse.StorehouseResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * The storehouses, read-only. Creating and changing them is not part of the API - they are set up
 * with the system, and everything that works with one only needs to find it.
 */
public interface StorehouseService {

    /** One page of storehouses, mapped while the session is open. */
    Page<StorehouseResponse> findStorehouses(Pageable pageable);
}
