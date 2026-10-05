package com.supplychainmanagement.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Widens {@code request_components.qty} from {@code int} to {@code bigint}, so the column matches
 * {@code RequestComponent.qty}, which is a {@code Long}.
 * <p>
 * The entity's type was widened at some point and {@code ddl-auto=update} never follows a type
 * change - the same drift that left {@code request_components.id} a {@code uuid}
 * ({@link RequestComponentIdMigration}) and the enum CHECK constraints behind their enums
 * ({@link StatusCheckConstraintMigration}). What it cost here was the upper half of the range: with
 * {@code STRICT_TRANS_TABLES} the server refused an out-of-range quantity at the insert, so a
 * mistyped number came back as a 500 rather than a 400 - and anybody reading the entity would have
 * assumed a {@code Long} was storable.
 * <p>
 * Unlike the id, this one is a plain {@code MODIFY COLUMN}: {@code int} to {@code bigint} is a
 * widening, every existing value fits, and MariaDB converts in place - so it is safe on a populated
 * table, no dropping and re-adding and no emptiness to check for.
 * <p>
 * Idempotent: the current type is read from {@code information_schema} first and a column that is
 * already {@code bigint} is left alone. The nullability is read along and kept, so a {@code NOT NULL}
 * column stays one - {@code MODIFY COLUMN} rewrites the whole definition and would otherwise drop it.
 * <p>
 * Failure is logged with the statement to run by hand, never thrown: the application is already up,
 * and the only thing a smaller column costs is a range nothing legitimately reaches
 * ({@code RequestComponentsRequest.MAX_QTY} caps a request at a million).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequestComponentQtyMigration {

    private static final String TABLE = "request_components";
    private static final String COLUMN = "qty";

    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void widenQuantityColumn() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT data_type, is_nullable
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                    """, TABLE, COLUMN);

            if (rows.isEmpty()) {
                // Nothing to widen - the table is created from the entity on a fresh database, and
                // Hibernate writes a bigint there in the first place.
                log.debug("Request component qty: no {}.{} column yet, nothing to widen", TABLE, COLUMN);
                return;
            }

            Map<String, Object> column = rows.getFirst();
            String dataType = String.valueOf(column.get("data_type")).toLowerCase();
            if ("bigint".equals(dataType)) {
                return;
            }

            boolean nullable = "YES".equalsIgnoreCase(String.valueOf(column.get("is_nullable")));
            String statement = "ALTER TABLE " + TABLE + " MODIFY COLUMN " + COLUMN + " BIGINT"
                    + (nullable ? " NULL" : " NOT NULL");

            jdbcTemplate.execute(statement);
            log.info("Request component qty: widened {}.{} from {} to bigint", TABLE, COLUMN, dataType);
        } catch (RuntimeException e) {
            log.error("Could not widen {}.{} to bigint - run by hand: "
                            + "ALTER TABLE {} MODIFY COLUMN {} BIGINT NOT NULL",
                    TABLE, COLUMN, TABLE, COLUMN, e);
        }
    }
}
