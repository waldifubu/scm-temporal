package com.supplychainmanagement.security;

import com.supplychainmanagement.entity.Storehouse;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.repository.StorehouseRepository;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.jayway.jsonpath.JsonPath.read;
import static org.assertj.core.api.Assertions.assertThat;

/** {@code PUT /storehouses/{id}} through the real chain: roles, what is stored, and what is refused. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class StorehouseUpdateEndpointTest {

    private static final AtomicLong CALLS = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private StorehouseRepository storehouseRepository;

    private MockHttpServletResponse post(User as, String json) throws Exception {
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(as.getUsername(), null, List.of()));
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/1.0/storehouses")
                        .contentType(MediaType.APPLICATION_JSON).content(json)
                        .header("X-API-KEY", "storehouse-create-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    @Test
    void createsAStorehouseTrimmedAndAnswers201() throws Exception {
        MockHttpServletResponse response = post(testData.userWithRoles(RoleEnum.WAREHOUSE),
                "{\"name\":\" Süd \",\"city\":\"München\",\"address\":\"\"}");

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        Number id = read(response.getContentAsString(), "$.id");
        Storehouse stored = storehouseRepository.findById(id.longValue()).orElseThrow();
        assertThat(stored.getName()).isEqualTo("Süd");
        assertThat(stored.getCity()).isEqualTo("München");
        assertThat(stored.getAddress()).isNull();
    }

    @Test
    void createIsForAdminAndWarehouseAndNeedsAName() throws Exception {
        assertThat(post(testData.userWithRoles(RoleEnum.ADMIN), "{\"name\":\"A\"}").getStatus()).isEqualTo(201);
        assertThat(post(testData.userWithRoles(RoleEnum.ADMIN), "{\"name\":\" \"}").getStatus()).isEqualTo(400);
        for (RoleEnum role : List.of(RoleEnum.MANAGER, RoleEnum.LOGISTICS, RoleEnum.CUSTOMER)) {
            assertThat(post(testData.userWithRoles(role), "{\"name\":\"B\"}").getStatus()).as(role.name()).isEqualTo(403);
        }
    }

    private MockHttpServletResponse put(User as, Long id, String json) throws Exception {
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(as.getUsername(), null, List.of()));
        return mockMvc.perform(MockMvcRequestBuilders.put("/api/1.0/storehouses/" + id)
                        .contentType(MediaType.APPLICATION_JSON).content(json)
                        .header("X-API-KEY", "storehouse-update-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    @Test
    void storesTheNewDataTrimmedAndAnswersIt() throws Exception {
        Storehouse storehouse = testData.storehouse();
        User warehouse = testData.userWithRoles(RoleEnum.WAREHOUSE);

        MockHttpServletResponse response = put(warehouse, storehouse.getId(),
                "{\"name\":\"  Nord  \",\"address\":\"Hafenstr. 2\",\"city\":\"Hamburg\",\"country\":\"  \"}");

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat((String) read(response.getContentAsString(), "$.name")).isEqualTo("Nord");
        Storehouse stored = storehouseRepository.findById(storehouse.getId()).orElseThrow();
        assertThat(stored.getName()).isEqualTo("Nord");
        assertThat(stored.getAddress()).isEqualTo("Hafenstr. 2");
        assertThat(stored.getCity()).isEqualTo("Hamburg");
        assertThat(stored.getCountry()).as("blank is stored as null").isNull();
    }

    @Test
    void refusesABlankNameAndAnUnknownId() throws Exception {
        Storehouse storehouse = testData.storehouse();
        User admin = testData.userWithRoles(RoleEnum.ADMIN);

        assertThat(put(admin, storehouse.getId(), "{\"name\":\"  \"}").getStatus()).isEqualTo(400);
        assertThat(put(admin, storehouse.getId(), "{}").getStatus()).isEqualTo(400);
        assertThat(put(admin, 987_654_321L, "{\"name\":\"X\"}").getStatus()).isEqualTo(404);
    }

    @Test
    void isForAdminAndWarehouseOnly() throws Exception {
        Storehouse storehouse = testData.storehouse();
        for (RoleEnum role : List.of(RoleEnum.ADMIN, RoleEnum.WAREHOUSE)) {
            assertThat(put(testData.userWithRoles(role), storehouse.getId(), "{\"name\":\"Ok\"}").getStatus())
                    .as(role.name()).isEqualTo(200);
        }
        for (RoleEnum role : List.of(RoleEnum.MANAGER, RoleEnum.LOGISTICS, RoleEnum.CUSTOMER)) {
            assertThat(put(testData.userWithRoles(role), storehouse.getId(), "{\"name\":\"No\"}").getStatus())
                    .as(role.name()).isEqualTo(403);
        }
        assertThat(storehouseRepository.findById(storehouse.getId()).orElseThrow().getName()).isEqualTo("Ok");
    }
}
