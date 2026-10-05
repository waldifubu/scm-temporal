package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.picking.PickingOrderDto;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.GlobalExceptionHandler;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.ReservationStatus;
import com.supplychainmanagement.service.OrderHandlingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The picking endpoints as a client calls them. There was no test for this controller at all, which
 * mattered once its two local {@code catch (APIException)} blocks were removed: half of that change
 * would have been unverified.
 * <p>
 * The point of these is the <strong>error shape</strong>. Both endpoints used to catch the exception
 * themselves and answer a bare {@code {"message": ...}} map; they now let it reach
 * {@link GlobalExceptionHandler}, which answers {@code ErrorDetails}. The message survives
 * unchanged - that record carries one - and the timestamp, the path and an error code come with it.
 */
class PickingControllerTest {

    private static final UUID SKU = UUID.fromString("706a99c3-944b-11f1-9b51-001e064520d8");

    private final OrderHandlingService orderHandlingService = mock(OrderHandlingService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PickingController(orderHandlingService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setApiVersionStrategy(ApiVersioningTestSupport.apiVersionStrategy())
                .build();
    }

    private static PickingOrderDto picked(Long reservationId) {
        return new PickingOrderDto(reservationId, 91234L, 11L, 900L, "Regal", SKU, 2, 1L,
                "Lager", FulfillmentStatus.PICKED, LocalDateTime.of(2026, 10, 5, 12, 0));
    }

    /** One reservation picked - the row itself, no envelope around it. */
    @Test
    void picksOneReservation() throws Exception {
        when(orderHandlingService.pickingReservationById(7L)).thenReturn(picked(7L));

        mockMvc.perform(post("/api/1.0/picking/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(7))
                .andExpect(jsonPath("$.fulfillmentStatus").value("PICKED"));
    }

    /** A whole order picked - the rows as a list. */
    @Test
    void picksAWholeOrder() throws Exception {
        when(orderHandlingService.pickingReservationByOrderNo("91234"))
                .thenReturn(List.of(picked(7L), picked(8L)));

        mockMvc.perform(post("/api/1.0/picking/order/91234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reservationId").value(7))
                .andExpect(jsonPath("$[1].reservationId").value(8));
    }

    /**
     * The change itself: a rule broken in the service reaches the client at its own status, through
     * the global handler, and the message is still there under the same name.
     */
    @Test
    void answersAConflictFromTheServiceAsErrorDetails() throws Exception {
        when(orderHandlingService.pickingReservationById(7L)).thenThrow(new APIException(
                HttpStatus.CONFLICT, "Reservation 7 is CONSUMED, only an ACTIVE one can be picked"));

        mockMvc.perform(post("/api/1.0/picking/7"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Reservation 7 is CONSUMED, only an ACTIVE one can be picked"))
                // What the local catch could not answer: where it happened and what kind it was.
                .andExpect(jsonPath("$.path").exists())
                .andExpect(jsonPath("$.errorCode").value("API_ERROR"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    /** The same for picking a whole order - both endpoints caught it themselves before. */
    @Test
    void answersAConflictOnTheOrderEndpointAsErrorDetails() throws Exception {
        when(orderHandlingService.pickingReservationByOrderNo("91234")).thenThrow(
                new APIException(HttpStatus.BAD_REQUEST, "Order 91234 holds no active reservation"));

        mockMvc.perform(post("/api/1.0/picking/order/91234"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Order 91234 holds no active reservation"))
                .andExpect(jsonPath("$.errorCode").value("API_ERROR"));
    }

    /** Nothing to pick for that order is a 404, not an empty list. */
    @Test
    void answersAnOrderWithNothingToPickWith404() throws Exception {
        when(orderHandlingService.pickingReservationByOrderNo("91234")).thenReturn(List.of());

        mockMvc.perform(post("/api/1.0/picking/order/91234"))
                .andExpect(status().isNotFound());
    }

    /** The work list, paged like every other list, sorted by expiry so the most urgent comes first. */
    @Test
    void listsThePickingOrdersByExpiryFirst() throws Exception {
        when(orderHandlingService.pickingOrders(any(), any()))
                .thenAnswer(call -> new PageImpl<>(List.of(picked(7L)), call.getArgument(1), 1));

        mockMvc.perform(get("/api/1.0/picking-orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.content[0].reservationId").value(7));

        verify(orderHandlingService).pickingOrders(eq(ReservationStatus.ACTIVE), any(Pageable.class));
    }
}
