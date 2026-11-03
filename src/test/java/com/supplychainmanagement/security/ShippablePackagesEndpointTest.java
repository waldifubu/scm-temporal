package com.supplychainmanagement.security;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.PackageItem;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.entity.users.Customer;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
import com.supplychainmanagement.model.enums.ShipmentPackageType;
import com.supplychainmanagement.repository.PackageItemRepository;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /shipments/packages} - the containers logistics may still ship - against the real filter chain and
 * real rows: each row names the customer its order belongs to, so the planner can see which containers go together.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class ShippablePackagesEndpointTest {

    private static final AtomicLong CALLS = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private ShipmentPackageRepository shipmentPackageRepository;
    @Autowired
    private PackageItemRepository packageItemRepository;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private EntityManager entityManager;

    private ShipmentPackage packedFor(Customer customer) {
        Order order = testData.order(customer);
        PackageItem item = testData.packedQuantity(testData.orderItem(order, 4, FulfillmentStatus.PACKED), 4);
        ShipmentPackage shipmentPackage = new ShipmentPackage();
        shipmentPackage.setPackageNumber("PKG-CUST-" + CALLS.incrementAndGet());
        shipmentPackage.setShipmentPackageStatus(ShipmentPackageStatus.PACKED);
        shipmentPackage.setShipmentPackageType(ShipmentPackageType.CARTON);
        shipmentPackage = shipmentPackageRepository.saveAndFlush(shipmentPackage);
        item.setShipmentPackage(shipmentPackage);
        packageItemRepository.saveAndFlush(item);
        return shipmentPackage;
    }

    private JsonNode rowOf(ShipmentPackage shipmentPackage) throws Exception {
        entityManager.flush();
        entityManager.clear();
        User logistics = testData.userWithRoles(RoleEnum.LOGISTICS);
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(logistics.getUsername(), null, List.of()));
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.get("/api/1.0/shipments/packages?size=500&sort=id&order=DESC")
                        .header("X-API-KEY", "shippable-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        for (JsonNode row : objectMapper.readTree(response.getContentAsString()).get("content")) {
            if (row.get("id").asLong() == shipmentPackage.getId()) {
                return row;
            }
        }
        return null;
    }

    @Test
    void eachRowNamesTheCustomerOfItsOrder() throws Exception {
        Customer first = testData.customer();
        Customer second = testData.customer();
        ShipmentPackage ofFirst = packedFor(first);
        ShipmentPackage ofSecond = packedFor(second);

        JsonNode rowOfFirst = rowOf(ofFirst);
        JsonNode rowOfSecond = rowOf(ofSecond);

        assertThat(rowOfFirst).isNotNull();
        assertThat(rowOfFirst.get("customerId").asLong()).isEqualTo(first.getId());
        assertThat(rowOfSecond.get("customerId").asLong()).isEqualTo(second.getId());
    }
}
