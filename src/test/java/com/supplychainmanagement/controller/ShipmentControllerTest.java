package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.shipping.PackageItemResponse;
import com.supplychainmanagement.dto.shipping.ShipmentPackageListDto;
import com.supplychainmanagement.exception.GlobalExceptionHandler;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
import com.supplychainmanagement.model.enums.ShipmentPackageType;
import com.supplychainmanagement.service.ShippingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The package list as a client calls it: the status is passed on - it used to be accepted and then
 * ignored - and the paging parameters reach the service as they were sent.
 */
class ShipmentControllerTest {

    private final ShippingService shippingService = mock(ShippingService.class);

    private MockMvc mockMvc;

    /** A loose package item as the list returns it - no package id. */
    private static PackageItemResponse looseItem() {
        return new PackageItemResponse(101L, LocalDateTime.of(2026, 9, 21, 10, 30), 11L, 1042L,
                "706a99c3-944b-11f1-9b51-001e064520d8", 5, 10, List.of(102L), UUID.randomUUID(), null,
                FulfillmentStatus.PACKING);
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ShipmentController(shippingService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setApiVersionStrategy(ApiVersioningTestSupport.apiVersionStrategy())
                .build();

        ShipmentPackageListDto row = new ShipmentPackageListDto(5L, "PKG-1", ShipmentPackageStatus.PACKED,
                ShipmentPackageType.CARTON, BigDecimal.ONE, new BigDecimal("1.5"), BigDecimal.ZERO, null, 2, List.of());
        when(shippingService.findShipmentPackages(any(), any(), any()))
                .thenAnswer(call -> new PageImpl<>(List.of(row), call.<Pageable>getArgument(2), 37));
    }

    @Test
    void passesStatusPackageNumberAndPagingOnToTheService() throws Exception {
        mockMvc.perform(get("/api/1.0/shipment-packages")
                        .param("status", "PACKED")
                        .param("packageNumber", "PKG-2026")
                        .param("page", "1")
                        .param("size", "10")
                        .param("sort", "packageNumber")
                        .param("order", "DESC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(5))
                .andExpect(jsonPath("$.content[0].status").value("PACKED"))
                .andExpect(jsonPath("$.total").value(37))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(10));

        verify(shippingService).findShipmentPackages(ShipmentPackageStatus.PACKED, "PKG-2026",
                PageRequest.of(1, 10, Sort.by(Sort.Direction.DESC, "packageNumber")));
    }

    /**
     * Without parameters the list shows the packages still being filled, and the package number
     * filter arrives as an empty string - the service then filters by status alone.
     */
    @Test
    void listsOpenPackagesByDefault() throws Exception {
        mockMvc.perform(get("/api/1.0/shipment-packages"))
                .andExpect(status().isOk());

        verify(shippingService).findShipmentPackages(ShipmentPackageStatus.OPEN, "",
                PageRequest.of(0, 25, Sort.by(Sort.Direction.ASC, "id")));
    }

    /** /packages lists package items, not packages - a loose one shows no package id. */
    @Test
    void packagesListsThePackageItems() throws Exception {
        PackageItemResponse loose = looseItem();
        when(shippingService.findPackageItems(any()))
                .thenAnswer(call -> new PageImpl<>(List.of(loose), call.<Pageable>getArgument(0), 1));

        mockMvc.perform(get("/api/1.0/packages").param("sort", "created").param("order", "DESC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(101))
                .andExpect(jsonPath("$.content[0].quantity").value(5))
                .andExpect(jsonPath("$.content[0].orderNo").value(1042))
                .andExpect(jsonPath("$.content[0].createdAt").value("2026-09-21 10:30:00"))
                .andExpect(jsonPath("$.content[0].fulfillmentStatus").value("PACKING"))
                .andExpect(jsonPath("$.content[0].siblings[0]").value(102))
                .andExpect(jsonPath("$.content[0].shipmentPackageId").value(nullValue()))
                .andExpect(jsonPath("$.total").value(1));

        verify(shippingService).findPackageItems(PageRequest.of(0, 25, Sort.by(Sort.Direction.DESC, "created")));
        verify(shippingService, never()).findShipmentPackages(any(), any(), any());
    }

    @Test
    void rejectsAStatusThatDoesNotExist() throws Exception {
        mockMvc.perform(get("/api/1.0/shipment-packages").param("status", "LOST"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(shippingService);
    }

    /** /lonely-packages asks for the loose items only - never for all of them. */
    @Test
    void lonelyPackagesListsOnlyTheLooseItems() throws Exception {
        PackageItemResponse loose = looseItem();
        when(shippingService.findLoosePackageItems(any()))
                .thenAnswer(call -> new PageImpl<>(List.of(loose), call.<Pageable>getArgument(0), 1));

        mockMvc.perform(get("/api/1.0/lonely-packages").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(101))
                .andExpect(jsonPath("$.content[0].shipmentPackageId").value(nullValue()))
                .andExpect(jsonPath("$.total").value(1));

        verify(shippingService).findLoosePackageItems(PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "id")));
        verify(shippingService, never()).findPackageItems(any());
    }

    @Test
    void showsOnePackageItem() throws Exception {
        when(shippingService.findPackageItem(101L)).thenReturn(looseItem());

        mockMvc.perform(get("/api/1.0/packages/101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(101))
                .andExpect(jsonPath("$.orderNo").value(1042));
    }

    @Test
    void showsOneShipmentPackageWithItsItems() throws Exception {
        when(shippingService.findShipmentPackage(5L)).thenReturn(new ShipmentPackageListDto(5L, "PKG-1",
                ShipmentPackageStatus.OPEN, ShipmentPackageType.CARTON, BigDecimal.ONE, new BigDecimal("1.5"),
                BigDecimal.ZERO, null, 1, List.of()));

        mockMvc.perform(get("/api/1.0/shipment-packages/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.packageNumber").value("PKG-1"))
                .andExpect(jsonPath("$.items").isArray());
    }

    /** An unknown id comes back as 404 through GlobalExceptionHandler. */
    @Test
    void answersAnUnknownIdWithNotFound() throws Exception {
        when(shippingService.findPackageItem(999L))
                .thenThrow(new ResourceNotFoundException("PackageItem", "id", 999L));
        when(shippingService.findShipmentPackage(999L))
                .thenThrow(new ResourceNotFoundException("ShipmentPackage", "id", 999L));

        mockMvc.perform(get("/api/1.0/packages/999")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/1.0/shipment-packages/999")).andExpect(status().isNotFound());
    }
}
