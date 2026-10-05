package com.supplychainmanagement.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns {@code request_components.id} into the auto-increment key the entity expects.
 * <p>
 * The table was created while {@code RequestComponent.id} was a {@code UUID}, so the column is of
 * type {@code uuid} and carries no {@code AUTO_INCREMENT}. The entity has since become a
 * {@code Long} with {@code GenerationType.IDENTITY}, which makes Hibernate leave the column out of
 * the insert and expect the database to fill it - and MariaDB answers
 * {@code Field 'id' doesn't have a default value}. Every other table in the schema has
 * {@code bigint auto_increment} here; this one is the exception, because {@code ddl-auto=update} adds
 * missing columns but never changes the type of one that exists.
 * <p>
 * Runs on every start and does nothing once the column is an auto-increment, so it can stay in place.
 * On a database created from the current entities Hibernate gets it right and this never fires.
 * <p>
 * It refuses to touch a table that has rows: the conversion only works by dropping the column, which
 * would throw those keys away. That case needs a decision, not a migration, so it is logged and left
 * alone.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequestComponentIdMigration {

    /**
     * Dropped and added back rather than modified: MariaDB refuses the conversion outright with
     * "Cannot cast 'uuid' as 'bigint' in assignment", whatever the table holds. Adding the column
     * with PRIMARY KEY in the same statement is what lets it be an AUTO_INCREMENT, which has to be a
     * key, and FIRST puts it back where it was. Dropping the only primary-key column takes the key
     * with it, so no separate DROP PRIMARY KEY is needed.
     */
    private static final List<String> STATEMENTS = List.of(
            "ALTER TABLE request_components DROP COLUMN id",
            "ALTER TABLE request_components ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST");

    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void makeTheKeyGenerateItself() {
        Map<String, Object> definition = jdbcTemplate.queryForMap("""
                SELECT COLUMN_TYPE, EXTRA
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'request_components'
                  AND COLUMN_NAME = 'id'
                """);

        String extra = String.valueOf(definition.get("EXTRA")).toLowerCase(Locale.ROOT);
        if (extra.contains("auto_increment")) {
            return;
        }

        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM request_components", Integer.class);
        if (rows != null && rows > 0) {
            log.error("request_components.id is {} and not an auto-increment, but the table holds {} row(s) -"
                            + " the column has to be dropped to convert it and that would discard them."
                            + " Decide what happens to those rows, then run: {}",
                    definition.get("COLUMN_TYPE"), rows, String.join("; ", STATEMENTS));
            return;
        }

        try {
            STATEMENTS.forEach(jdbcTemplate::execute);
            log.info("request_components.id is an auto-increment now - was {}", definition.get("COLUMN_TYPE"));
        } catch (RuntimeException e) {
            log.error("Could not make request_components.id an auto-increment - run by hand: {}",
                    String.join("; ", STATEMENTS), e);
        }
    }
}
