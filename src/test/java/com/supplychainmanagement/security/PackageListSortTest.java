package com.supplychainmanagement.security;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.PackageItem;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.entity.users.Customer;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
import com.supplychainmanagement.model.enums.ShipmentPackageType;
import com.supplychainmanagement.repository.PackageItemRepository;
import com.supplychainmanagement.repository.ShipmentPackageRepository;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The package item list ({@code GET /packages}) sorted by what its table shows, against the real filter
 * chain and the real rows - above all by the number of the order an item belongs to, which is what the page
 * groups its rows by.
 * <p>
 * The part worth a context: {@code shipmentPackage.id} is a path through an optional association, and the
 * list holds loose items with no package at all. As an inner join, every one of them would vanish from the
 * list the moment somebody clicks that column - no error, a shorter table.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class PackageListSortTest {

    private static final AtomicLong CALLS = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private PackageItemRepository packageItemRepository;
    @Autowired
    private ShipmentPackageRepository shipmentPackageRepository;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode get(String pathAndQuery) throws Exception {
        var user = testData.userWithRoles(RoleEnum.WAREHOUSE);
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(user.getUsername(), null, List.of()));
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.get("/api/1.0" + pathAndQuery)
                        .header("X-API-KEY", "pkg-sort-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return objectMapper.readTree(response.getContentAsString()).get("content");
    }

    private PackageItem itemOf(Order order, int quantity) {
        OrderItem line = testData.orderItem(order, 10, FulfillmentStatus.PACKING);
        return testData.packedQuantity(line, quantity);
    }

    /** The ids of this test's items, in the order the answer lists them. */
    private List<Long> idsOf(JsonNode content, List<PackageItem> mine) {
        List<Long> wanted = mine.stream().map(PackageItem::getId).toList();
        List<Long> ids = new ArrayList<>();
        content.forEach(row -> {
            if (wanted.contains(row.get("id").asLong())) {
                ids.add(row.get("id").asLong());
            }
        });
        return ids;
    }

    @Test
    void sortsByTheNumberOfTheOrder() throws Exception {
        Customer customer = testData.customer();
        Order first = testData.order(customer);
        Order second = testData.order(customer);
        Order third = testData.order(customer);
        // Created against the order of the numbers, so neither the id nor the insert order is the sort.
        PackageItem ofThird = itemOf(third, 1);
        PackageItem ofFirst = itemOf(first, 2);
        PackageItem ofSecond = itemOf(second, 3);
        List<PackageItem> mine = List.of(ofThird, ofFirst, ofSecond);

        List<Long> ascending = idsOf(get("/packages?size=500&sort=orderItem.order.orderNo&order=ASC"), mine);
        List<Long> descending = idsOf(get("/packages?size=500&sort=orderItem.order.orderNo&order=DESC"), mine);

        assertThat(ascending).containsExactly(ofFirst.getId(), ofSecond.getId(), ofThird.getId());
        assertThat(descending).containsExactly(ofThird.getId(), ofSecond.getId(), ofFirst.getId());
    }

    /** The check this class exists for: a loose item is sorted by the package column, not dropped. */
    @Test
    void sortingByThePackageKeepsTheLooseItems() throws Exception {
        Order order = testData.order(testData.customer());
        PackageItem loose = itemOf(order, 1);
        PackageItem packed = itemOf(order, 2);
        ShipmentPackage shipmentPackage = new ShipmentPackage();
        shipmentPackage.setPackageNumber("PKG-SORT-" + CALLS.incrementAndGet());
        shipmentPackage.setShipmentPackageStatus(ShipmentPackageStatus.OPEN);
        shipmentPackage.setShipmentPackageType(ShipmentPackageType.CARTON);
        shipmentPackage = shipmentPackageRepository.saveAndFlush(shipmentPackage);
        packed.setShipmentPackage(shipmentPackage);
        packageItemRepository.saveAndFlush(packed);
        List<PackageItem> mine = List.of(loose, packed);

        List<Long> ascending = idsOf(get("/packages?size=500&sort=shipmentPackage.id&order=ASC"), mine);
        List<Long> descending = idsOf(get("/packages?size=500&sort=shipmentPackage.id&order=DESC"), mine);

        assertThat(ascending).containsExactlyInAnyOrder(loose.getId(), packed.getId());
        assertThat(descending).containsExactlyInAnyOrder(loose.getId(), packed.getId());
    }

    /** The loose list takes the same keys. */
    @Test
    void theLooseListSortsByTheNumberOfTheOrderToo() throws Exception {
        Customer customer = testData.customer();
        Order first = testData.order(customer);
        Order second = testData.order(customer);
        PackageItem ofSecond = itemOf(second, 1);
        PackageItem ofFirst = itemOf(first, 1);

        List<Long> ids = idsOf(get("/lonely-packages?size=500&sort=orderItem.order.orderNo&order=ASC"),
                List.of(ofSecond, ofFirst));

        assertThat(ids).containsExactly(ofFirst.getId(), ofSecond.getId());
    }

    /** Items of one order share the sort value; the id has to keep the pages apart. */
    @Test
    void pagesDoNotRepeatOrSkipAnItemWhenTheOrderIsTheSame() throws Exception {
        Order order = testData.order(testData.customer());
        List<PackageItem> mine = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            mine.add(itemOf(order, i + 1));
        }

        List<Long> seen = new ArrayList<>();
        for (int page = 0; page < 400; page++) {
            JsonNode rows = get("/packages?size=2&page=" + page + "&sort=orderItem.order.orderNo&order=ASC");
            if (rows.isEmpty()) {
                break;
            }
            seen.addAll(idsOf(rows, mine));
        }

        assertThat(seen).containsExactlyInAnyOrderElementsOf(mine.stream().map(PackageItem::getId).toList());
    }

    /**
     * Without a sort the list is by when an item was made, then by its order number: the items are made in the
     * order [third, first, second], and the list keeps that - it is not the order of the numbers.
     */
    @Test
    void theDefaultListIsByCreationBeforeTheNumberOfTheOrder() throws Exception {
        Customer customer = testData.customer();
        Order first = testData.order(customer);
        Order second = testData.order(customer);
        Order third = testData.order(customer);
        PackageItem ofThird = itemOf(third, 1);
        PackageItem ofFirst = itemOf(first, 2);
        PackageItem ofSecond = itemOf(second, 3);
        List<PackageItem> mine = List.of(ofThird, ofFirst, ofSecond);

        List<Long> ascending = idsOf(get("/packages?size=500"), mine);
        List<Long> descending = idsOf(get("/packages?size=500&order=DESC"), mine);

        assertThat(ascending).containsExactly(ofThird.getId(), ofFirst.getId(), ofSecond.getId());
        assertThat(descending).containsExactly(ofSecond.getId(), ofFirst.getId(), ofThird.getId());
    }

    @Test
    void theLooseListHasTheSameDefault() throws Exception {
        Customer customer = testData.customer();
        Order first = testData.order(customer);
        Order second = testData.order(customer);
        PackageItem ofSecond = itemOf(second, 1);
        PackageItem ofFirst = itemOf(first, 1);

        assertThat(idsOf(get("/lonely-packages?size=500"), List.of(ofSecond, ofFirst)))
                .containsExactly(ofSecond.getId(), ofFirst.getId());
    }
}
