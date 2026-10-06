package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.common.PageResponse;
import com.supplychainmanagement.dto.storehouse.StorehouseResponse;
import com.supplychainmanagement.service.StorehouseService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * The storehouses a client can name. Read-only, ADMIN and WAREHOUSE - the roles that book stock and
 * receive goods, which are the two things that take a {@code storehouseId}.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping({"/api/{version}/storehouses"})
public class StorehouseController {

    /**
     * What {@code sort} may name. It is applied as a property path, so anything else would end in a
     * {@code PropertyReferenceException} - and, with no handler for it, a 500 for what is simply a
     * typo in a query parameter. {@code stocks} is deliberately not among them.
     */
    private static final Set<String> SORTABLE = Set.of("id", "name", "city", "country");

    private final StorehouseService storehouseService;

    /**
     * Storehouses, paged like every other list. Sorted by name by default - the order a person looks
     * one up in. A dropdown is {@code ?size=100}: there are few of them.
     */
    @GetMapping(path = "", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN','WAREHOUSE')")
    public PageResponse<StorehouseResponse> getStorehouses(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "name") String sort,
            @RequestParam(defaultValue = "ASC") String order) {
        return PageResponse.of(storehouseService.findStorehouses(
                PageRequests.of(page, size, sort, order, SORTABLE)));
    }
}
