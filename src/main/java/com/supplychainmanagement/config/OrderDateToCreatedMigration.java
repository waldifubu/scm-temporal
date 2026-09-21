package com.supplychainmanagement.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Moves {@code orders.order_date} into {@code orders.created} and drops the old column.
 * <p>
 * No entity maps {@code order_date} any more - {@code Order} keeps the creation time in
 * {@code created} alone. {@code ddl-auto=update} adds columns but never removes one, so without this
 * the old column would stay around for good.
 * <p>
 * Runs on every start and does nothing once the column is gone, so it can stay in place. The two
 * steps cannot share a transaction: MariaDB commits implicitly before an {@code ALTER TABLE}. If the
 * drop fails, the next start copies again - the same values onto the same rows - and retries it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderDateToCreatedMigration {

    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        if (!orderDateColumnExists()) {
            return;
        }

        // A row without an order_date keeps the created value it already has instead of losing it
        // to a NULL.
        int copied = jdbcTemplate.update("""
                UPDATE orders
                SET created = order_date
                WHERE order_date IS NOT NULL
                """);
        log.info("Migration order_date -> created: copied {} row(s)", copied);

        jdbcTemplate.execute("ALTER TABLE orders DROP COLUMN order_date");
        log.info("Migration order_date -> created: dropped column orders.order_date");
    }

    private boolean orderDateColumnExists() {
        Integer columns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'orders'
                  AND COLUMN_NAME = 'order_date'
                """, Integer.class);

        return columns != null && columns > 0;
    }
}
