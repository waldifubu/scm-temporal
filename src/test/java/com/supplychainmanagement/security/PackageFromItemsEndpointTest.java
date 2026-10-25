package com.supplychainmanagement.security;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.PackageItem;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
import com.supplychainmanagement.model.enums.ShipmentPackageType;
import com.supplychainmanagement.repository.PackageItemRepository;
import com.supplychainmanagement.repository.ShipmentPackageRepository;
import com.supplychainmanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
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

import static com.jayway.jsonpath.JsonPath.read;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /packing/shipment/from-items} against the real filter chain and real rows: a package
 * made of package items that exist already, in one call.
 * <p>
 * The first tests send the caller's own JSON, key for key - type, ids, a null number, a null
 * <em>weight</em> and null dimensions - because the point of the endpoint is that this body works. The
 * rest is what has to refuse, and that a refusal leaves no package behind.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class PackageFromItemsEndpointTest {

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
    private PackageItemRepository packageItemRepository;
    @Autowired
    private EntityManager entityManager;

    private User customer;
    private Order order;
    private Order otherOrder;
    /** Loose, of {@code order}: 3 and 4 pieces of a product weighing 2 each. */
    private PackageItem first;
    private PackageItem second;
    /** Loose, of the other order. */
    private PackageItem foreign;
    /** Already in a package. */
    private PackageItem taken;
    private ShipmentPackage otherPackage;

    @BeforeEach
    void scenario() {
        customer = testData.customer();
        order = testData.order(customer);
        otherOrder = testData.order(customer);

        first = testData.packedQuantity(testData.orderItem(order, 10, FulfillmentStatus.PACKING), 3);
        second = testData.packedQuantity(testData.orderItem(order, 10, FulfillmentStatus.PACKING), 4);
        foreign = testData.packedQuantity(testData.orderItem(otherOrder, 10, FulfillmentStatus.PACKING), 2);
        // A product's weight is worked out from its components when it is inserted - none here, so 0 -
        // and set by hand afterwards to have something to add up.
        first.getOrderItem().getProduct().setWeight(new java.math.BigDecimal("2"));
        second.getOrderItem().getProduct().setWeight(new java.math.BigDecimal("2"));
        entityManager.flush();

        taken = testData.packedQuantity(testData.orderItem(order, 10, FulfillmentStatus.PACKING), 1);
        otherPackage = new ShipmentPackage();
        otherPackage.setPackageNumber("PKG-TAKEN-" + CALLS.incrementAndGet());
        otherPackage.setShipmentPackageStatus(ShipmentPackageStatus.OPEN);
        otherPackage.setShipmentPackageType(ShipmentPackageType.OTHER);
        otherPackage = shipmentPackageRepository.saveAndFlush(otherPackage);
        otherPackage.addItem(taken);
        shipmentPackageRepository.saveAndFlush(otherPackage);
    }

    private MockHttpServletResponse post(String body, User as) throws Exception {
        String token = jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(as.getUsername(), null, List.of()));
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/1.0/packing/shipment/from-items")
                        .header("X-API-KEY", "from-items-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(body))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse asWarehouse(String body) throws Exception {
        return post(body, testData.userWithRoles(RoleEnum.WAREHOUSE));
    }

    /** The caller's JSON with the given ids. */
    private static String callersJson(Long... ids) {
        return """
                {
                  "shipmentPackageType": "OTHER",
                  "packageItems": %s,
                  "packageNumber": null,
                  "weight": null,
                  "length": null,
                  "width": null,
                  "height": null
                }""".formatted(List.of(ids));
    }

    private long packages() {
        return shipmentPackageRepository.count();
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    void createsAnOpenPackageHoldingTheItems() throws Exception {
        MockHttpServletResponse response = asWarehouse(callersJson(first.getId(), second.getId()));

        assertThat(response.getStatus()).as("answered: %.300s", response.getContentAsString()).isEqualTo(200);
        String body = response.getContentAsString();
        assertThat((String) read(body, "$.status")).isEqualTo("OPEN");
        assertThat((String) read(body, "$.type")).isEqualTo("OTHER");
        assertThat(((Number) read(body, "$.id")).longValue()).isPositive();
        List<Number> itemIds = read(body, "$.items[*].id");
        assertThat(itemIds).extracting(Number::longValue).containsExactlyInAnyOrder(first.getId(), second.getId());
    }

    /** What makes it worth a call: the items belong to the package afterwards, in the database. */
    @Test
    void movesTheItemsIntoThePackage() throws Exception {
        String body = asWarehouse(callersJson(first.getId(), second.getId())).getContentAsString();
        long packageId = ((Number) read(body, "$.id")).longValue();

        entityManager.flush();
        entityManager.clear();

        assertThat(packageItemRepository.findById(first.getId()).orElseThrow().getShipmentPackage().getId()).isEqualTo(packageId);
        assertThat(packageItemRepository.findById(second.getId()).orElseThrow().getShipmentPackage().getId()).isEqualTo(packageId);
    }

    /** No number sent: one is generated. */
    @Test
    void generatesAPackageNumberWhenNoneIsSent() throws Exception {
        String body = asWarehouse(callersJson(first.getId())).getContentAsString();

        assertThat((String) read(body, "$.packageNumber")).startsWith("PKG-");
    }

    /** The weight is the package's own business: 2 x 3 + 2 x 4, whatever the body says. */
    @Test
    void computesTheWeightAndIgnoresOneInTheBody() throws Exception {
        String body = asWarehouse("""
                { "packageItems": [%d, %d], "weight": 999 }""".formatted(first.getId(), second.getId()))
                .getContentAsString();

        assertThat(((Number) read(body, "$.weight")).doubleValue()).isEqualTo(14.0);
        assertThat(((Number) read(body, "$.packageWeight")).doubleValue()).isEqualTo(14.0);
    }

    @Test
    void takesTypeNumberAndDimensionsAsSent() throws Exception {
        String body = asWarehouse("""
                { "shipmentPackageType": "euro_pallet", "packageItems": [%d], "packageNumber": "MY-PKG-%d",
                  "length": 120.5, "width": 80, "height": 15 }""".formatted(first.getId(), CALLS.get())).getContentAsString();

        assertThat((String) read(body, "$.type")).isEqualTo("EURO_PALLET");
        assertThat((String) read(body, "$.packageNumber")).startsWith("MY-PKG-");
        assertThat(((Number) read(body, "$.length")).doubleValue()).isEqualTo(120.5);
        assertThat(((Number) read(body, "$.width")).doubleValue()).isEqualTo(80.0);
        assertThat(((Number) read(body, "$.height")).doubleValue()).isEqualTo(15.0);
    }

    /** The dimensions sent are the ones the package lists answer, each under its own name, in the list and on its own. */
    @Test
    void showsTheDimensionsSentInThePackageLists() throws Exception {
        String number = "DIMS-" + CALLS.incrementAndGet();
        String created = asWarehouse("""
                { "packageItems": [%d], "packageNumber": "%s", "length": 120.5, "width": 80, "height": 15 }"""
                .formatted(first.getId(), number)).getContentAsString();
        long packageId = ((Number) read(created, "$.id")).longValue();
        String token = jwtTokenProvider.generateToken(new UsernamePasswordAuthenticationToken(
                testData.userWithRoles(RoleEnum.WAREHOUSE).getUsername(), null, List.of()));

        String single = mockMvc.perform(MockMvcRequestBuilders.get("/api/1.0/shipment-packages/" + packageId)
                        .header("X-API-KEY", "from-items-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        String list = mockMvc.perform(MockMvcRequestBuilders.get("/api/1.0/shipment-packages")
                        .param("status", "OPEN").param("packageNumber", number)
                        .header("X-API-KEY", "from-items-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();

        for (String body : List.of(single, list)) {
            String at = body == single ? "$" : "$.content[0]";
            assertThat(((Number) read(body, at + ".length")).doubleValue()).isEqualTo(120.5);
            assertThat(((Number) read(body, at + ".width")).doubleValue()).isEqualTo(80.0);
            assertThat(((Number) read(body, at + ".height")).doubleValue()).isEqualTo(15.0);
            assertThat(((Number) read(body, at + ".volume")).doubleValue()).isEqualTo(120.5 * 80 * 15);
        }
    }

    /** Made, then packed: createdAt from the first call on, packedAt only once the package is completed. */
    @Test
    void showsWhenThePackageWasMadeAndWhenItWasPacked() throws Exception {
        PackageItem a = testData.packedQuantity(testData.orderItem(order, 5, FulfillmentStatus.PACKED), 5);
        String created = asWarehouse(callersJson(a.getId())).getContentAsString();
        long packageId = ((Number) read(created, "$.id")).longValue();
        String token = jwtTokenProvider.generateToken(new UsernamePasswordAuthenticationToken(
                testData.userWithRoles(RoleEnum.WAREHOUSE).getUsername(), null, List.of()));

        String before = mockMvc.perform(MockMvcRequestBuilders.get("/api/1.0/shipment-packages/" + packageId)
                        .header("X-API-KEY", "from-items-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(MockMvcRequestBuilders.put("/api/1.0/packing/shipment/" + packageId + "/complete")
                .header("X-API-KEY", "from-items-" + CALLS.incrementAndGet())
                .header("Authorization", "Bearer " + token)).andReturn();
        String after = mockMvc.perform(MockMvcRequestBuilders.get("/api/1.0/shipment-packages/" + packageId)
                        .header("X-API-KEY", "from-items-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();

        assertThat((String) read(before, "$.status")).isEqualTo("OPEN");
        assertThat((String) read(before, "$.createdAt")).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
        assertThat((Object) read(before, "$.packedAt")).as("not packed yet").isNull();

        assertThat((String) read(after, "$.status")).isEqualTo("PACKED");
        assertThat((String) read(after, "$.packedAt")).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
        assertThat((String) read(after, "$.createdAt")).isEqualTo((String) read(before, "$.createdAt"));
        // The fixed-width pattern sorts like time: made no later than packed.
        assertThat((String) read(after, "$.packedAt")).isGreaterThanOrEqualTo((String) read(after, "$.createdAt"));
    }

    /** A repeated id is the same item once. */
    @Test
    void takesARepeatedIdOnce() throws Exception {
        String body = asWarehouse(callersJson(first.getId(), first.getId())).getContentAsString();

        List<Object> items = read(body, "$.items");
        assertThat(items).hasSize(1);
    }

    /** The items are in a package now: asking again is the "already in a package" refusal, not a second package. */
    @Test
    void doesNotMakeASecondPackageOutOfTheSameItems() throws Exception {
        assertThat(asWarehouse(callersJson(first.getId())).getStatus()).isEqualTo(200);
        long before = packages();

        MockHttpServletResponse again = asWarehouse(callersJson(first.getId()));

        assertThat(again.getStatus()).isEqualTo(409);
        assertThat(packages()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ refusals leave nothing behind

    @Test
    void refusesAnUnknownIdWith404AndMakesNoPackage() throws Exception {
        long before = packages();

        MockHttpServletResponse response = asWarehouse(callersJson(first.getId(), 987_654_321L));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat((String) read(response.getContentAsString(), "$.message")).contains("987654321");
        assertThat(packages()).isEqualTo(before);
        assertThat(first.getShipmentPackage()).as("the known item stays loose").isNull();
    }

    @Test
    void refusesAnItemAlreadyInAnotherPackageWith409() throws Exception {
        long before = packages();

        MockHttpServletResponse response = asWarehouse(callersJson(first.getId(), taken.getId()));

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat((String) read(response.getContentAsString(), "$.message"))
                .contains("PackageItem " + taken.getId()).contains("ShipmentPackage " + otherPackage.getId());
        assertThat(packages()).isEqualTo(before);
        assertThat(taken.getShipmentPackage().getId()).as("not taken out of its package").isEqualTo(otherPackage.getId());
        assertThat(first.getShipmentPackage()).isNull();
    }

    /** A package goes to one customer's order. */
    @Test
    void refusesItemsOfTwoOrdersWith400() throws Exception {
        long before = packages();

        MockHttpServletResponse response = asWarehouse(callersJson(first.getId(), foreign.getId()));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat((String) read(response.getContentAsString(), "$.message")).contains("one order");
        assertThat(packages()).isEqualTo(before);
        assertThat(first.getShipmentPackage()).isNull();
        assertThat(foreign.getShipmentPackage()).isNull();
    }

    @Test
    void refusesABodyWithoutIdsWith400() throws Exception {
        long before = packages();

        assertThat(asWarehouse("{\"shipmentPackageType\": \"OTHER\"}").getStatus()).isEqualTo(400);
        assertThat(asWarehouse("{\"packageItems\": []}").getStatus()).isEqualTo(400);
        assertThat(asWarehouse("{\"packageItems\": [null]}").getStatus()).isEqualTo(400);
        assertThat(packages()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ who may write

    /** Packing is the warehouse's work, ADMIN may do it too. */
    @Test
    void letsWarehouseAndAdminCreateAPackage() throws Exception {
        for (RoleEnum role : List.of(RoleEnum.WAREHOUSE, RoleEnum.ADMIN)) {
            PackageItem own = testData.packedQuantity(testData.orderItem(order, 10, FulfillmentStatus.PACKING), 1);

            MockHttpServletResponse response = post(callersJson(own.getId()), testData.userWithRoles(role));

            assertThat(response.getStatus()).as("%s: %.200s", role, response.getContentAsString()).isEqualTo(200);
        }
    }

    /** The manager reads the picked stage; writing a package is not reading. */
    @Test
    void refusesEveryOtherRoleWith403AndMakesNoPackage() throws Exception {
        long before = packages();

        for (RoleEnum role : List.of(RoleEnum.MANAGER, RoleEnum.CUSTOMER, RoleEnum.LOGISTICS, RoleEnum.SUPPLIER, RoleEnum.DISTRIBUTOR)) {
            assertThat(post(callersJson(first.getId()), testData.userWithRoles(role)).getStatus())
                    .as(role.name()).isEqualTo(403);
        }
        assertThat(packages()).isEqualTo(before);
    }

    @Test
    void refusesARequestWithoutAToken() throws Exception {
        int status = mockMvc.perform(MockMvcRequestBuilders.post("/api/1.0/packing/shipment/from-items")
                        .header("X-API-KEY", "from-items-" + CALLS.incrementAndGet())
                        .contentType("application/json").content(callersJson(first.getId())))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(401);
    }

    /** The package it makes is the one the planning side's lists and the completing call know. */
    @Test
    void makesAPackageThatCanBeCompletedNext() throws Exception {
        // completePackage wants every line PACKED - packing has set them so by the time a package is closed.
        PackageItem a = testData.packedQuantity(testData.orderItem(order, 5, FulfillmentStatus.PACKED), 5);
        PackageItem b = testData.packedQuantity(testData.orderItem(order, 6, FulfillmentStatus.PACKED), 6);
        String body = asWarehouse(callersJson(a.getId(), b.getId())).getContentAsString();
        long packageId = ((Number) read(body, "$.id")).longValue();
        String token = jwtTokenProvider.generateToken(new UsernamePasswordAuthenticationToken(
                testData.userWithRoles(RoleEnum.WAREHOUSE).getUsername(), null, List.of()));

        MockHttpServletResponse completed = mockMvc.perform(MockMvcRequestBuilders
                        .put("/api/1.0/packing/shipment/" + packageId + "/complete")
                        .header("X-API-KEY", "from-items-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();

        assertThat(completed.getStatus()).as("answered: %.300s", completed.getContentAsString()).isEqualTo(200);
        assertThat((String) read(completed.getContentAsString(), "$.status")).isEqualTo("PACKED");
    }
}
