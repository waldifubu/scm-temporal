package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.component.ComponentResponseDto;
import com.supplychainmanagement.dto.component.ProductRefDto;
import com.supplychainmanagement.dto.component.RequestComponentResponse;
import com.supplychainmanagement.dto.component.RequestComponentResponseDto;
import com.supplychainmanagement.exception.GlobalExceptionHandler;
import com.supplychainmanagement.model.enums.RequestStatus;
import com.supplychainmanagement.service.ComponentService;
import com.supplychainmanagement.service.RequestComponentService;
import com.supplychainmanagement.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The inbound side of a component request as a client calls it: the supplier's own list and the five
 * steps they report.
 * <p>
 * These endpoints live in {@link InboundController} and keep the {@code /api/&#123;version&#125;/components}
 * base path, so the URLs are the ones clients already know - only the class they sit in changed.
 * The tests moved with them out of {@code ComponentControllerTest}, which now covers the catalogue,
 * the ordering endpoint and the warehouse's side.
 */
class InboundControllerTest {

    private static final UUID SCREW = UUID.fromString("706a99c3-944b-11f1-9b51-001e064520d8");

    private final ComponentService componentService = mock(ComponentService.class);
    private final RequestComponentService requestComponentService = mock(RequestComponentService.class);
    private final UserService userService = mock(UserService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new InboundController(componentService, requestComponentService, userService))
                .setControllerAdvice(new GlobalExceptionHandler())
                // @AuthenticationPrincipal has no resolver in a standalone setup - without it every
                // one of these fails before it reaches the controller method.
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setApiVersionStrategy(ApiVersioningTestSupport.apiVersionStrategy())
                .build();
    }


    /** The supplier's two steps: the request id from the path and the acting user from the token. */
    @Test
    void passesTheSupplierStepsOn() throws Exception {
        when(userService.getAuthenticatedUserId(any())).thenReturn(315L);
        when(componentService.approveRequest(any(), any()))
                .thenReturn(answered(RequestStatus.APPROVED));
        when(componentService.requestInTransit(any(), any()))
                .thenReturn(answered(RequestStatus.IN_TRANSIT));

        mockMvc.perform(post("/api/1.0/components/supplier/5/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("APPROVED"));
        mockMvc.perform(post("/api/1.0/components/supplier/5/in-transit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("IN_TRANSIT"));

        verify(componentService).approveRequest(5L, 315L);
        verify(componentService).requestInTransit(5L, 315L);
    }


    /** The supplier's last step, on its own path - nothing is booked by it. */
    @Test
    void passesTheHandoverOn() throws Exception {
        when(userService.getAuthenticatedUserId(any())).thenReturn(315L);
        when(componentService.requestDelivered(any(), any()))
                .thenReturn(answered(RequestStatus.DELIVERED));

        mockMvc.perform(post("/api/1.0/components/supplier/5/delivered"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("DELIVERED"));

        verify(componentService).requestDelivered(5L, 315L);
    }


    /**
     * The supplier's own list. The component comes as a nested DTO, which is the point: the field
     * used to hold the Component entity, so the response carried the component's product along and
     * resolved the graph through the open-in-view session.
     */
    @Test
    void listsTheSuppliersOwnRequests() throws Exception {
        when(userService.getAuthenticatedUserId(any())).thenReturn(315L);
        when(requestComponentService.findMyRequests(315L)).thenReturn(List.of(myRequest(12L)));

        mockMvc.perform(get("/api/1.0/components/my-requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].requestStatus").value("APPROVED"))
                .andExpect(jsonPath("$[0].qty").value(12))
                .andExpect(jsonPath("$[0].component.sku").value(SCREW.toString()))
                .andExpect(jsonPath("$[0].component.name").value("Blech"))
                // The product is a reference, not the entity - two fields and nothing else.
                .andExpect(jsonPath("$[0].component.product.articleNo").value(900))
                .andExpect(jsonPath("$[0].component.product.components").doesNotExist());

        verify(requestComponentService).findMyRequests(315L);
    }


    /**
     * The ordered quantity is a long on both sides now. It was an Integer in the DTO against the
     * entity's Long, copied from the component's bill-of-materials qty sitting in the same response,
     * and MapStruct narrowed it without a word.
     */
    @Test
    void answersAQuantityBeyondTheIntRange() throws Exception {
        when(userService.getAuthenticatedUserId(any())).thenReturn(315L);
        when(requestComponentService.findMyRequests(315L))
                .thenReturn(List.of(myRequest(3_000_000_000L)));

        mockMvc.perform(get("/api/1.0/components/my-requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].qty").value(3_000_000_000L))
                // The nested qty is the recipe quantity and stays what it is - the two are not one.
                .andExpect(jsonPath("$[0].component.qty").value(4));
    }


    /** The two ways a request stops without goods, both the supplier's. */
    @Test
    void passesRejectAndCancelOn() throws Exception {
        when(userService.getAuthenticatedUserId(any())).thenReturn(315L);
        when(componentService.rejectRequest(any(), any())).thenReturn(answered(RequestStatus.REJECTED));
        when(componentService.cancelRequest(any(), any())).thenReturn(answered(RequestStatus.CANCELLED));

        mockMvc.perform(post("/api/1.0/components/supplier/5/reject"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("REJECTED"));
        mockMvc.perform(post("/api/1.0/components/supplier/5/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("CANCELLED"));

        verify(componentService).rejectRequest(5L, 315L);
        verify(componentService).cancelRequest(5L, 315L);
    }


    /** Neither takes a body - there is no column for a reason, and comment belongs to the orderer. */
    @Test
    void cancelsWithoutABody() throws Exception {
        when(componentService.cancelRequest(any(), any())).thenReturn(answered(RequestStatus.CANCELLED));

        mockMvc.perform(post("/api/1.0/components/supplier/5/cancel"))
                .andExpect(status().isOk());
    }


    /**
     * One row of the supplier's list, over the given ordered quantity. The nested component carries
     * its own qty - how many go into one product - which is a different number entirely.
     */
    private static RequestComponentResponseDto myRequest(Long orderedQty) {
        ComponentResponseDto component = new ComponentResponseDto(11L, "ACME", "Blech",
                SCREW.toString(), "A-11", 4, new ProductRefDto(900L, "Regal"));
        return new RequestComponentResponseDto(5L, null, null, RequestStatus.APPROVED, null,
                orderedQty, component);
    }

    /** One request as the API answers it, in the given status. */
    private static RequestComponentResponse answered(RequestStatus status) {
        return new RequestComponentResponse(5L, 11L, SCREW, "Blech", 12L, null, status, null, 315L,
                77L, "Rita Rack", null);
    }

}
