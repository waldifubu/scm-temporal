package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.shipping.CreateShipmentRequest;
import com.supplychainmanagement.dto.shipping.ShipmentPackageIdsRequest;
import com.supplychainmanagement.dto.shipping.ShipmentResponse;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.GlobalExceptionHandler;
import com.supplychainmanagement.model.enums.ShipmentStatus;
import com.supplychainmanagement.service.ShipmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The shipment endpoints as a client calls them: binding, validation and status codes. */
class ShipmentControllerTest {

    private final ShipmentService shipmentService = mock(ShipmentService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ShipmentController(shipmentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setApiVersionStrategy(ApiVersioningTestSupport.apiVersionStrategy())
                .build();

        ShipmentResponse response = new ShipmentResponse(50L, 3L, "Ada Lovelace", ShipmentStatus.CREATED,
                "Musterstr. 1", "DHL", null, LocalDate.of(2026, 10, 1), null, null, null, 0, BigDecimal.ZERO,
                null, null, List.of());
        when(shipmentService.createShipment(any())).thenReturn(response);
        when(shipmentService.addShipmentPackages(any(), any())).thenReturn(response);
        when(shipmentService.replaceShipmentPackages(any(), any())).thenReturn(response);
        when(shipmentService.removeShipmentPackage(any(), any())).thenReturn(response);
        when(shipmentService.updateShipmentData(any(), any())).thenReturn(response);
        when(shipmentService.findShipments(any(), any()))
                .thenAnswer(call -> new PageImpl<>(List.of(), call.<Pageable>getArgument(1), 0));
    }

    @Test
    void createsAShipmentWith201() throws Exception {
        mockMvc.perform(post("/api/1.0/shipments").contentType(APPLICATION_JSON)
                        .content("""
                                { "shipmentPackageIds": [5, 7],
                                  "shippingAddress": "Musterstr. 1", "shippingMethod": "DHL",
                                  "requestedDeliveryDate": "2026-10-01" }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(50))
                .andExpect(jsonPath("$.customerName").value("Ada Lovelace"));

        verify(shipmentService).createShipment(argThat((CreateShipmentRequest request) ->
                request.shipmentPackageIds().equals(List.of(5L, 7L))
                        && LocalDate.of(2026, 10, 1).equals(request.requestedDeliveryDate())));
    }

    /**
     * At least one package is required - refused before the service. The customer is not sent (it
     * comes from the packages), and the address is optional, so neither shows up as an error.
     */
    @Test
    void refusesACreateRequestWithoutPackages() throws Exception {
        mockMvc.perform(post("/api/1.0/shipments").contentType(APPLICATION_JSON)
                        .content("""
                                { "shipmentPackageIds": [] }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.shipmentPackageIds").value("At least one shipment package is required"))
                .andExpect(jsonPath("$.customerId").doesNotExist())
                .andExpect(jsonPath("$.shippingAddress").doesNotExist());

        verifyNoInteractions(shipmentService);
    }

    @Test
    void addsPackagesFromABareArray() throws Exception {
        mockMvc.perform(post("/api/1.0/shipments/50/packages").contentType(APPLICATION_JSON)
                        .content("[7, 9]"))
                .andExpect(status().isOk());

        verify(shipmentService).addShipmentPackages(eq(50L),
                argThat((ShipmentPackageIdsRequest request) -> request.shipmentPackageIds().equals(List.of(7L, 9L))));
    }

    @Test
    void replacesPackagesFromTheWrappedForm() throws Exception {
        mockMvc.perform(put("/api/1.0/shipments/50/packages").contentType(APPLICATION_JSON)
                        .content("""
                                { "shipmentPackageIds": [7] }
                                """))
                .andExpect(status().isOk());

        verify(shipmentService).replaceShipmentPackages(eq(50L),
                argThat((ShipmentPackageIdsRequest request) -> request.shipmentPackageIds().equals(List.of(7L))));
    }

    @Test
    void removesAPackageByTheIdsInThePath() throws Exception {
        mockMvc.perform(delete("/api/1.0/shipments/50/packages/7"))
                .andExpect(status().isOk());

        verify(shipmentService).removeShipmentPackage(50L, 7L);
    }

    @Test
    void refusesShipmentDataWithoutAddress() throws Exception {
        mockMvc.perform(put("/api/1.0/shipments/50").contentType(APPLICATION_JSON)
                        .content("""
                                { "shippingMethod": "UPS" }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(shipmentService);
    }

    /** No status parameter means every shipment, not a default status. */
    @Test
    void listsWithoutAStatusByDefault() throws Exception {
        mockMvc.perform(get("/api/1.0/shipments"))
                .andExpect(status().isOk());

        verify(shipmentService).findShipments(isNull(), any(Pageable.class));
    }

    /** A rule broken in the service reaches the client at its own status, through the global handler. */
    @Test
    void answersAConflictFromTheServiceWith409() throws Exception {
        when(shipmentService.createShipment(any())).thenThrow(
                new APIException(HttpStatus.CONFLICT, "ShipmentPackage 5 is OPEN, only PACKED packages can be shipped"));

        mockMvc.perform(post("/api/1.0/shipments").contentType(APPLICATION_JSON)
                        .content("""
                                { "customerId": 3, "shipmentPackageIds": [5], "shippingAddress": "Musterstr. 1" }
                                """))
                .andExpect(status().isConflict());
    }
}
