package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.storehouse.StorehouseRequest;
import com.supplychainmanagement.dto.storehouse.StorehouseResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * The storehouses: listed, created, and their descriptive data changed.
 */
public interface StorehouseService {

    /** One page of storehouses, mapped while the session is open. */
    Page<StorehouseResponse> findStorehouses(Pageable pageable);

    /** Creates a storehouse from name, address, city and country; blank optional fields are stored as none. */
    StorehouseResponse create(StorehouseRequest request);

    /** Changes name, address, city and country of one storehouse; 404 when there is none with that id. */
    StorehouseResponse update(Long id, StorehouseRequest request);
}
