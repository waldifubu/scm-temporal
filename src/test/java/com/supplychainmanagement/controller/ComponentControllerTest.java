package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.component.ComponentResponseDto;
import com.supplychainmanagement.dto.component.ProductRefDto;
import com.supplychainmanagement.dto.component.RequestComponentResponse;
import com.supplychainmanagement.dto.component.RequestComponentResponseDto;
import com.supplychainmanagement.dto.component.RequestComponentsRequest;
import com.supplychainmanagement.dto.mapper.ComponentMapper;
import com.supplychainmanagement.entity.RequestComponent;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.GlobalExceptionHandler;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.model.enums.RequestStatus;
import com.supplychainmanagement.service.ComponentService;
import com.supplychainmanagement.service.RequestComponentService;
import com.supplychainmanagement.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Ordering components as a client sends it: both body shapes, validation and status codes. */
class ComponentControllerTest {

    private static final UUID SCREW = UUID.fromString("706a99c3-944b-11f1-9b51-001e064520d8");

    private final ComponentService componentService = mock(ComponentService.class);
    private final ComponentMapper componentMapper = mock(ComponentMapper.class);
    private final UserService userService = mock(UserService.class);
    private final RequestComponentService requestComponentService = mock(RequestComponentService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ComponentController(
                        componentService, componentMapper, userService, requestComponentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                // @AuthenticationPrincipal has no resolver in a standalone setup - without it the two
                // supplier steps fail before they reach the controller method.
                .setCustomArgumentResolvers(
                        new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
                .setApiVersionStrategy(ApiVersioningTestSupport.apiVersionStrategy())
                .build();

        when(componentService.requestComponents(any(), any(), any())).thenReturn(List.of(
                new RequestComponentResponse(1L, 5L, SCREW, "screw", 12L, "Notwendig",
                        RequestStatus.OPEN, null, 12L, 77L, "Rita Rack", null)));
    }

    private RequestComponentsRequest bound() {
        ArgumentCaptor<RequestComponentsRequest> captor = ArgumentCaptor.forClass(RequestComponentsRequest.class);
        verify(componentService).requestComponents(eq(12L), captor.capture(), any());
        return captor.getValue();
    }

    /** The wrapped form, and the supplier from the path. */
    @Test
    void placesARequestFromTheWrappedBody() throws Exception {
        mockMvc.perform(post("/api/1.0/components/request/12").contentType(APPLICATION_JSON)
                        .content("""
                                { "items": [
                                    { "componentId": "706a99c3-944b-11f1-9b51-001e064520d8",
                                      "comment": "Notwendig für umme", "qty": 12 }
                                ] }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].componentId").value(SCREW.toString()))
                .andExpect(jsonPath("$[0].requestStatus").value("OPEN"));

        assertThat(bound().items()).singleElement().satisfies(item -> {
            assertThat(item.componentId()).isEqualTo(SCREW);
            assertThat(item.qty()).isEqualTo(12L);
            assertThat(item.comment()).isEqualTo("Notwendig für umme");
        });
    }

    /** The bare array binds to the same record - the project's id lists accept both shapes as well. */
    @Test
    void placesARequestFromTheBareArray() throws Exception {
        mockMvc.perform(post("/api/1.0/components/request/12").contentType(APPLICATION_JSON)
                        .content("""
                                [ { "componentId": "706a99c3-944b-11f1-9b51-001e064520d8", "qty": 12 } ]
                                """))
                .andExpect(status().isCreated());

        assertThat(bound().items()).singleElement().satisfies(item -> {
            assertThat(item.componentId()).isEqualTo(SCREW);
            assertThat(item.comment()).isNull();
        });
    }

    /** The comment is optional. */
    @Test
    void acceptsALineWithoutAComment() throws Exception {
        mockMvc.perform(post("/api/1.0/components/request/12").contentType(APPLICATION_JSON)
                        .content("""
                                [ { "componentId": "706a99c3-944b-11f1-9b51-001e064520d8", "qty": 1 } ]
                                """))
                .andExpect(status().isCreated());
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

    // ------------------------------------------------------------------ the warehouse's work list

    /**
     * The work list, paged, oldest first - a pile at the dock is worked off in the order it arrived,
     * which is why the default sort is requestDate and not id.
     */
    @Test
    void listsTheRequestsOldestFirst() throws Exception {
        when(componentService.findRequests(any(), any()))
                .thenAnswer(call -> new PageImpl<>(List.of(answered(RequestStatus.DELIVERED)),
                        call.<Pageable>getArgument(1), 1));

        mockMvc.perform(get("/api/1.0/components/requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.size").value(25))
                .andExpect(jsonPath("$.content[0].requestStatus").value("DELIVERED"))
                .andExpect(jsonPath("$.content[0].assignedByName").value("Rita Rack"));

        verify(componentService).findRequests(isNull(), argThat((Pageable pageable) ->
                pageable.getPageNumber() == 0 && pageable.getPageSize() == 25
                        && Sort.by(Sort.Direction.ASC, "requestDate").equals(pageable.getSort())));
    }

    /** The status the warehouse actually works off, passed through as an enum. */
    @Test
    void narrowsTheWorkListToAStatus() throws Exception {
        when(componentService.findRequests(any(), any()))
                .thenAnswer(call -> new PageImpl<>(List.of(), call.<Pageable>getArgument(1), 0));

        mockMvc.perform(get("/api/1.0/components/requests").param("status", "DELIVERED"))
                .andExpect(status().isOk());

        verify(componentService).findRequests(eq(RequestStatus.DELIVERED), any(Pageable.class));
    }

    /**
     * The literal segment wins over GET /components/{sku} - without that, "requests" would be bound
     * as a UUID and answered with a 400. Same arrangement as /components/my-requests.
     */
    @Test
    void readsRequestsAsTheListAndNotAsASku() throws Exception {
        when(componentService.findRequests(any(), any()))
                .thenAnswer(call -> new PageImpl<>(List.of(), call.<Pageable>getArgument(1), 0));

        mockMvc.perform(get("/api/1.0/components/requests"))
                .andExpect(status().isOk());

        verify(componentService, never()).findBySku(any());
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

    // ------------------------------------------------------------------ the goods receipt

    /**
     * The receipt takes two path variables - which request, and which storehouse the goods actually
     * arrived in. The acting user comes from the token, never from the path.
     */
    @Test
    void booksADeliveryInWithRequestAndStorehouseFromThePath() throws Exception {
        when(userService.getAuthenticatedUserId(any())).thenReturn(77L);
        when(componentService.receiveRequest(any(), any(), any()))
                .thenReturn(answered(RequestStatus.IN_STOCK));

        mockMvc.perform(post("/api/1.0/components/warehouse/5/in-stock/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("IN_STOCK"))
                .andExpect(jsonPath("$.qty").value(12))
                .andExpect(jsonPath("$.componentId").value(SCREW.toString()));

        verify(componentService).receiveRequest(5L, 3L, 77L);
    }

    /** A status that cannot be booked in reaches the client as a 409 through the global handler. */
    @Test
    void answersAWrongStatusWith409() throws Exception {
        when(componentService.receiveRequest(any(), any(), any())).thenThrow(new APIException(
                HttpStatus.CONFLICT, "Request 5 is IN_TRANSIT, only DELIVERED can be booked in"));

        mockMvc.perform(post("/api/1.0/components/warehouse/5/in-stock/3"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Request 5 is IN_TRANSIT, only DELIVERED can be booked in"));
    }

    /** A wrong storehouse in the path is a 404, not the 500 StockService would have produced. */
    @Test
    void answersAnUnknownStorehouseWith404() throws Exception {
        when(componentService.receiveRequest(any(), any(), any()))
                .thenThrow(new ResourceNotFoundException("Storehouse", "id", 99L));

        mockMvc.perform(post("/api/1.0/components/warehouse/5/in-stock/99"))
                .andExpect(status().isNotFound());
    }

    /** A path variable that is not a number never reaches the service. */
    @Test
    void refusesANonNumericStorehouse() throws Exception {
        mockMvc.perform(post("/api/1.0/components/warehouse/5/in-stock/lager-eins"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(componentService);
    }

    private static RequestComponentResponse answered(RequestStatus status) {
        return new RequestComponentResponse(5L, 11L, SCREW, "Blech", 12L, null, status, null, 315L,
                77L, "Rita Rack", null);
    }

    /** Nothing to order is not a request. */
    @Test
    void refusesAnEmptyList() throws Exception {
        mockMvc.perform(post("/api/1.0/components/request/12").contentType(APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(componentService);
    }

    /** A quantity below one would be an order for nothing. */
    @Test
    void refusesAQuantityBelowOne() throws Exception {
        mockMvc.perform(post("/api/1.0/components/request/12").contentType(APPLICATION_JSON)
                        .content("""
                                [ { "componentId": "706a99c3-944b-11f1-9b51-001e064520d8", "qty": 0 } ]
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(componentService);
    }

    /**
     * The upper bound on a line, refused before anything is placed - a 400 naming the field rather
     * than a quantity the goods receipt would have to turn away later.
     */
    @Test
    void refusesAQuantityAboveTheLimit() throws Exception {
        mockMvc.perform(post("/api/1.0/components/request/12").contentType(APPLICATION_JSON)
                        .content("""
                                [ { "componentId": "706a99c3-944b-11f1-9b51-001e064520d8",
                                    "qty": 3000000000 } ]
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(componentService);
    }

    /** The limit itself still goes through - it is a maximum, not a boundary to stay under. */
    @Test
    void acceptsTheHighestAllowedQuantity() throws Exception {
        mockMvc.perform(post("/api/1.0/components/request/12").contentType(APPLICATION_JSON)
                        .content("""
                                [ { "componentId": "706a99c3-944b-11f1-9b51-001e064520d8",
                                    "qty": 1000000 } ]
                                """))
                .andExpect(status().isCreated());
    }

    /** Which component is asked for is not optional. */
    @Test
    void refusesALineWithoutAComponent() throws Exception {
        mockMvc.perform(post("/api/1.0/components/request/12").contentType(APPLICATION_JSON)
                        .content("""
                                [ { "qty": 3, "comment": "welches denn?" } ]
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(componentService);
    }
}
