package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.component.RequestComponentResponse;
import com.supplychainmanagement.dto.component.RequestComponentsRequest;
import com.supplychainmanagement.dto.mapper.ComponentMapper;
import com.supplychainmanagement.entity.RequestComponent;
import com.supplychainmanagement.exception.GlobalExceptionHandler;
import com.supplychainmanagement.model.enums.RequestStatus;
import com.supplychainmanagement.service.ComponentService;
import com.supplychainmanagement.service.RequestComponentService;
import com.supplychainmanagement.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
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
        mockMvc = MockMvcBuilders.standaloneSetup(new ComponentController(componentService, componentMapper, userService, requestComponentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                // @AuthenticationPrincipal has no resolver in a standalone setup - without it the two
                // supplier steps fail before they reach the controller method.
                .setCustomArgumentResolvers(
                        new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
                .setApiVersionStrategy(ApiVersioningTestSupport.apiVersionStrategy())
                .build();

        when(componentService.requestComponents(any(), any())).thenReturn(List.of(
                new RequestComponentResponse(1L, 5L, SCREW, "screw", 12L, "Notwendig",
                        RequestStatus.OPEN, null, 12L)));
    }

    private RequestComponentsRequest bound() {
        ArgumentCaptor<RequestComponentsRequest> captor = ArgumentCaptor.forClass(RequestComponentsRequest.class);
        verify(componentService).requestComponents(eq(12L), captor.capture());
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

    private static RequestComponentResponse answered(RequestStatus status) {
        return new RequestComponentResponse(5L, 11L, SCREW, "Blech", 12L, null, status, null, 315L);
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
