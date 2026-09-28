package com.supplychainmanagement.config;

import com.supplychainmanagement.model.enums.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Keeps the {@code CHECK} constraints over {@link OrderStatus} columns in step with the enum.
 * <p>
 * Hibernate writes one on first creation - {@code order_status in ('CREATED', ...)} - and
 * {@code ddl-auto=update} never touches it again. A value added to the enum afterwards is therefore
 * rejected by the database, and not only on the order itself: {@code order_history} carries the same
 * check twice, so the audit row of the very first transition into the new status would fail as well.
 * That failure surfaces at commit inside the {@code AFTER_COMMIT} listener, which turns an operation
 * that actually succeeded into a 500. {@code PARTIALLY_DELIVERED} was the value that ran into it.
 * <p>
 * The wanted clause is built from {@code OrderStatus.values()}, so it cannot drift from the enum
 * again - the next added value is covered without touching this class. Runs on every start and does
 * nothing once each constraint lists every value, so it can stay in place.
 * <p>
 * A failure here is logged, not thrown: the application must not refuse to start over an audit
 * constraint, and the log then carries the statements to run by hand.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatusCheckConstraintMigration {

    /** Every column holding an OrderStatus, with the check Hibernate named after that column. */
    private static final List<StatusColumn> STATUS_COLUMNS = List.of(
            new StatusColumn("orders", "order_status"),
            new StatusColumn("order_history", "previous_status"),
            new StatusColumn("order_history", "new_status"));

    private final JdbcTemplate jdbcTemplate;

    private record StatusColumn(String table, String column) {
    }

    @EventListener(ApplicationReadyEvent.class)
    public void alignOrderStatusChecks() {
        STATUS_COLUMNS.forEach(this::align);
    }

    private void align(StatusColumn statusColumn) {
        String clause = currentCheckClause(statusColumn);
        if (clause == null || listsEveryStatus(clause)) {
            // No check at all, or one that already knows every value - nothing to do either way.
            return;
        }

        String wanted = Arrays.stream(OrderStatus.values())
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(","));
        String add = "ALTER TABLE " + statusColumn.table() + " ADD CONSTRAINT " + statusColumn.column()
                + " CHECK (" + statusColumn.column() + " in (" + wanted + "))";

        try {
            // Separate statements on purpose: MariaDB has no "modify check", and it commits
            // implicitly before each ALTER anyway, so a shared transaction would buy nothing.
            dropCheck(statusColumn);
            jdbcTemplate.execute(add);
            log.info("Aligned CHECK constraint {}.{} with OrderStatus", statusColumn.table(), statusColumn.column());
        } catch (RuntimeException e) {
            log.error("Could not align CHECK constraint {}.{} with OrderStatus - run by hand: {}",
                    statusColumn.table(), statusColumn.column(), add, e);
        }
    }

    /**
     * Hibernate writes the check into the <em>column</em> definition, and a column constraint is not
     * reachable through {@code DROP CONSTRAINT} - MariaDB answers "Can't DROP CONSTRAINT; check that
     * it exists". Redefining the column without the check is what removes it, which is why the column
     * type and nullability are read back and written again unchanged.
     * <p>
     * A constraint this migration added itself is a <em>table</em> constraint and does come off with
     * {@code DROP CONSTRAINT}, so that is tried first - after the first run the columns are in that
     * shape, and the next added enum value costs no column rewrite.
     * <p>
     * {@code MODIFY COLUMN} keeps type and nullability and drops everything else the column carried -
     * a default or a comment. None of these three has any.
     */
    private void dropCheck(StatusColumn statusColumn) {
        try {
            jdbcTemplate.execute("ALTER TABLE " + statusColumn.table()
                    + " DROP CONSTRAINT " + statusColumn.column());
            return;
        } catch (RuntimeException notATableConstraint) {
            log.debug("{}.{} carries a column check, rewriting the column instead",
                    statusColumn.table(), statusColumn.column());
        }

        Map<String, Object> definition = jdbcTemplate.queryForMap("""
                SELECT COLUMN_TYPE, IS_NULLABLE
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """, statusColumn.table(), statusColumn.column());

        String nullability = "NO".equals(definition.get("IS_NULLABLE")) ? " NOT NULL" : " NULL";
        jdbcTemplate.execute("ALTER TABLE " + statusColumn.table() + " MODIFY COLUMN " + statusColumn.column()
                + " " + definition.get("COLUMN_TYPE") + nullability);
    }

    private String currentCheckClause(StatusColumn statusColumn) {
        List<String> clauses = jdbcTemplate.queryForList("""
                SELECT CHECK_CLAUSE
                FROM information_schema.CHECK_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND CONSTRAINT_NAME = ?
                """, String.class, statusColumn.table(), statusColumn.column());

        return clauses.isEmpty() ? null : clauses.getFirst();
    }

    private static boolean listsEveryStatus(String clause) {
        return Arrays.stream(OrderStatus.values())
                .allMatch(status -> clause.contains("'" + status.name() + "'"));
    }
}
