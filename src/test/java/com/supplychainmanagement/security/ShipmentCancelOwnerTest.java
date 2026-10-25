package com.supplychainmanagement.security;

import com.supplychainmanagement.entity.Shipment;
import com.supplychainmanagement.entity.users.Distributor;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.model.enums.ShipmentStatus;
import com.supplychainmanagement.repository.ShipmentRepository;
import com.supplychainmanagement.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /shipments/{id}/cancel} against the real filter chain, the real roles and the real rows:
 * DISTRIBUTOR is allowed to call the endpoint - a carrier has to be able to refuse what was handed to
 * it - but only for the shipment assigned to them. Another carrier, who needs nothing but the id, is a
 * 403 and the shipment, with the planner's note on it, stays as it was.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class ShipmentCancelOwnerTest {

    private static final AtomicLong CALLS = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private ShipmentRepository shipmentRepository;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private MockHttpServletResponse cancel(Long shipmentId, User as, String reason) throws Exception {
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(as.getUsername(), null, List.of()));
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/1.0/shipments/" + shipmentId + "/cancel")
                        .contentType("application/json")
                        .content("{\"reason\":\"" + reason + "\"}")
                        .header("X-API-KEY", "cancel-owner-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    private Distributor carrier() {
        return (Distributor) testData.userWithRoles(RoleEnum.DISTRIBUTOR);
    }

    private Shipment shipmentOf(Distributor assigned) {
        Shipment shipment = new Shipment();
        shipment.setCustomer(testData.customer());
        shipment.setDistributor(assigned);
        shipment.setStatus(ShipmentStatus.DISPATCH_REQUESTED);
        shipment.setCreatedAt(LocalDateTime.now());
        shipment.setComment("call first");
        return shipmentRepository.saveAndFlush(shipment);
    }

    private Shipment reload(Shipment shipment) {
        return shipmentRepository.findById(shipment.getId()).orElseThrow();
    }

    @Test
    void theAssignedCarrierMayCancel() throws Exception {
        Distributor owner = carrier();
        Shipment shipment = shipmentOf(owner);

        MockHttpServletResponse response = cancel(shipment.getId(), owner, "no truck");

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(reload(shipment).getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(reload(shipment).getComment()).isEqualTo("no truck");
    }

    @Test
    void anotherCarrierGetsA403AndTheShipmentStaysAsItWas() throws Exception {
        Shipment shipment = shipmentOf(carrier());

        MockHttpServletResponse response = cancel(shipment.getId(), carrier(), "not mine");

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(reload(shipment).getStatus()).isEqualTo(ShipmentStatus.DISPATCH_REQUESTED);
        assertThat(reload(shipment).getComment()).isEqualTo("call first");
    }

    @Test
    void aCarrierGetsA403ForAShipmentNobodyWasAssigned() throws Exception {
        Shipment shipment = shipmentOf(null);

        assertThat(cancel(shipment.getId(), carrier(), "why").getStatus()).isEqualTo(403);
        assertThat(reload(shipment).getStatus()).isEqualTo(ShipmentStatus.DISPATCH_REQUESTED);
    }

    @Test
    void logisticsAndAdminMayCancelAShipmentOfAnyCarrier() throws Exception {
        Shipment first = shipmentOf(carrier());
        Shipment second = shipmentOf(carrier());

        assertThat(cancel(first.getId(), testData.userWithRoles(RoleEnum.LOGISTICS), "planner").getStatus()).isEqualTo(200);
        assertThat(cancel(second.getId(), testData.userWithRoles(RoleEnum.ADMIN), "admin").getStatus()).isEqualTo(200);
        assertThat(reload(first).getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(reload(second).getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
    }

    @Test
    void otherRolesStayOutAsBefore() throws Exception {
        Shipment shipment = shipmentOf(carrier());

        assertThat(cancel(shipment.getId(), testData.userWithRoles(RoleEnum.WAREHOUSE), "x").getStatus()).isEqualTo(403);
        assertThat(cancel(shipment.getId(), testData.userWithRoles(RoleEnum.CUSTOMER), "x").getStatus()).isEqualTo(403);
    }
}
