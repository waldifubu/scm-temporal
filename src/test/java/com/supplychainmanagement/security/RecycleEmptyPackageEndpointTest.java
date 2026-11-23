package com.supplychainmanagement.security;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.PackageItem;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
import com.supplychainmanagement.model.enums.ShipmentPackageType;
import com.supplychainmanagement.repository.ShipmentPackageRepository;
import com.supplychainmanagement.support.TestData;
import jakarta.persistence.EntityManager;
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

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code DELETE /packing/shipment/{id}} - recycling an empty container - against the real filter chain and real
 * rows: only warehouse and admin, only a package without items, and a refusal leaves the package where it was.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class RecycleEmptyPackageEndpointTest {

    private static final AtomicLong CALLS = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private ShipmentPackageRepository shipmentPackageRepository;
    @Autowired
    private EntityManager entityManager;

    private MockHttpServletResponse delete(User as, Long id) throws Exception {
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(as.getUsername(), null, List.of()));
        return mockMvc.perform(MockMvcRequestBuilders.delete("/api/1.0/packing/shipment/" + id)
                .header("X-API-KEY", "recycle-" + CALLS.incrementAndGet())
                .header("Authorization", "Bearer " + token)).andReturn().getResponse();
    }

    private ShipmentPackage openPackage() {
        ShipmentPackage pack = new ShipmentPackage();
        pack.setPackageNumber("PKG-RECYCLE-" + CALLS.incrementAndGet());
        pack.setShipmentPackageStatus(ShipmentPackageStatus.OPEN);
        pack.setShipmentPackageType(ShipmentPackageType.OTHER);
        return shipmentPackageRepository.saveAndFlush(pack);
    }

    @Test
    void recyclesAnEmptyContainer() throws Exception {
        ShipmentPackage pack = openPackage();

        assertThat(delete(testData.userWithRoles(RoleEnum.WAREHOUSE), pack.getId()).getStatus()).isEqualTo(204);

        entityManager.flush();
        entityManager.clear();
        assertThat(shipmentPackageRepository.findById(pack.getId())).isEmpty();
    }

    @Test
    void refusesAContainerThatStillHoldsItems() throws Exception {
        Order order = testData.order(testData.customer());
        PackageItem item = testData.packedQuantity(testData.orderItem(order, 10, FulfillmentStatus.PACKING), 3);
        ShipmentPackage pack = openPackage();
        pack.addItem(item);
        entityManager.flush();

        MockHttpServletResponse response = delete(testData.userWithRoles(RoleEnum.ADMIN), pack.getId());

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(409);
        entityManager.clear();
        assertThat(shipmentPackageRepository.findById(pack.getId())).isPresent();
    }

    @Test
    void answers404ForAnUnknownContainerAnd403ForOtherRoles() throws Exception {
        assertThat(delete(testData.userWithRoles(RoleEnum.ADMIN), -1L).getStatus()).isEqualTo(404);
        ShipmentPackage pack = openPackage();
        for (RoleEnum role : List.of(RoleEnum.LOGISTICS, RoleEnum.MANAGER, RoleEnum.CUSTOMER)) {
            assertThat(delete(testData.userWithRoles(role), pack.getId()).getStatus()).as(role.name()).isEqualTo(403);
        }
        assertThat(shipmentPackageRepository.findById(pack.getId())).isPresent();
    }
}
