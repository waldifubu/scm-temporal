package com.supplychainmanagement.controller;

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
class OutboundControllerTest {

    private final DeliveryService deliveryService = mock(DeliveryService.class);
    private final ShipmentService shipmentService = mock(ShipmentService.class);
    private final UserService userService = mock(UserService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new OutboundController(deliveryService, userService),
                        new ShipmentController(shipmentService, userService))
                .setControllerAdvice(new GlobalExceptionHandler())
                // @AuthenticationPrincipal has no resolver in a standalone setup - without it every
                // endpoint here fails before it reaches the controller method.
                .setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
                .setApiVersionStrategy(ApiVersioningTestSupport.apiVersionStrategy())
                .build();

        when(userService.getAuthenticatedUserId(any())).thenReturn(99L);
        when(deliveryService.acceptShipment(any(), any(), any())).thenReturn(delivery(ShipmentStatus.ACCEPTED));
        when(deliveryService.shipmentInTransit(any(), any())).thenReturn(delivery(ShipmentStatus.IN_TRANSIT));
        when(deliveryService.shipmentDelivered(any(), any())).thenReturn(delivery(ShipmentStatus.DELIVERED));
        when(deliveryService.findShipmentsForDistributor(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(delivery(ShipmentStatus.ACCEPTED))));
        when(shipmentService.cancelShipment(any(), any(), any())).thenReturn(null);
    }

    private static DeliveryResponse delivery(ShipmentStatus status) {
        return new DeliveryResponse(50L, status, "Ada Lovelace", "Musterstr. 1", "DHL", null, null, null, null,
                1, BigDecimal.ONE, List.of("PKG-5"), 3L, null, null, null, null);
    }

    /** The three steps of the carrier, each with the id from the path and the acting user. */
    @Test
    void acceptInTransitAndDeliveredPassTheShipmentAndTheUserOn() throws Exception {
        mockMvc.perform(post("/api/1.0/shipments/50/accept")).andExpect(status().isOk());
        mockMvc.perform(post("/api/1.0/shipments/50/intransit")).andExpect(status().isOk());
        mockMvc.perform(post("/api/1.0/shipments/50/delivered")).andExpect(status().isOk());

        // No body: a plain acceptance, no note.
        verify(deliveryService).acceptShipment(50L, null, 99L);
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

    /**
     * There is only one cancel left, and it lives on the planning side with all three roles. The
     * carrier used to have a PUT on the same path, which meant guessing the verb for a 403.
     */
    @Test
    void leavesTheOnlyCancelToThePlanningSide() throws Exception {
        mockMvc.perform(post("/api/1.0/shipments/50/cancel").contentType(APPLICATION_JSON)
                        .content("""
                                { "reason": "repacking" }
                                """))
                .andExpect(status().isOk());

        verify(shipmentService).cancelShipment(eq(50L), any(), eq(99L));
        verifyNoInteractions(deliveryService);
    }

    @Test
    void hasNoCarrierCancelOfItsOwn() throws Exception {
        mockMvc.perform(put("/api/1.0/shipments/50/cancel").contentType(APPLICATION_JSON)
                        .content("""
                                { "reason": "truck broke down" }
                                """))
                .andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(deliveryService);
    }

    /** An unknown status is a 400 from the binder, not a 500. */
    @Test
    void refusesAnUnknownStatus() throws Exception {
        mockMvc.perform(get("/api/1.0/shipments/distributor").param("status", "FLYING"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(deliveryService);
    }

    // ------------------------------------------------------------------ reject

    /** The id from the path, the reason from the body, the acting user - and no other service is asked. */
    @Test
    void rejectPassesTheShipmentTheReasonAndTheUserOn() throws Exception {
        when(deliveryService.rejectShipment(any(), any(), any())).thenReturn(delivery(ShipmentStatus.REJECTED));

        mockMvc.perform(post("/api/1.0/shipments/50/reject").contentType(APPLICATION_JSON)
                        .content("""
                                { "reason": "no truck free" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        verify(deliveryService).rejectShipment(50L, "no truck free", 99L);
        verifyNoInteractions(shipmentService);
    }

    /** The reason is required, as it is on a cancellation: a bean validation, so a 400 before the service is asked. */
    @Test
    void rejectWithoutAReasonIsA400() throws Exception {
        for (String body : new String[]{"{}", "{ \"reason\": \"\" }", "{ \"reason\": \"   \" }", "{ \"reason\": null }"}) {
            mockMvc.perform(post("/api/1.0/shipments/50/reject").contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }

        verifyNoInteractions(deliveryService);
    }

    @Test
    void rejectWithoutABodyIsA400() throws Exception {
        mockMvc.perform(post("/api/1.0/shipments/50/reject")).andExpect(status().isBadRequest());

        verifyNoInteractions(deliveryService);
    }

    /** One more than the column holds is a 400 naming the field, not a database error. */
    @Test
    void rejectWithATooLongReasonIsA400() throws Exception {
        mockMvc.perform(post("/api/1.0/shipments/50/reject").contentType(APPLICATION_JSON)
                        .content("{ \"reason\": \"" + "x".repeat(256) + "\" }"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(deliveryService);
    }

    /** It is the carrier's endpoint, in the carrier's controller - not a second verb on the planner's. */
    @Test
    void rejectIsAPostOnly() throws Exception {
        mockMvc.perform(put("/api/1.0/shipments/50/reject").contentType(APPLICATION_JSON)
                        .content("{ \"reason\": \"x\" }"))
                .andExpect(status().isMethodNotAllowed());
    }

    // ------------------------------------------------------------------ accept with a note

    /** The carrier's note on accepting goes to the service under the id from the path - no other service is asked. */
    @Test
    void acceptPassesTheNoteOn() throws Exception {
        mockMvc.perform(post("/api/1.0/shipments/50/accept").contentType(APPLICATION_JSON)
                        .content("""
                                { "reason": "pick-up on Monday" }
                                """))
                .andExpect(status().isOk());

        verify(deliveryService).acceptShipment(50L, "pick-up on Monday", 99L);
        verifyNoInteractions(shipmentService);
    }

    /** An empty object or a blank note is an acceptance like any other - the note is optional. */
    @Test
    void acceptTakesAnEmptyOrBlankNoteAsNoNote() throws Exception {
        mockMvc.perform(post("/api/1.0/shipments/50/accept").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/1.0/shipments/50/accept").contentType(APPLICATION_JSON).content("{ \"reason\": null }"))
                .andExpect(status().isOk());

        verify(deliveryService, org.mockito.Mockito.times(2)).acceptShipment(50L, null, 99L);
    }

    /** One more than the column holds is a 400 naming the field, not a database error. */
    @Test
    void acceptWithATooLongNoteIsA400() throws Exception {
        mockMvc.perform(post("/api/1.0/shipments/50/accept").contentType(APPLICATION_JSON)
                        .content("{ \"reason\": \"" + "x".repeat(256) + "\" }"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(deliveryService);
    }
}
