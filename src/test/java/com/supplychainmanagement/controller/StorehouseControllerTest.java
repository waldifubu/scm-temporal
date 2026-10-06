package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.storehouse.StorehouseResponse;
import com.supplychainmanagement.exception.GlobalExceptionHandler;
import com.supplychainmanagement.service.StorehouseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The storehouse list as a client calls it. Who may call it is the business of
 * ApiAuthorizationTest - this is the shape, the paging and the sort guard.
 */
class StorehouseControllerTest {

    private final StorehouseService storehouseService = mock(StorehouseService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new StorehouseController(storehouseService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setApiVersionStrategy(ApiVersioningTestSupport.apiVersionStrategy())
                .build();

        when(storehouseService.findStorehouses(any())).thenAnswer(call -> new PageImpl<>(
                List.of(new StorehouseResponse(1L, "Hauptlager", "Musterstr. 1", "Berlin", "DE"),
                        new StorehouseResponse(2L, "Aussenlager", "Hafenstr. 9", "Hamburg", "DE")),
                call.<Pageable>getArgument(0), 2));
    }

    /** Paged like every other list, and the fields a client needs to name a storehouse. */
    @Test
    void listsStorehousesInThePagedShape() throws Exception {
        mockMvc.perform(get("/api/1.0/storehouses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.size").value(25))
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Hauptlager"))
                .andExpect(jsonPath("$.content[0].city").value("Berlin"))
                // The stocks collection of the entity never reaches the response.
                .andExpect(jsonPath("$.content[0].stocks").doesNotExist());
    }

    /** By name, ascending: the order a person looks a storehouse up in, not the order it was created. */
    @Test
    void sortsByNameByDefault() throws Exception {
        mockMvc.perform(get("/api/1.0/storehouses")).andExpect(status().isOk());

        verify(storehouseService).findStorehouses(argThat((Pageable pageable) ->
                pageable.getPageNumber() == 0 && pageable.getPageSize() == 25
                        && Sort.by(Sort.Direction.ASC, "name").equals(pageable.getSort())));
    }

    /** A dropdown asks for all of them at once. */
    @Test
    void passesPagingAndSortingOn() throws Exception {
        mockMvc.perform(get("/api/1.0/storehouses")
                        .param("page", "1").param("size", "100").param("sort", "city").param("order", "DESC"))
                .andExpect(status().isOk());

        verify(storehouseService).findStorehouses(argThat((Pageable pageable) ->
                pageable.getPageNumber() == 1 && pageable.getPageSize() == 100
                        && Sort.by(Sort.Direction.DESC, "city").equals(pageable.getSort())));
    }

    /**
     * A sort field that does not exist is a 400 naming it, and the service is never reached. Without
     * the guard it is a PropertyReferenceException and, as nothing handles that, a 500.
     */
    @Test
    void refusesAnUnknownSortFieldWith400() throws Exception {
        mockMvc.perform(get("/api/1.0/storehouses").param("sort", "nonsense"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("nonsense")));

        verifyNoInteractions(storehouseService);
    }

    /** The collection of the entity is not sortable either - it is not a column of the storehouse. */
    @Test
    void refusesToSortByTheStocksCollection() throws Exception {
        mockMvc.perform(get("/api/1.0/storehouses").param("sort", "stocks"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(storehouseService);
    }
}
