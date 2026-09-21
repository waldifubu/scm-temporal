package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.shipping.CreatePackageRequest;
import com.supplychainmanagement.dto.shipping.PackageItemIdsRequest;
import com.supplychainmanagement.dto.shipping.UpdatePackageRequest;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.GlobalExceptionHandler;
import com.supplychainmanagement.model.enums.ShipmentPackageType;
import com.supplychainmanagement.repository.PackageItemRepository;
import com.supplychainmanagement.service.OrderHandlingService;
import com.supplychainmanagement.service.PackageItemResponseAssembler;
import com.supplychainmanagement.service.PackingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The packing endpoints as a client sees them: whether {@code items} is required is decided by the
 * validation group each endpoint asks for, and that only shows on a real request - the rules on the
 * DTO alone are covered by PackItemValidationTest.
 * <p>
 * Standalone MockMvc with the project's own API version resolver from WebConfig, so the requests go
 * to /api/1.0/... like real ones. No security filters - @PreAuthorize is not what is tested here.
 */
class FulfillmentControllerPackingValidationTest {

    private final OrderHandlingService orderHandlingService = mock(OrderHandlingService.class);
    private final PackingService packingService = mock(PackingService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FulfillmentController(orderHandlingService, packingService,
                        new PackageItemResponseAssembler(mock(PackageItemRepository.class))))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setApiVersionStrategy(ApiVersioningTestSupport.apiVersionStrategy())
                .build();

