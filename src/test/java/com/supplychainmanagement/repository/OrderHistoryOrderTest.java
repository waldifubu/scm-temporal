package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderHistory;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The order of an order's history against the real database, tie-break included.
 * <p>
 * Rows written within one transaction carry the <em>same</em> {@code changedAt} - measured, 251 of
 * 300 three-row histories, although the column is {@code datetime(6)} - so for them the id is the
 * whole order. The endpoint tests see that only now and then (one run in twelve with the tie-break
 * turned around), because they read one history per test. This one reads many in a row, so a tie-break
 * that runs the wrong way cannot pass by luck.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class OrderHistoryOrderTest {

    private static final int HISTORIES = 40;

    @Autowired
    private TestData testData;
    @Autowired
    private OrderHistoryRepository repository;
    @Autowired
    private EntityManager entityManager;

    /** Newest first: the row written last comes first, whether or not the timestamps differ. */
    @Test
    void listsTheLatestRowFirstEvenWhenTheTimestampsAreEqual() {
        User customer = testData.customer();
        int tied = 0;

        for (int i = 0; i < HISTORIES; i++) {
            Order order = testData.order(customer);
            OrderHistory created = testData.historyRow(order, null, OrderStatus.CREATED, null);
            OrderHistory acknowledged = testData.historyRow(order, OrderStatus.CREATED, OrderStatus.ACKNOWLEDGED, null);
            OrderHistory approved = testData.historyRow(order, OrderStatus.ACKNOWLEDGED, OrderStatus.APPROVED, null);
            entityManager.clear();

            List<OrderHistory> rows = repository.findByOrderIdOrderByChangedAtDescIdDesc(order.getId());

            assertThat(rows).extracting(OrderHistory::getId)
                    .as("history %d, written oldest to newest, read newest first", i)
                    .containsExactly(approved.getId(), acknowledged.getId(), created.getId());
            if (rows.get(0).getChangedAt().equals(rows.get(1).getChangedAt())
                    || rows.get(1).getChangedAt().equals(rows.get(2).getChangedAt())) {
                tied++;
            }
        }

        // Only worth something if the tie-break was exercised at least once. Where timestamps happen
        // never to tie (another clock), the test is reported as skipped rather than passing without
        // having tested it.
        assumeThat(tied).as("histories with two rows sharing a timestamp").isGreaterThan(0);
    }
}
