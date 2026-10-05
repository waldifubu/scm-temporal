package com.supplychainmanagement.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Makes {@code order_history.user_id} nullable, which is what the code has always assumed.
 * <p>
 * A status change does not always have a user behind it: a scheduled sweep runs as {@code "system"},
 * which resolves to nobody, and {@code OrderService.update(id, order)} passes no id at all.
 * {@code FulfillmentServiceImpl.userIdOf} returns {@code null} for exactly that case and says so in
 * its Javadoc - but the column was created {@code NOT NULL}, so the audit row could not be written.
 * <p>
 * That failure is worse than it looks: the row is inserted by {@code OrderStatusChangedListener} in
 * an {@code AFTER_COMMIT} listener, so the status change itself is already committed. The exception
 * propagates out of the outer commit and the caller sees a 500 for an operation that succeeded.
 * <p>
 * Runs on every start and does nothing once the column is nullable, so it can stay in place. A
 * failure is logged rather than thrown - the application must not refuse to start over it, and the
 * log then carries the statement to run by hand.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderHistoryUserIdMigration {

    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void allowHistoryWithoutAUser() {
        Map<String, Object> definition = jdbcTemplate.queryForMap("""
                SELECT COLUMN_TYPE, IS_NULLABLE
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'order_history'
                  AND COLUMN_NAME = 'user_id'
                """);

        if (!"NO".equals(definition.get("IS_NULLABLE"))) {
            return;
        }

        // The type is read back rather than assumed: MODIFY COLUMN rewrites the whole definition, so
        // naming a type of its own here would quietly change the column.
        String statement = "ALTER TABLE order_history MODIFY COLUMN user_id "
                + definition.get("COLUMN_TYPE") + " NULL";

        try {
            jdbcTemplate.execute(statement);
            log.info("order_history.user_id is nullable now - a status change without a user keeps its audit row");
        } catch (RuntimeException e) {
            log.error("Could not make order_history.user_id nullable - run by hand: {}", statement, e);
        }
    }
}
