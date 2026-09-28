package com.supplychainmanagement.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Gives every existing component row a usable {@code qty}.
 * <p>
 * {@code Component.qty} is new, and {@code ddl-auto=update} adds the column to a table that already
 * has rows. Whether those rows end up with the column default or with MariaDB's own {@code 0}
 * depends on how the {@code ALTER} is emitted - and {@code 0} would be the worse outcome by far:
 * {@code ProductionServiceImpl} would read the recipe as "needs none of this", find every storehouse
 * eligible and produce a finished product without decrementing anything. {@code assemble()} runs
 * that unattended every 150 s.
 * <p>
 * Idempotent: after the first run nothing matches the {@code WHERE} any more. Kept in place rather
 * than run once by hand, because the same thing happens again on any database the schema is created
 * on from an older state.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComponentQtyMigration {

    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void defaultMissingQuantities() {
        try {
            // A component that is part of a recipe is needed at least once - that is the assumption
            // the column was ignored under for as long as every line counted as one.
            int fixed = jdbcTemplate.update("UPDATE components SET qty = 1 WHERE qty IS NULL OR qty < 1");
            if (fixed > 0) {
                log.info("Component qty: set {} row(s) without a usable quantity to 1", fixed);
            }
        } catch (RuntimeException e) {
            log.error("Could not default component quantities - run by hand: "
                    + "UPDATE components SET qty = 1 WHERE qty IS NULL OR qty < 1", e);
        }
    }
}
