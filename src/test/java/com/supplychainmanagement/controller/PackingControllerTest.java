package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.shipping.CreatePackageRequest;
import com.supplychainmanagement.dto.shipping.PackageItemIdsRequest;
import com.supplychainmanagement.dto.shipping.UpdatePackageRequest;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.GlobalExceptionHandler;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.ShipmentPackageType;
import com.supplychainmanagement.repository.PackageItemRepository;
import com.supplychainmanagement.service.PackageItemResponseAssembler;
import com.supplychainmanagement.service.OrderHandlingService;
import com.supplychainmanagement.service.PackingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

/**
 * The packing endpoints as a client sees them: whether {@code items} is required is decided by the
 * validation group each endpoint asks for, and that only shows on a real request - the rules on the
 * DTO alone are covered by PackItemValidationTest.
 * <p>
 * Standalone MockMvc with the project's own API version resolver from WebConfig, so the requests go
 * to /api/1.0/... like real ones. No security filters - @PreAuthorize is not what is tested here.
 */
class PackingControllerTest {

    private final PackingService packingService = mock(PackingService.class);
    private final OrderHandlingService orderHandlingService = mock(OrderHandlingService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PackingController(packingService,
                        new PackageItemResponseAssembler(mock(PackageItemRepository.class)), orderHandlingService))
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

