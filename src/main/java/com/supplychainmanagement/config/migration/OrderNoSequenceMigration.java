package com.supplychainmanagement.config.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates the sequence the order numbers are counted with.
 * <p>
 * {@code ddl-auto} cannot: the sequence hangs on no entity. It is read by
 * {@code OrderRepository.nextOrderNoCounter()} and handed to
 * {@code OrderNumberScrambler.orderNoFor(long)}, which permutes it into an eight-digit order number.
 * <p>
 * Why a sequence of its own rather than {@code Order.id}: the id only exists after the insert, so the
 * order would have to be written with {@code order_no} null and updated right after - two statements,
 * and the creation event firing on an unfinished row. A sequence is read before the insert.
 * <p>
 * <strong>Gaps are fine, reuse is not.</strong> A MariaDB sequence is deliberately not
 * transactional: a rolled-back order keeps its drawn counter, and the server's default cache of
 * 1000 means a restart can skip a block. Neither matters - nothing counts order numbers, they only
 * have to be unique. Handing the same counter out twice is the one thing that must not happen, and
 * that is exactly what a sequence guarantees and what the old
 * {@code Math.random() + existsByOrderNo} loop could not.
 * <p>
 * Starts at 1, which is safe on a populated database: the scrambler maps every counter into
 * 90000000..99999999, while the order numbers that exist from before are four-digit (3632..9038 at
 * the time of writing). The two ranges cannot meet.
 * <p>
 * Idempotent through {@code IF NOT EXISTS}. Failure is logged with the statement to run by hand and
 * never thrown - but note that order creation does not work without the sequence, and says so
 * (see {@code OrderServiceImpl.nextOrderNo}); it is not a defect that can pass unnoticed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderNoSequenceMigration {

    /** The name {@code OrderRepository.nextOrderNoCounter()} reads. */
    public static final String SEQUENCE = "order_no_seq";

    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void createOrderNoSequence() {
        String statement = "CREATE SEQUENCE IF NOT EXISTS " + SEQUENCE + " START WITH 1 INCREMENT BY 1";
        try {
            jdbcTemplate.execute(statement);

            // next_not_cached_value, not last_value - that one is PostgreSQL. Read only to log that
            // the sequence is really there; NEXTVAL is deliberately not called here, it would burn a
            // counter on every start.
            Long next = jdbcTemplate.queryForObject(
                    "SELECT next_not_cached_value FROM " + SEQUENCE, Long.class);
            log.info("Order number sequence {} is in place, next uncached value {}", SEQUENCE, next);
        } catch (RuntimeException e) {
            log.error("Could not create the order number sequence - orders cannot be created until it "
                    + "exists. Run by hand: {}", statement, e);
        }
    }
}
