package com.supplychainmanagement.security;

import com.supplychainmanagement.entity.Shipment;
import com.supplychainmanagement.entity.users.Customer;
import com.supplychainmanagement.entity.users.Distributor;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.model.enums.ShipmentStatus;
import com.supplychainmanagement.repository.ShipmentRepository;
import com.supplychainmanagement.repository.UserRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shipment lists against the real filter chain and the real rows: the distributor is named in a
 * row, and a page can be sorted by the names of the customer and the distributor.
 * <p>
 * The part worth a context: {@code distributor.lastName} is a path through an optional association. If
 * it became an inner join, every shipment nobody has taken on yet would silently vanish from the list
 * the moment somebody clicks that column - no error, a shorter table. A unit test cannot see that.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class ShipmentListSortTest {

    private static final AtomicLong CALLS = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ShipmentRepository shipmentRepository;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private ObjectMapper objectMapper;

    private MockHttpServletResponse get(String pathAndQuery, User as) throws Exception {
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(as.getUsername(), null, List.of()));
        return mockMvc.perform(MockMvcRequestBuilders.get("/api/1.0" + pathAndQuery)
                        .header("X-API-KEY", "ship-sort-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    private Customer customerNamed(String lastName) {
        Customer customer = testData.customer();
        customer.setLastName(lastName);
        return userRepository.saveAndFlush(customer);
    }

    private Distributor distributorNamed(String lastName) {
        Distributor distributor = (Distributor) testData.userWithRoles(RoleEnum.DISTRIBUTOR);
        distributor.setLastName(lastName);
        return userRepository.saveAndFlush(distributor);
    }

    private Shipment shipment(Customer customer, Distributor distributor) {
        Shipment shipment = new Shipment();
        shipment.setCustomer(customer);
        shipment.setDistributor(distributor);
        shipment.setStatus(distributor == null ? ShipmentStatus.READY : ShipmentStatus.DISPATCH_REQUESTED);
        shipment.setCreatedAt(LocalDateTime.now());
        return shipmentRepository.saveAndFlush(shipment);
    }

    /** The ids of this test's shipments, in the order the answer lists them. */
    private List<Long> idsOf(JsonNode content, List<Shipment> mine) {
        List<Long> wanted = mine.stream().map(Shipment::getId).toList();
        List<Long> ids = new ArrayList<>();
        for (JsonNode row : content) {
            if (wanted.contains(row.get("id").asLong())) {
                ids.add(row.get("id").asLong());
            }
        }
        return ids;
    }

    private JsonNode content(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return objectMapper.readTree(response.getContentAsString()).get("content");
    }

    @Test
    void namesTheDistributorInARowAndLeavesItOutForAShipmentWithout() throws Exception {
        Distributor carrier = distributorNamed("Carrier");
        Shipment taken = shipment(customerNamed("Hopper"), carrier);
        Shipment open = shipment(customerNamed("Hopper"), null);
        User admin = testData.userWithRoles(RoleEnum.ADMIN);

        JsonNode rows = content(get("/shipments?size=500", admin));

        JsonNode takenRow = null;
        JsonNode openRow = null;
        for (JsonNode row : rows) {
            if (row.get("id").asLong() == taken.getId()) takenRow = row;
            if (row.get("id").asLong() == open.getId()) openRow = row;
        }
        assertThat(takenRow).isNotNull();
        assertThat(takenRow.get("distributorId").asLong()).isEqualTo(carrier.getId());
        assertThat(takenRow.get("distributorName").asString()).isEqualTo("Role Carrier");
        assertThat(openRow).isNotNull();
        assertThat(openRow.get("distributorId").isNull()).isTrue();
        assertThat(openRow.get("distributorName").isNull()).isTrue();
    }

    /** The check this class exists for: the shipment without a distributor is sorted, not dropped. */
    @Test
    void sortingByDistributorKeepsTheShipmentsNobodyHasTakenOn() throws Exception {
        Shipment zulu = shipment(customerNamed("Hopper"), distributorNamed("Zulu"));
        Shipment alpha = shipment(customerNamed("Hopper"), distributorNamed("Alpha"));
        Shipment none = shipment(customerNamed("Hopper"), null);
        List<Shipment> mine = List.of(zulu, alpha, none);
        User admin = testData.userWithRoles(RoleEnum.ADMIN);

        List<Long> ascending = idsOf(content(get("/shipments?size=500&sort=distributor.lastName&order=ASC", admin)), mine);
        List<Long> descending = idsOf(content(get("/shipments?size=500&sort=distributor.lastName&order=DESC", admin)), mine);

        assertThat(ascending).containsExactlyInAnyOrder(zulu.getId(), alpha.getId(), none.getId());
        assertThat(descending).containsExactlyInAnyOrder(zulu.getId(), alpha.getId(), none.getId());
        // Between the two named carriers the order is by name, whichever way it runs.
        assertThat(ascending.indexOf(alpha.getId())).isLessThan(ascending.indexOf(zulu.getId()));
        assertThat(descending.indexOf(zulu.getId())).isLessThan(descending.indexOf(alpha.getId()));
    }

    @Test
    void sortsByTheCustomersLastName() throws Exception {
        Shipment zulu = shipment(customerNamed("Zulu"), null);
        Shipment alpha = shipment(customerNamed("Alpha"), null);
        Shipment mike = shipment(customerNamed("Mike"), null);
        User admin = testData.userWithRoles(RoleEnum.ADMIN);

        List<Long> ascending = idsOf(content(get("/shipments?size=500&sort=customer.lastName&order=ASC", admin)),
                List.of(zulu, alpha, mike));

        assertThat(ascending).containsExactly(alpha.getId(), mike.getId(), zulu.getId());
    }

    /** The same keys work on the carrier's own list, which shows only what was handed to them. */
    @Test
    void theCarriersOwnListSortsByTheCustomersNameAndShowsOnlyTheirShipments() throws Exception {
        Distributor carrier = distributorNamed("Carrier");
        Distributor other = distributorNamed("Other");
        Shipment zulu = shipment(customerNamed("Zulu"), carrier);
        Shipment alpha = shipment(customerNamed("Alpha"), carrier);
        Shipment foreign = shipment(customerNamed("Mike"), other);

        JsonNode rows = content(get("/shipments/distributor?size=500&sort=customer.lastName&order=ASC", carrier));

        List<Long> ids = new ArrayList<>();
        rows.forEach(row -> ids.add(row.get("shipmentId").asLong()));
        assertThat(ids).containsExactly(alpha.getId(), zulu.getId());
        assertThat(ids).doesNotContain(foreign.getId());
    }

    /** Shipments of one second share everything a column can sort by; the id has to keep the pages apart. */
    @Test
    void pagesDoNotRepeatOrSkipAShipmentWhenTheSortValuesTie() throws Exception {
        Customer customer = customerNamed("Same");
        List<Shipment> mine = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            mine.add(shipment(customer, null));
        }
        User admin = testData.userWithRoles(RoleEnum.ADMIN);

        List<Long> seen = new ArrayList<>();
        for (int page = 0; page < 400; page++) {
            JsonNode rows = content(get("/shipments?size=2&page=" + page + "&sort=status&order=ASC", admin));
            if (rows.isEmpty()) {
                break;
            }
            seen.addAll(idsOf(rows, mine));
        }

        assertThat(seen).containsExactlyInAnyOrderElementsOf(mine.stream().map(Shipment::getId).toList());
    }
}
