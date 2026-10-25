package com.supplychainmanagement.security;

import com.supplychainmanagement.entity.Shipment;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.entity.users.Distributor;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
import com.supplychainmanagement.model.enums.ShipmentPackageType;
import com.supplychainmanagement.model.enums.ShipmentStatus;
import com.supplychainmanagement.repository.ShipmentPackageRepository;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /shipments/{id}/reject} against the real filter chain, the real roles and the real rows.
 * <p>
 * What a context can show that the unit tests cannot: the database takes the new status at all
 * ({@code shipment.status} carries a CHECK that {@code StatusCheckConstraintMigration} has to widen), the
 * reason really lands in {@code distributor_comment}, and the shipment keeps everything else - the planner's
 * comment, the carrier, and above all its package.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class ShipmentRejectTest {

    private static final AtomicLong CALLS = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private ShipmentRepository shipmentRepository;
    @Autowired
    private ShipmentPackageRepository shipmentPackageRepository;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private ObjectMapper objectMapper;

    private MockHttpServletResponse reject(Long shipmentId, User as, String body) throws Exception {
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(as.getUsername(), null, List.of()));
        var request = MockMvcRequestBuilders.post("/api/1.0/shipments/" + shipmentId + "/reject")
                .header("X-API-KEY", "reject-" + CALLS.incrementAndGet())
                .header("Authorization", "Bearer " + token);
        if (body != null) {
            request = request.contentType("application/json").content(body);
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private Distributor carrier() {
        return (Distributor) testData.userWithRoles(RoleEnum.DISTRIBUTOR);
    }

    private Shipment shipmentOf(Distributor assigned, ShipmentStatus status) {
        Shipment shipment = new Shipment();
        shipment.setCustomer(testData.customer());
        shipment.setDistributor(assigned);
        shipment.setStatus(status);
        shipment.setCreatedAt(LocalDateTime.now());
        shipment.setComment("call first");
        return shipmentRepository.saveAndFlush(shipment);
    }

    private Shipment reload(Shipment shipment) {
        return shipmentRepository.findById(shipment.getId()).orElseThrow();
    }

    @Test
    void theAssignedCarrierRejectsAndTheReasonIsKept() throws Exception {
        Distributor owner = carrier();
        Shipment shipment = shipmentOf(owner, ShipmentStatus.DISPATCH_REQUESTED);

        MockHttpServletResponse response = reject(shipment.getId(), owner, "{\"reason\":\" no truck free \"}");

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("status").asString()).isEqualTo("REJECTED");
        assertThat(body.get("distributorComment").asString()).isEqualTo("no truck free");

        // Read back from the database, not from the answer: the CHECK on shipment.status took the new value.
        Shipment stored = reload(shipment);
        assertThat(stored.getStatus()).isEqualTo(ShipmentStatus.REJECTED);
        assertThat(stored.getDistributorComment()).isEqualTo("no truck free");
    }

    /** The rejection is the status and the reason - not the planner's note, not the assignment. */
    @Test
    void leavesTheRestOfTheShipmentAlone() throws Exception {
        Distributor owner = carrier();
        Shipment shipment = shipmentOf(owner, ShipmentStatus.DISPATCH_REQUESTED);

        reject(shipment.getId(), owner, "{\"reason\":\"no\"}");

        Shipment stored = reload(shipment);
        assertThat(stored.getComment()).isEqualTo("call first");
        assertThat(stored.getDistributor().getId()).isEqualTo(owner.getId());
        assertThat(stored.getShippedAt()).isNull();
    }

    @Test
    void keepsItsPackagesInTheShipment() throws Exception {
        Distributor owner = carrier();
        Shipment shipment = shipmentOf(owner, ShipmentStatus.DISPATCH_REQUESTED);
        ShipmentPackage shipmentPackage = new ShipmentPackage();
        shipmentPackage.setPackageNumber("PKG-REJECT-" + CALLS.incrementAndGet());
        shipmentPackage.setShipmentPackageStatus(ShipmentPackageStatus.PACKED);
        shipmentPackage.setShipmentPackageType(ShipmentPackageType.CARTON);
        // The shipment owns no cascade to its packages: the package is saved itself, with the shipment set.
        shipmentPackage.setShipment(shipment);
        shipmentPackage = shipmentPackageRepository.saveAndFlush(shipmentPackage);

        assertThat(reject(shipment.getId(), owner, "{\"reason\":\"no\"}").getStatus()).isEqualTo(200);

        ShipmentPackage stored = shipmentPackageRepository.findById(shipmentPackage.getId()).orElseThrow();
        assertThat(stored.getShipment()).isNotNull();
        assertThat(stored.getShipment().getId()).isEqualTo(shipment.getId());
        assertThat(stored.getShipmentPackageStatus()).isEqualTo(ShipmentPackageStatus.PACKED);
    }

    @Test
    void anotherCarrierGetsA403AndTheShipmentStaysAsItWas() throws Exception {
        Shipment shipment = shipmentOf(carrier(), ShipmentStatus.DISPATCH_REQUESTED);

        assertThat(reject(shipment.getId(), carrier(), "{\"reason\":\"not mine\"}").getStatus()).isEqualTo(403);

        assertThat(reload(shipment).getStatus()).isEqualTo(ShipmentStatus.DISPATCH_REQUESTED);
        assertThat(reload(shipment).getDistributorComment()).isNull();
    }

    @Test
    void aShipmentThatWasAlreadyAcceptedCannotBeRejected() throws Exception {
        Distributor owner = carrier();
        Shipment shipment = shipmentOf(owner, ShipmentStatus.ACCEPTED);

        assertThat(reject(shipment.getId(), owner, "{\"reason\":\"changed my mind\"}").getStatus()).isEqualTo(409);

        assertThat(reload(shipment).getStatus()).isEqualTo(ShipmentStatus.ACCEPTED);
    }

    @Test
    void aMissingOrBlankReasonIsA400AndWritesNothing() throws Exception {
        Distributor owner = carrier();
        Shipment shipment = shipmentOf(owner, ShipmentStatus.DISPATCH_REQUESTED);

        assertThat(reject(shipment.getId(), owner, "{}").getStatus()).isEqualTo(400);
        assertThat(reject(shipment.getId(), owner, "{\"reason\":\"  \"}").getStatus()).isEqualTo(400);
        assertThat(reject(shipment.getId(), owner, null).getStatus()).isEqualTo(400);

        assertThat(reload(shipment).getStatus()).isEqualTo(ShipmentStatus.DISPATCH_REQUESTED);
    }

    /** A reason a database column cannot hold is a 400 that names the field, not a 500. */
    @Test
    void aReasonLongerThanTheColumnIsA400() throws Exception {
        Distributor owner = carrier();
        Shipment shipment = shipmentOf(owner, ShipmentStatus.DISPATCH_REQUESTED);

        MockHttpServletResponse response = reject(shipment.getId(), owner, "{\"reason\":\"" + "x".repeat(256) + "\"}");

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(reload(shipment).getStatus()).isEqualTo(ShipmentStatus.DISPATCH_REQUESTED);
    }

    @Test
    void adminMayRejectOnBehalfOfTheCarrierAndOtherRolesStayOut() throws Exception {
        Shipment shipment = shipmentOf(carrier(), ShipmentStatus.DISPATCH_REQUESTED);

        // The planning side calls a shipment off; it does not answer for the carrier.
        assertThat(reject(shipment.getId(), testData.userWithRoles(RoleEnum.LOGISTICS), "{\"reason\":\"x\"}").getStatus())
                .isEqualTo(403);
        assertThat(reject(shipment.getId(), testData.userWithRoles(RoleEnum.WAREHOUSE), "{\"reason\":\"x\"}").getStatus())
                .isEqualTo(403);
        assertThat(reload(shipment).getStatus()).isEqualTo(ShipmentStatus.DISPATCH_REQUESTED);

        assertThat(reject(shipment.getId(), testData.userWithRoles(RoleEnum.ADMIN), "{\"reason\":\"carrier unreachable\"}").getStatus())
                .isEqualTo(200);
        assertThat(reload(shipment).getStatus()).isEqualTo(ShipmentStatus.REJECTED);
    }

    private MockHttpServletResponse assign(Long shipmentId, Long distributorId, User as) throws Exception {
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(as.getUsername(), null, List.of()));
        return mockMvc.perform(MockMvcRequestBuilders.put("/api/1.0/shipments/" + shipmentId + "/distributor/" + distributorId)
                        .header("X-API-KEY", "reassign-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    /** The way out: the planner hands a rejected shipment to another carrier, who can then take it on. */
    @Test
    void aRejectedShipmentCanBeHandedToAnotherCarrier() throws Exception {
        Distributor first = carrier();
        Distributor second = carrier();
        Shipment shipment = shipmentOf(first, ShipmentStatus.DISPATCH_REQUESTED);
        assertThat(reject(shipment.getId(), first, "{\"reason\":\"no truck free\"}").getStatus()).isEqualTo(200);

        MockHttpServletResponse response = assign(shipment.getId(), second.getId(), testData.userWithRoles(RoleEnum.LOGISTICS));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        Shipment stored = reload(shipment);
        assertThat(stored.getStatus()).isEqualTo(ShipmentStatus.DISPATCH_REQUESTED);
        assertThat(stored.getDistributor().getId()).isEqualTo(second.getId());
        assertThat(stored.getDistributorComment()).isNull();
        assertThat(stored.getComment()).isEqualTo("call first");

        // The new carrier answers for it; the one who turned it down no longer does.
        assertThat(reject(shipment.getId(), first, "{\"reason\":\"still no\"}").getStatus()).isEqualTo(403);
    }
}
