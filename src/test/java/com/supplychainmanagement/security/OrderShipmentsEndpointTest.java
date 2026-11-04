package com.supplychainmanagement.security;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.PackageItem;
import com.supplychainmanagement.entity.Shipment;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.entity.users.Customer;
import com.supplychainmanagement.entity.users.Distributor;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
import com.supplychainmanagement.model.enums.ShipmentStatus;
import com.supplychainmanagement.repository.PackageItemRepository;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static com.jayway.jsonpath.JsonPath.read;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /orders/{orderNo}/shipments} against the real filter chain and real rows: the shipments an order's
 * packages travel in, only that order's, the carrier's name for staff and not for the customer, and the access
 * rule of the order itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class OrderShipmentsEndpointTest {

    private static final AtomicLong CALLS = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private ShipmentRepository shipmentRepository;
    @Autowired
    private ShipmentPackageRepository shipmentPackageRepository;
    @Autowired
    private PackageItemRepository packageItemRepository;

    private MockHttpServletResponse shipmentsOf(Long orderNo, User as) throws Exception {
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(as.getUsername(), null, List.of()));
        return mockMvc.perform(MockMvcRequestBuilders.get("/api/1.0/orders/" + orderNo + "/shipments")
                        .header("X-API-KEY", "order-shipments-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    private Shipment shipment(Customer customer, Distributor distributor, ShipmentStatus status) {
        Shipment shipment = new Shipment();
        shipment.setCustomer(customer);
        shipment.setDistributor(distributor);
        shipment.setStatus(status);
        shipment.setTrackingNumber("TRACK-" + CALLS.incrementAndGet());
        shipment.setShippingAddress("1 Harbour Road");
        shipment.setCreatedAt(LocalDateTime.now());
        return shipmentRepository.saveAndFlush(shipment);
    }

    /** A package with the given number in the shipment, holding some of the line. */
    private void packInto(Shipment shipment, OrderItem line, String packageNumber) {
        PackageItem item = testData.packedQuantity(line, 1);
        ShipmentPackage shipmentPackage = new ShipmentPackage();
        shipmentPackage.setShipmentPackageStatus(ShipmentPackageStatus.PACKED);
        shipmentPackage.setPackageNumber(packageNumber);
        shipmentPackage = shipmentPackageRepository.saveAndFlush(shipmentPackage);
        item.setShipmentPackage(shipmentPackage);
        packageItemRepository.saveAndFlush(item);
        shipment.addPackage(shipmentPackage);
        shipmentPackageRepository.saveAndFlush(shipmentPackage);
    }

    private Distributor carrier() {
        Distributor distributor = (Distributor) testData.userWithRoles(RoleEnum.DISTRIBUTOR);
        distributor.setFirstName("Dora");
        distributor.setLastName("Dispatch");
        return distributor;
    }

    @Test
    void listsTheShipmentsOfTheOrderWithTheirPackagesAndOnlyThose() throws Exception {
        Customer customer = testData.customer();
        Order order = testData.order(customer);
        OrderItem line = testData.orderItem(order, 3, FulfillmentStatus.PACKED);
        Shipment first = shipment(customer, carrier(), ShipmentStatus.DELIVERED);
        Shipment second = shipment(customer, null, ShipmentStatus.READY);
        packInto(first, line, "PKG-A");
        packInto(second, line, "PKG-B");
        // Another order of the same customer, in a third shipment: must not show up.
        Order other = testData.order(customer);
        packInto(shipment(customer, null, ShipmentStatus.READY), testData.orderItem(other, 1, FulfillmentStatus.PACKED), "PKG-X");
        User manager = testData.userWithRoles(RoleEnum.MANAGER);

        MockHttpServletResponse response = shipmentsOf(order.getOrderNo(), manager);

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        String body = response.getContentAsString();
        List<Integer> ids = read(body, "$[*].id");
        assertThat(ids).containsExactly(first.getId().intValue(), second.getId().intValue());
        List<String> statuses = read(body, "$[*].status");
        assertThat(statuses).containsExactly("DELIVERED", "READY");
        List<String> firstPackages = read(body, "$[0].packageNumbers");
        assertThat(firstPackages).containsExactly("PKG-A");
        List<String> secondPackages = read(body, "$[1].packageNumbers");
        assertThat(secondPackages).containsExactly("PKG-B");
        String address = read(body, "$[0].shippingAddress");
        assertThat(address).isEqualTo("1 Harbour Road");
    }

    @Test
    void staffSeeTheCarrierAndACustomerDoesNot() throws Exception {
        Customer customer = (Customer) testData.userWithRoles(RoleEnum.CUSTOMER);
        Order order = testData.order(customer);
        OrderItem line = testData.orderItem(order, 3, FulfillmentStatus.PACKED);
        packInto(shipment(customer, carrier(), ShipmentStatus.IN_TRANSIT), line, "PKG-A");

        Map<String, Object> asStaff = read(shipmentsOf(order.getOrderNo(), testData.userWithRoles(RoleEnum.LOGISTICS))
                .getContentAsString(), "$[0]");
        Map<String, Object> asCustomer = read(shipmentsOf(order.getOrderNo(), customer).getContentAsString(), "$[0]");

        assertThat(asStaff.get("distributorName")).isEqualTo("Dora Dispatch");
        assertThat(asCustomer).doesNotContainKey("distributorName");
        assertThat(asCustomer.get("status")).isEqualTo("IN_TRANSIT");
    }

    @Test
    void anOrderInNoShipmentAnswersAnEmptyList() throws Exception {
        Order order = testData.order(testData.customer());
        testData.orderItem(order, 3, FulfillmentStatus.WAITING);

        MockHttpServletResponse response = shipmentsOf(order.getOrderNo(), testData.userWithRoles(RoleEnum.WAREHOUSE));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("[]");
    }

    @Test
    void aCustomerMayNotReadAnotherCustomersShipmentsAndAnUnknownOrderIsNotFound() throws Exception {
        Order order = testData.order(testData.customer());

        assertThat(shipmentsOf(order.getOrderNo(), testData.userWithRoles(RoleEnum.CUSTOMER)).getStatus()).isEqualTo(403);
        assertThat(shipmentsOf(order.getOrderNo() + 987654, testData.userWithRoles(RoleEnum.MANAGER)).getStatus()).isEqualTo(404);
    }
}
