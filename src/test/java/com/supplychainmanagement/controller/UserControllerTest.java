package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.mapper.UserMapper;
import com.supplychainmanagement.dto.user.DistributorQueryRequest;
import com.supplychainmanagement.dto.user.UserRequestDto;
import com.supplychainmanagement.entity.users.Distributor;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.exception.GlobalExceptionHandler;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The user endpoints bind a UserRequestDto, not the User entity: only the fields a client may set
 * reach the service, roles arrive as names, and entity-only fields in the body are ignored.
 */
class UserControllerTest {

    private final UserService userService = mock(UserService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new UserController(userService, Mappers.getMapper(UserMapper.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(
                        new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
                .setApiVersionStrategy(ApiVersioningTestSupport.apiVersionStrategy())
                .build();

        User saved = new User();
        saved.setId(9L);
        when(userService.create(any(UserRequestDto.class), any())).thenReturn(saved);
        when(userService.update(any(), any(UserRequestDto.class))).thenReturn(saved);
    }

    @Test
    void createBindsTheRequestDtoWithRoleNames() throws Exception {
        mockMvc.perform(post("/api/1.0/users").contentType(APPLICATION_JSON)
                        .content("""
                                { "firstName": "Ada", "lastName": "Lovelace", "username": "ada",
                                  "email": "ada@example.com", "password": "secret1",
                                  "isActive": false, "roles": ["MANAGER", "warehouse"] }
                                """))
                .andExpect(status().isCreated());

        ArgumentCaptor<UserRequestDto> captor = ArgumentCaptor.forClass(UserRequestDto.class);
        verify(userService).create(captor.capture(), any());
        UserRequestDto request = captor.getValue();
        assertThat(request.username()).isEqualTo("ada");
        assertThat(request.isActive()).isFalse();
        assertThat(request.roles()).isEqualTo(Set.of(RoleEnum.MANAGER, RoleEnum.WAREHOUSE));
    }

    /** Fields only the entity has - id, userType, createdAt - do not bind to anything any more. */
    @Test
    void updateIgnoresEntityOnlyFields() throws Exception {
        mockMvc.perform(put("/api/1.0/users/9").contentType(APPLICATION_JSON)
                        .content("""
                                { "id": 1, "userType": "Admin", "createdAt": "2020-01-01T00:00:00",
                                  "firstName": "Ada", "lastName": "Lovelace", "username": "ada",
                                  "email": "ada@example.com" }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<UserRequestDto> captor = ArgumentCaptor.forClass(UserRequestDto.class);
        verify(userService).update(eq(9L), captor.capture());
        assertThat(captor.getValue().firstName()).isEqualTo("Ada");
        assertThat(captor.getValue().roles()).isNull();
    }

    /** A role name the enum does not know is a 400, not a silently dropped role. */
    @Test
    void rejectsAnUnknownRoleName() throws Exception {
        mockMvc.perform(post("/api/1.0/users").contentType(APPLICATION_JSON)
                        .content("""
                                { "username": "ada", "roles": ["SUPERUSER"] }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(userService);
    }

    /** The question is in the body: customer, weight in kg, volume in cm3 - and the answer is a page, best carrier first. */
    @Test
    void queryDistributorsBindsTheBodyAndAnswersAPage() throws Exception {
        Distributor first = new Distributor();
        first.setId(319L);
        Distributor second = new Distributor();
        second.setId(320L);
        when(userService.queryDistributors(any(DistributorQueryRequest.class))).thenReturn(List.of(first, second));

        mockMvc.perform(request(org.springframework.http.HttpMethod.QUERY, "/api/1.0/users/distributors/query").contentType(APPLICATION_JSON)
                        .content("""
                                { "customerId": 311, "weight": 60.5, "volume": 250000 }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(319))
                .andExpect(jsonPath("$.content[1].id").value(320))
                .andExpect(jsonPath("$.total").value(2));

        ArgumentCaptor<DistributorQueryRequest> captor = ArgumentCaptor.forClass(DistributorQueryRequest.class);
        verify(userService).queryDistributors(captor.capture());
        assertThat(captor.getValue().customerId()).isEqualTo(311L);
        assertThat(captor.getValue().weight()).isEqualByComparingTo(new BigDecimal("60.5"));
        assertThat(captor.getValue().volume()).isEqualByComparingTo(new BigDecimal("250000"));
    }

    @Test
    void queryDistributorsAnswersAnEmptyPageWhenNoRuleApplies() throws Exception {
        when(userService.queryDistributors(any(DistributorQueryRequest.class))).thenReturn(List.of());

        mockMvc.perform(request(org.springframework.http.HttpMethod.QUERY, "/api/1.0/users/distributors/query").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void queryDistributorsRefusesANegativeWeightOrVolume() throws Exception {
        mockMvc.perform(request(org.springframework.http.HttpMethod.QUERY, "/api/1.0/users/distributors/query").contentType(APPLICATION_JSON)
                        .content("{ \"weight\": -1 }"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(request(org.springframework.http.HttpMethod.QUERY, "/api/1.0/users/distributors/query").contentType(APPLICATION_JSON)
                        .content("{ \"volume\": -5 }"))
                .andExpect(status().isBadRequest());

        verify(userService, org.mockito.Mockito.never()).queryDistributors(any());
    }
}
