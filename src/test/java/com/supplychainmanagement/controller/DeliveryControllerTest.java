package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.shipping.CancelShipmentRequest;
import com.supplychainmanagement.dto.shipping.DeliveryResponse;
import com.supplychainmanagement.exception.GlobalExceptionHandler;
import com.supplychainmanagement.model.enums.ShipmentStatus;
import com.supplychainmanagement.service.DeliveryService;
import com.supplychainmanagement.service.ShipmentService;
import com.supplychainmanagement.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The carrier's endpoints as a distributor calls them. {@link ShipmentController} is registered
 * alongside on purpose: both map under {@code /shipments}, and only with the two together does the
 * test say which one a path really reaches.
 */
class DeliveryControllerTest {

    private final DeliveryService deliveryService = mock(DeliveryService.class);
    private final ShipmentService shipmentService = mock(ShipmentService.class);
    private final UserService userService = mock(UserService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new DeliveryController(deliveryService, userService),
                        new ShipmentController(shipmentService, userService))
                .setControllerAdvice(new GlobalExceptionHandler())
                // @AuthenticationPrincipal has no resolver in a standalone setup - without it every
                // endpoint here fails before it reaches the controller method.
                .setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
                .setApiVersionStrategy(ApiVersioningTestSupport.apiVersionStrategy())
                .build();

        when(userService.getAuthenticatedUserId(any())).thenReturn(99L);
        when(deliveryService.acceptShipment(any(), any())).thenReturn(delivery(ShipmentStatus.ACCEPTED));
        when(deliveryService.shipmentInTransit(any(), any())).thenReturn(delivery(ShipmentStatus.IN_TRANSIT));
        when(deliveryService.shipmentDelivered(any(), any())).thenReturn(delivery(ShipmentStatus.DELIVERED));
        when(deliveryService.findShipmentsForDistributor(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(delivery(ShipmentStatus.ACCEPTED))));
        when(deliveryService.cancelShipment(any(), any(), any())).thenReturn(delivery(ShipmentStatus.CANCELLED));
        when(shipmentService.cancelShipment(any(), any(), any())).thenReturn(null);
    }

    private static DeliveryResponse delivery(ShipmentStatus status) {
        return new DeliveryResponse(50L, status, "Ada Lovelace", "Musterstr. 1", "DHL", null, null, null, null,
                1, BigDecimal.ONE, List.of("PKG-5"));
    }

    /** The three steps of the carrier, each with the id from the path and the acting user. */
    @Test
    void acceptInTransitAndDeliveredPassTheShipmentAndTheUserOn() throws Exception {
        mockMvc.perform(post("/api/1.0/shipments/50/accept")).andExpect(status().isOk());
        mockMvc.perform(post("/api/1.0/shipments/50/intransit")).andExpect(status().isOk());
        mockMvc.perform(post("/api/1.0/shipments/50/delivered")).andExpect(status().isOk());

        verify(deliveryService).acceptShipment(50L, 99L);
        verify(deliveryService).shipmentInTransit(50L, 99L);
        verify(deliveryService).shipmentDelivered(50L, 99L);
        verifyNoInteractions(shipmentService);
    }

    /**
     * The work list. No id in the path - the distributor is the one calling, so it comes from the
     * authenticated user, and the defaults of every other list apply.
     */
    @Test
    void listsTheShipmentsOfTheCallingDistributor() throws Exception {
        mockMvc.perform(get("/api/1.0/shipments/distributor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.content[0].shipmentId").value(50));

        verify(deliveryService).findShipmentsForDistributor(eq(99L), isNull(), argThat(pageable ->
                pageable.getPageNumber() == 0 && pageable.getPageSize() == 25
                        && pageable.getSort().getOrderFor("id").getDirection().isAscending()));
    }

    /** Paging and the status filter are handed on the way the other lists hand them. */
    @Test
    void passesPagingAndTheStatusFilterOn() throws Exception {
        mockMvc.perform(get("/api/1.0/shipments/distributor")
                        .param("page", "2").param("size", "5").param("sort", "createdAt")
                        .param("order", "DESC").param("status", "IN_TRANSIT"))
                .andExpect(status().isOk());

        verify(deliveryService).findShipmentsForDistributor(eq(99L), eq(ShipmentStatus.IN_TRANSIT),
                argThat((Pageable pageable) -> pageable.getPageNumber() == 2 && pageable.getPageSize() == 5
                        && pageable.getSort().getOrderFor("createdAt").getDirection().isDescending()));
    }

    /**
     * {@code /shipments/distributor} and {@code /shipments/{shipmentId}} are one segment apart and
     * live in different controllers. The literal has to win - otherwise the path would end up in
     * ShipmentController, where "distributor" is no Long.
     */
    @Test
    void doesNotCollideWithTheSingleShipmentPath() throws Exception {
        mockMvc.perform(get("/api/1.0/shipments/distributor")).andExpect(status().isOk());

        verify(deliveryService).findShipmentsForDistributor(any(), any(), any());
        verifyNoInteractions(shipmentService);
    }

    /** Handing the shipment back: the reason from the body, the id from the path, the acting user. */
    @Test
    void cancelPassesTheReasonAndTheUserOn() throws Exception {
        mockMvc.perform(put("/api/1.0/shipments/50/cancel").contentType(APPLICATION_JSON)
                        .content("""
                                { "reason": "truck broke down" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        verify(deliveryService).cancelShipment(50L, new CancelShipmentRequest("truck broke down"), 99L);
        verifyNoInteractions(shipmentService);
    }

    /** No reason, no cancellation - the comment is the only record of why it was called off. */
    @Test
    void refusesToCancelWithoutAReason() throws Exception {
        mockMvc.perform(put("/api/1.0/shipments/50/cancel").contentType(APPLICATION_JSON)
                        .content("""
                                { "reason": "  " }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(deliveryService);
    }

    /**
     * The planning side calls the same thing off with POST on the same path. Both mappings have to
     * exist side by side - the PUT belongs to the carrier, the POST to LOGISTICS.
     */
    @Test
    void leavesThePostCancelOfThePlanningSideAlone() throws Exception {
        mockMvc.perform(post("/api/1.0/shipments/50/cancel").contentType(APPLICATION_JSON)
                        .content("""
                                { "reason": "repacking" }
                                """))
                .andExpect(status().isOk());

        verify(shipmentService).cancelShipment(eq(50L), any(), eq(99L));
        verifyNoInteractions(deliveryService);
    }

    /** An unknown status is a 400 from the binder, not a 500. */
    @Test
    void refusesAnUnknownStatus() throws Exception {
        mockMvc.perform(get("/api/1.0/shipments/distributor").param("status", "FLYING"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(deliveryService);
    }
}