        when(packingService.createShipmentPackage(any(), any())).thenReturn(new ShipmentPackage());
        when(packingService.createCustomShipment(any())).thenReturn(new ShipmentPackage());
        when(packingService.updateCustomShipment(any(), any())).thenReturn(new ShipmentPackage());
        when(packingService.addPackageItems(any(), any())).thenReturn(new ShipmentPackage());
        when(packingService.removePackageItem(any(), any())).thenReturn(new ShipmentPackage());
        when(packingService.updatePackageData(any(), any())).thenReturn(new ShipmentPackage());
        when(packingService.completePackage(any())).thenReturn(new ShipmentPackage());
    }

    /** No items key at all: rejected before the service is ever called. */
    @Test
    void createShipmentPackageByOrderRejectsARequestWithoutItems() throws Exception {
        mockMvc.perform(post("/api/1.0/packing/1042").contentType(APPLICATION_JSON)
                        .content("""
                                { "shipmentPackageType": "CARTON" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.items").value("At least one item is required"));

        verifyNoInteractions(packingService);
    }

    @Test
    void createShipmentPackageByOrderRejectsAnEmptyItemList() throws Exception {
        mockMvc.perform(post("/api/1.0/packing/1042").contentType(APPLICATION_JSON)
                        .content("""
                                { "items": [], "shipmentPackageType": "CARTON" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.items").value("At least one item is required"));

        verifyNoInteractions(packingService);
    }

    /** The per-item rules still apply here - WithItems comes together with Default. */
    @Test
    void createShipmentPackageByOrderChecksTheItemsThemselves() throws Exception {
        mockMvc.perform(post("/api/1.0/packing/1042").contentType(APPLICATION_JSON)
                        .content("""
                                { "items": [ { "orderItemId": 11, "qty": 0 } ] }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$['items[0].qty']").value("qty must be at least 1"));

        verifyNoInteractions(packingService);
    }

    @Test
    void createShipmentPackageByOrderPassesARequestWithItemsOn() throws Exception {
        mockMvc.perform(post("/api/1.0/packing/1042").contentType(APPLICATION_JSON)
                        .content("""
                                { "items": [ { "orderItemId": 11, "qty": 2 } ], "shipmentPackageType": "CARTON" }
                                """))
                .andExpect(status().isOk());

        verify(packingService).createShipmentPackage(eq(1042L), any(CreatePackageRequest.class));
    }

    /** The contrast: the endpoint that creates the package alone does not ask for items. */
    @Test
    void createCustomShipmentGoesWithoutItems() throws Exception {
        mockMvc.perform(post("/api/1.0/packing/shipment").contentType(APPLICATION_JSON)
                        .content("""
                                { "shipmentPackageType": "CARTON" }
                                """))
                .andExpect(status().isOk());

        verify(packingService).createCustomShipment(any(CreatePackageRequest.class));
    }

    /**
     * Optional does not mean unchecked: items that are sent to createCustomShipment go through the
     * Default group of plain @Valid, so each one needs an orderItemId and a qty of at least 1.
     */
    @Test
    void createCustomShipmentStillChecksTheItemsItIsGiven() throws Exception {
        mockMvc.perform(post("/api/1.0/packing/shipment").contentType(APPLICATION_JSON)
                        .content("""
                                { "items": [ { "orderItemId": 11, "qty": 0 }, { "qty": 2 } ] }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$['items[0].qty']").value("qty must be at least 1"))
                .andExpect(jsonPath("$['items[1].orderItemId']").value("orderItemId is required"));

        verifyNoInteractions(packingService);
    }

    // ------------------------------------------------------------------ package contents

    private static PackageItemIdsRequest withIds(List<Long> ids) {
        return argThat(request -> request != null && request.packageItemIds().equals(ids));
    }

    /** PUT .../items replaces the contents - here with the bare array of package item ids. */
    @Test
    void updateCustomShipmentTakesABareArrayOfPackageItemIds() throws Exception {
        mockMvc.perform(put("/api/1.0/packing/shipment/5/items").contentType(APPLICATION_JSON)
                        .content("[101, 102]"))
                .andExpect(status().isOk());

        verify(packingService).updateCustomShipment(eq(5L), withIds(List.of(101L, 102L)));
    }

    /** An empty list is a valid replacement - it empties the package. */
    @Test
    void updateCustomShipmentAcceptsAnEmptyList() throws Exception {
        mockMvc.perform(put("/api/1.0/packing/shipment/5/items").contentType(APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isOk());

        verify(packingService).updateCustomShipment(eq(5L), withIds(List.of()));
    }

    @Test
    void addPackageItemsTakesTheWrappedForm() throws Exception {
        mockMvc.perform(post("/api/1.0/packing/shipment/5/items").contentType(APPLICATION_JSON)
                        .content("""
                                { "packageItemIds": [101] }
                                """))
                .andExpect(status().isOk());

        verify(packingService).addPackageItems(eq(5L), withIds(List.of(101L)));
    }

    @Test
    void removePackageItemTakesBothIdsFromThePath() throws Exception {
        mockMvc.perform(delete("/api/1.0/packing/shipment/5/items/101"))
                .andExpect(status().isOk());

        verify(packingService).removePackageItem(5L, 101L);
    }

    @Test
    void updatePackageDataTakesThePackageFieldsOnly() throws Exception {
        mockMvc.perform(put("/api/1.0/packing/shipment/5").contentType(APPLICATION_JSON)
                        .content("""
                                { "shipmentPackageType": "carton", "length": 40, "packageNumber": "PKG-2" }
                                """))
                .andExpect(status().isOk());

        verify(packingService).updatePackageData(eq(5L), argThat((UpdatePackageRequest request) ->
                request.shipmentPackageType() == ShipmentPackageType.CARTON
                        && "PKG-2".equals(request.packageNumber())));
    }

    @Test
    void refusesANullPackageItemId() throws Exception {
        mockMvc.perform(put("/api/1.0/packing/shipment/5/items").contentType(APPLICATION_JSON)
                        .content("[101, null]"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(packingService);
    }

    @Test
    void completePackageTakesTheIdFromThePath() throws Exception {
        mockMvc.perform(put("/api/1.0/packing/shipment/5/complete"))
                .andExpect(status().isOk());

        verify(packingService).completePackage(5L);
    }

    /** Completing a package that is not OPEN is a 409, in the {"message": ...} shape of these endpoints. */
    @Test
    void answersCompletingAPackedPackageWith409() throws Exception {
        when(packingService.completePackage(5L)).thenThrow(new APIException(HttpStatus.CONFLICT,
                "ShipmentPackage 5 is PACKED, only an OPEN package can be changed"));

        mockMvc.perform(put("/api/1.0/packing/shipment/5/complete"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("ShipmentPackage 5 is PACKED, only an OPEN package can be changed"));
    }

    /** A conflict from the service reaches the client at its own status, as {"message": ...}. */
    @Test
    void answersAConflictFromTheServiceWith409() throws Exception {
        when(packingService.addPackageItems(any(), any())).thenThrow(
                new APIException(HttpStatus.CONFLICT, "PackageItem 101 is already in ShipmentPackage 6"));

        mockMvc.perform(post("/api/1.0/packing/shipment/5/items").contentType(APPLICATION_JSON)
                        .content("[101]"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("PackageItem 101 is already in ShipmentPackage 6"));
    }
}
