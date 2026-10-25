package com.supplychainmanagement.controller;

import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.security.JwtTokenProvider;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every paged list, against the real filter chain and the real queries: a typo in {@code sort},
 * {@code page} or {@code size} is a <strong>400</strong>, never a 500, and every column a list says it
 * can be sorted by really can.
 * <p>
 * Twelve lists used to answer 500 for all three: nothing handles {@code PropertyReferenceException},
 * and {@code PageRequest.of} throws {@code IllegalArgumentException}. {@code PageRequests.of} checks them
 * first, against a list each endpoint names - and that list is the second thing worth holding. A
 * name on it that the query cannot resolve (a joined column on a projection, a field the entity does
 * not have) would pass every check at the controller and fail at the database, which is exactly how
 * these lists were wrong before, so each entry is run as a {@code sort} here, in both directions.
 * <p>
 * The tables are empty for all it matters: a sort that does not resolve fails when the query is
 * built, not when it finds a row.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class ListParametersTest {

    private static final AtomicLong CALLS = new AtomicLong();

    /** One paged list: where it is, and what it says it can be sorted by. */
    private record Listing(String path, Set<String> sortable) {
    }

    private static List<Listing> listings() {
        return List.of(
                new Listing("/orders", OrderController.ORDER_SORTABLE),
                new Listing("/orders/new", OrderController.ORDER_SORTABLE),
                new Listing("/order-items", PackingController.ORDER_ITEM_SORTABLE),
                new Listing("/order-items/packable", PackingController.ORDER_ITEM_SORTABLE),
                new Listing("/picking-orders", PickingController.PICKING_SORTABLE),
                new Listing("/shipments", ShipmentController.SHIPMENT_SORTABLE),
                new Listing("/shipments/distributor", ShipmentController.SHIPMENT_SORTABLE),
                new Listing("/shipments/packages", PackageController.SHIPMENT_PACKAGE_SORTABLE),
                new Listing("/users/distributors", UserController.USER_SORTABLE),
                new Listing("/users/suppliers", UserController.USER_SORTABLE),
                new Listing("/shipment-packages", PackageController.SHIPMENT_PACKAGE_SORTABLE),
                new Listing("/packages", PackageController.PACKAGE_ITEM_SORTABLE),
                new Listing("/lonely-packages", PackageController.PACKAGE_ITEM_SORTABLE),
                new Listing("/components/requests", ComponentController.REQUEST_SORTABLE),
                new Listing("/users", UserController.USER_SORTABLE),
                new Listing("/stock/storehouse/1", StockController.STOCK_SORTABLE));
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TestData testData;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private String token() {
        User admin = testData.userWithRoles(RoleEnum.ADMIN);
        return jwtTokenProvider.generateToken(
                new UsernamePasswordAuthenticationToken(admin.getUsername(), null, List.of()));
    }

    private MockHttpServletResponse get(String pathAndQuery, String token) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get("/api/1.0" + pathAndQuery)
                        .header("X-API-KEY", "list-params-" + CALLS.incrementAndGet())
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
    }

    /** Stock of a storehouse that has none is a 404 - the one answer besides 200 that is not a mistake. */
    private static boolean fine(Listing listing, int status) {
        return status == 200 || (listing.path().startsWith("/stock/") && status == 404);
    }

    // ------------------------------------------------------------------ a typo is a 400

    @Test
    void answersAnUnknownSortWith400AndNamesWhatIsAllowed() throws Exception {
        String token = token();
        List<String> wrong = new ArrayList<>();

        for (Listing listing : listings()) {
            MockHttpServletResponse response = get(listing.path() + "?sort=doesNotExist", token);
            String body = response.getContentAsString();
            // The handler HTML-escapes the message ('), so the quotes are not compared.
            if (response.getStatus() != 400 || !body.contains("Cannot sort by") || !body.contains("doesNotExist")) {
                wrong.add(listing.path() + " -> " + response.getStatus() + " " + body.substring(0, Math.min(80, body.length())));
            }
        }

        assertThat(wrong).as("lists that did not refuse a sort that names nothing").isEmpty();
    }

    @Test
    void answersANegativePageWith400() throws Exception {
        String token = token();
        List<String> wrong = new ArrayList<>();

        for (Listing listing : listings()) {
            int status = get(listing.path() + "?page=-1", token).getStatus();
            if (status != 400) {
                wrong.add(listing.path() + " -> " + status);
            }
        }

        assertThat(wrong).as("lists that did not refuse page=-1").isEmpty();
    }

    @Test
    void answersASizeBelowOneWith400() throws Exception {
        String token = token();
        List<String> wrong = new ArrayList<>();

        for (Listing listing : listings()) {
            int status = get(listing.path() + "?size=0", token).getStatus();
            if (status != 400) {
                wrong.add(listing.path() + " -> " + status);
            }
        }

        assertThat(wrong).as("lists that did not refuse size=0").isEmpty();
    }

    // ------------------------------------------------------------------ what is allowed works

    /** Without a single parameter: the defaults of every list are on its own whitelist. */
    @Test
    void answersEveryListWithItsDefaults() throws Exception {
        String token = token();
        List<String> wrong = new ArrayList<>();

        for (Listing listing : listings()) {
            MockHttpServletResponse response = get(listing.path(), token);
            if (!fine(listing, response.getStatus())) {
                wrong.add(listing.path() + " -> " + response.getStatus());
            }
        }

        assertThat(wrong).as("lists that fail with no parameter at all").isEmpty();
    }

    /**
     * Every entry of every whitelist, ascending and descending, against the real query. This is the test
     * that catches an entry the query cannot resolve - a list that says "sort by anything on me" and then
     * fails for one of them.
     */
    @Test
    void sortsByEveryColumnTheListSaysItCan() throws Exception {
        List<String> wrong = new ArrayList<>();

        for (Listing listing : listings()) {
            // A user of their own per list: all lists together are more calls than one user's rate limit.
            String token = token();
            for (String key : new TreeSet<>(listing.sortable())) {
                for (String direction : List.of("ASC", "DESC")) {
                    MockHttpServletResponse response = get(listing.path() + "?sort=" + key + "&order=" + direction, token);
                    if (!fine(listing, response.getStatus())) {
                        wrong.add(listing.path() + " sort=" + key + " " + direction + " -> " + response.getStatus());
                    }
                }
            }
        }

        assertThat(wrong).as("whitelisted columns the query cannot sort by").isEmpty();
    }

    // ------------------------------------------------------------------ particulars

    /** A page ordered by the password hash says something about the hash. It is not on the list. */
    @Test
    void doesNotLetTheUserListBeSortedByThePasswordHash() throws Exception {
        MockHttpServletResponse response = get("/users?sort=password", token());

        assertThat(response.getStatus()).isEqualTo(400);
        // The message repeats the refused name; what is allowed is the part after "one of".
        String allowed = response.getContentAsString().split("one of", 2)[1];
        assertThat(allowed).doesNotContain("password").contains("username");
        assertThat(UserController.USER_SORTABLE).doesNotContain("password");
    }

    /** {@code GET /orders} takes the status as text; an unknown one used to end in {@code valueOf}'s 500. */
    @Test
    void answersAnUnknownOrderStatusWith400() throws Exception {
        MockHttpServletResponse response = get("/orders?status=bogus", token());

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("Unknown order status").contains("bogus").contains("CREATED");
    }

    @Test
    void stillTakesTheStatusInAnyCase() throws Exception {
        assertThat(get("/orders?status=created", token()).getStatus()).isEqualTo(200);
        assertThat(get("/orders?status=Approved", token()).getStatus()).isEqualTo(200);
    }

    /** The error says which parameter and what is allowed - the point of a 400 over a 500. */
    @Test
    void saysWhichParameterIsWrong() throws Exception {
        String token = token();

        assertThat(get("/shipments?page=-3", token).getContentAsString()).contains("page cannot be negative, got -3");
        assertThat(get("/shipments?size=0", token).getContentAsString()).contains("size has to be at least 1, got 0");
        assertThat(get("/shipments?sort=nope", token).getContentAsString()).contains("createdAt").contains("trackingNumber");
    }
}
