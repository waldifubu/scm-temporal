package com.supplychainmanagement.config;

import com.supplychainmanagement.config.migration.OrderNotNullMigration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderNotNullMigrationTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OrderNotNullMigration migration = new OrderNotNullMigration(jdbc);

    private void column(String name, String nullable, long missing) {
        when(jdbc.queryForList(anyString(), eq("orders"), eq(name)))
                .thenReturn(List.of(Map.of("column_type", "bigint(20)", "is_nullable", nullable)));
        when(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE " + name + " IS NULL", Long.class))
                .thenReturn(missing);
    }

    @Test
    void tightensWhatIsNullableAndFull() {
        column("order_no", "YES", 0);
        column("order_status", "NO", 0);
        column("total", "YES", 0);

        migration.makeColumnsRequired();

        verify(jdbc).execute("ALTER TABLE orders MODIFY COLUMN order_no bigint(20) NOT NULL");
        verify(jdbc).execute("ALTER TABLE orders MODIFY COLUMN total bigint(20) NOT NULL");
        verify(jdbc, never()).execute("ALTER TABLE orders MODIFY COLUMN order_status bigint(20) NOT NULL");
    }

    @Test
    void leavesAColumnAloneThatStillHoldsANull() {
        column("order_no", "YES", 2);
        column("order_status", "NO", 0);
        column("total", "NO", 0);

        migration.makeColumnsRequired();

        verify(jdbc, never()).execute(anyString());
    }
}