    /**
     * Completing a package that is not OPEN is a 409 - answered as ErrorDetails through the global
     * handler, like every other endpoint. These two used to catch the exception themselves and
     * answer a bare {"message": ...} map; the message survives, the rest comes with it.
     */
    @Test
    void answersCompletingAPackedPackageWith409() throws Exception {
        when(packingService.completePackage(5L)).thenThrow(new APIException(HttpStatus.CONFLICT,
                "ShipmentPackage 5 is PACKED, only an OPEN package can be changed"));

        mockMvc.perform(put("/api/1.0/packing/shipment/5/complete"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("ShipmentPackage 5 is PACKED, only an OPEN package can be changed"))
                .andExpect(jsonPath("$.errorCode").value("API_ERROR"))
                .andExpect(jsonPath("$.path").exists());
    }

    /** The packing work list: PICKED lines by default, sorted by updatedAt, paged as sent. */
    @Test
    void listsThePickedOrderItemsByDefault() throws Exception {
        when(orderHandlingService.getOrderItems(any(), any())).thenAnswer(call ->
                new org.springframework.data.domain.PageImpl<>(List.of(), call.getArgument(1), 0));

        mockMvc.perform(get("/api/1.0/order-items").param("page", "2").param("size", "10"))
                .andExpect(status().isOk());

        verify(orderHandlingService).getOrderItems(eq(FulfillmentStatus.PICKED), argThat((Pageable pageable) ->
                pageable.getPageNumber() == 2 && pageable.getPageSize() == 10
                        && pageable.getSort().equals(Sort.by(Sort.Direction.ASC, "updatedAt"))));
    }

    // ------------------------------------------------------------------ a package from existing items

    /** The caller's own JSON, key for key - including the weight, which is never read. */
    private static final String FROM_ITEMS_BODY = """
            {
              "shipmentPackageType": "OTHER",
              "packageItems": [1, 2, 3, 4],
              "packageNumber": null,
              "weight": null,
              "length": null,
              "width": null,
              "height": null
            }""";

    @Test
    void createsAPackageFromTheCallersJson() throws Exception {
        when(packingService.createPackageFromItems(any())).thenReturn(new ShipmentPackage());

        mockMvc.perform(post("/api/1.0/packing/shipment/from-items").contentType(APPLICATION_JSON)
                        .content(FROM_ITEMS_BODY))
                .andExpect(status().isOk());

        verify(packingService).createPackageFromItems(argThat(request ->
                request.packageItems().equals(List.of(1L, 2L, 3L, 4L))
                        && request.shipmentPackageType() == ShipmentPackageType.OTHER
                        && request.packageNumber() == null
                        && request.length() == null && request.width() == null && request.height() == null));
    }

    /** The type is case insensitive, as on POST /packing/shipment. */
    @Test
    void readsTheTypeCaseInsensitively() throws Exception {
        when(packingService.createPackageFromItems(any())).thenReturn(new ShipmentPackage());

        mockMvc.perform(post("/api/1.0/packing/shipment/from-items").contentType(APPLICATION_JSON)
                        .content("{\"shipmentPackageType\": \"carton\", \"packageItems\": [7], \"length\": 30.5}"))
                .andExpect(status().isOk());

        verify(packingService).createPackageFromItems(argThat(request ->
                request.shipmentPackageType() == ShipmentPackageType.CARTON
                        && request.length().compareTo(new java.math.BigDecimal("30.5")) == 0));
    }

    @Test
    void refusesARequestWithoutPackageItems() throws Exception {
        mockMvc.perform(post("/api/1.0/packing/shipment/from-items").contentType(APPLICATION_JSON)
                        .content("{\"shipmentPackageType\": \"OTHER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.packageItems").exists());

        verifyNoInteractions(packingService);
    }

    @Test
    void refusesAnEmptyListOfPackageItems() throws Exception {
        mockMvc.perform(post("/api/1.0/packing/shipment/from-items").contentType(APPLICATION_JSON)
                        .content("{\"packageItems\": []}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(packingService);
    }

    @Test
    void refusesANullIdInTheList() throws Exception {
        mockMvc.perform(post("/api/1.0/packing/shipment/from-items").contentType(APPLICATION_JSON)
                        .content("{\"packageItems\": [1, null]}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(packingService);
    }

    /** Order line ids under the old key are not package item ids: the old key is simply not this one. */
    @Test
    void doesNotTakeOrderLinesUnderTheItemsKey() throws Exception {
        mockMvc.perform(post("/api/1.0/packing/shipment/from-items").contentType(APPLICATION_JSON)
                        .content("{\"items\": [{\"orderItemId\": 1, \"qty\": 2}]}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(packingService);
    }

    /** The service's refusals reach the client at their own status. */
    @Test
    void answersAConflictFromTheServiceWith409Here() throws Exception {
        when(packingService.createPackageFromItems(any())).thenThrow(
                new APIException(HttpStatus.CONFLICT, "PackageItem 2 is already in ShipmentPackage 8"));

        mockMvc.perform(post("/api/1.0/packing/shipment/from-items").contentType(APPLICATION_JSON)
                        .content(FROM_ITEMS_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("PackageItem 2 is already in ShipmentPackage 8"));
    }

    /** The literal path must not be taken for POST /packing/shipment/{id}/items or a package id. */
    @Test
    void keepsTheFromItemsPathApartFromTheOtherPackageEndpoints() throws Exception {
        when(packingService.createPackageFromItems(any())).thenReturn(new ShipmentPackage());

        mockMvc.perform(post("/api/1.0/packing/shipment/from-items").contentType(APPLICATION_JSON)
                .content(FROM_ITEMS_BODY)).andExpect(status().isOk());

        verify(packingService, never()).createCustomShipment(any());
        verify(packingService, never()).addPackageItems(any(), any());
    }

    // ------------------------------------------------------------------ the packable list

    private void packableAnswersEmptyPages() {
        when(packingService.findPackableItems(any(), any())).thenAnswer(call ->
                new org.springframework.data.domain.PageImpl<>(List.of(), call.getArgument(1), 0));
    }

    /** No parameters: every order, the first page of 25, the line picked first first. */
    @Test
    void listsThePackableItemsOfEveryOrderByDefault() throws Exception {
        packableAnswersEmptyPages();

        mockMvc.perform(get("/api/1.0/order-items/packable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.total").value(0));

        verify(packingService).findPackableItems(eq(null), argThat((Pageable pageable) ->
                pageable.getPageNumber() == 0 && pageable.getPageSize() == 25
                        && pageable.getSort().equals(Sort.by(Sort.Direction.ASC, "updatedAt"))));
    }

    /** One order is what a package is made for, so the filter reaches the service as sent. */
    @Test
    void narrowsThePackableItemsToOneOrder() throws Exception {
        packableAnswersEmptyPages();

        mockMvc.perform(get("/api/1.0/order-items/packable")
                        .param("orderNo", "104321").param("page", "1").param("size", "5")
                        .param("sort", "quantity").param("order", "DESC"))
                .andExpect(status().isOk());

        verify(packingService).findPackableItems(eq(104321L), argThat((Pageable pageable) ->
                pageable.getPageNumber() == 1 && pageable.getPageSize() == 5
                        && pageable.getSort().equals(Sort.by(Sort.Direction.DESC, "quantity"))));
    }

    /** It is a path of its own: the literal must not be taken for an id or a status of /order-items. */
    @Test
    void doesNotAnswerThePackableListFromTheStatusListsHandler() throws Exception {
        packableAnswersEmptyPages();

        mockMvc.perform(get("/api/1.0/order-items/packable")).andExpect(status().isOk());

        verifyNoInteractions(orderHandlingService);
    }

    /** A joined column is not resolvable on the projection; naming one is a 400, not a 500. */
    @Test
    void refusesToSortThePackableItemsByAJoinedColumn() throws Exception {
        mockMvc.perform(get("/api/1.0/order-items/packable").param("sort", "productName"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(packingService);
    }

    @Test
    void refusesANegativePageOfPackableItems() throws Exception {
        mockMvc.perform(get("/api/1.0/order-items/packable").param("page", "-1"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(packingService);
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
