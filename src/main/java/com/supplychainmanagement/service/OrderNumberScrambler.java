package com.supplychainmanagement.service;

/**
 * Turns a dense counter (1, 2, 3, ...) into an order number that looks arbitrary
 * (94_712_038, 90_255_461, 98_004_917, ...) without ever producing the same one twice.
 * <p>
 * <strong>Why not random.</strong> {@code randomOrderNo()} drew a number and asked the database
 * whether it was taken. That has three problems and the third is fatal: the check and the insert are
 * two statements, so two requests can pass it with the same number (the unique index then fails the
 * second one); the chance of a redraw grows with every order; and once the range is used up the loop
 * never terminates - it spins forever holding a request thread and its transaction. With 1000..9999
 * that ceiling was about 9000 orders.
 * <p>
 * <strong>What this does instead.</strong> It <em>permutes</em> the counter. A permutation is
 * bijective: different counters can never map to the same order number, so there is nothing to check
 * and nothing to retry. Uniqueness is a property of the arithmetic, not of a query that might race.
 * <p>
 * The permutation is a four-round {@link #feistel(long) Feistel network}. A Feistel is a permutation
 * whatever its round function does, which is what makes the guarantee easy to hold; the round
 * function only decides how scrambled the result looks. Consecutive counters come out unrelated -
 * that is the point of the construction and what {@code OrderNumberScramblerTest} measures.
 * <p>
 * <strong>The range.</strong> Every result lies in {@value #FIRST} .. {@value #LAST}, so it starts
 * with 9 and is never below 9000 - "at least 9000" on either reading of it. Every number has the
 * same width, and the range sits above the order numbers that already exist (3632..9038 at the time
 * of writing), so a collision with those is not merely unlikely but impossible.
 * <p>
 * <strong>It holds {@value #COUNT} numbers, and that is the ceiling of the whole scheme.</strong>
 * Reaching it is refused rather than wrapped - see {@link #orderNoFor(long)}. Widening it is a matter
 * of moving {@link #FIRST} and {@link #LAST}, but only before numbers are in use: a different range
 * is a different permutation, and a counter could then land on a number an earlier one already had.
 * <p>
 * A Feistel works on a power of two and the range is not one, so a result outside it is fed through
 * again until it lands inside (<em>cycle walking</em>, the standard trick in format-preserving
 * encryption). That preserves bijectivity - the walk is itself a permutation of the smaller set - and
 * costs {@code BLOCK / COUNT} rounds on average, which is why {@link #HALF_BITS} is derived from the
 * range instead of being written down.
 * <p>
 * <strong>Two things must never change</strong> once numbers are in the database: {@link #ROUND_KEYS}
 * and the range. They define which counter maps to which number; a different permutation of the same
 * range could hand out a number an earlier one already used. The unique index would catch it, but as
 * a failed order rather than as a near miss.
 * <p>
 * It is deliberately <strong>not</strong> a hash of {@code Order.id}. That reads well - the id is a
 * dense counter too - but the id only exists after the insert, so the order would have to be written
 * with {@code order_no} null and updated right after: two statements, a window in which the order has
 * no number, and the creation event firing on a row that is not finished. A counter of its own is
 * read before the insert, so the number is there when the order is first written. Same mathematics,
 * one statement.
 */
public final class OrderNumberScrambler {

    /** The lowest order number this can produce. */
    public static final long FIRST = 90_000L;

    /** The highest order number this can produce. */
    public static final long LAST = 199_999L;

    /** How many different order numbers there are - the capacity of the scheme. */
    public static final long COUNT = LAST - FIRST + 1;

    /**
     * Half the Feistel block, derived from {@link #COUNT} rather than written down: the block is the
     * smallest power of two that holds the range, so a change to {@link #FIRST} or {@link #LAST}
     * carries over by itself.
     * <p>
     * It matters for speed, not for correctness. Cycle walking repeats until a result lands inside
     * the range, so it costs {@code BLOCK / COUNT} rounds on average - about 1.6 when the block fits
     * the range, and over a thousand when it does not.
     */
    private static final int HALF_BITS = halfBitsFor(COUNT);
    private static final int HALF_MASK = (1 << HALF_BITS) - 1;
    private static final long BLOCK = 1L << (2 * HALF_BITS);

    /**
     * One constant per round. Arbitrary odd 64-bit values - they only have to differ, so that the
     * rounds do not cancel each other out. <strong>Never change these.</strong>
     */
    private static final long[] ROUND_KEYS = {
            0x9E3779B97F4A7C15L,
            0xBF58476D1CE4E5B9L,
            0x94D049BB133111EBL,
            0x2545F4914F6CDD1DL,
    };

    private OrderNumberScrambler() {
    }

    /** The smallest half-width whose full block holds {@code count}. */
    private static int halfBitsFor(long count) {
        int bits = 1;
        while ((1L << (2 * bits)) < count) {
            bits++;
        }
        return bits;
    }

    /**
     * How wide the Feistel block is - exposed so a test can check it really follows the range rather
     * than standing at whatever it was written at.
     */
    static long blockSize() {
        return BLOCK;
    }

    /**
     * The order number for the given counter value.
     *
     * @param counter a value the caller hands out once and never again - a database sequence. Any
     *                positive value is accepted; 1 is the first.
     * @return an order number in {@link #FIRST}..{@link #LAST}, different for every different
     *         counter
     * @throws IllegalArgumentException if the counter is below 1, or beyond what the range can hold.
     *                                 Both are refused rather than wrapped: wrapping would quietly
     *                                 start handing out numbers that are already in use, which is
     *                                 exactly the failure this class exists to remove.
     */
    public static long orderNoFor(long counter) {
        if (counter < 1) {
            throw new IllegalArgumentException("Order number counter starts at 1, got " + counter);
        }
        if (counter > COUNT) {
            throw new IllegalArgumentException("Order numbers are exhausted: the counter is at "
                    + counter + " and the range " + FIRST + ".." + LAST + " holds " + COUNT);
        }

        // Counters are 1-based, the permutation is 0-based.
        long walked = counter - 1;
        do {
            walked = feistel(walked);
        } while (walked >= COUNT);

        return FIRST + walked;
    }

    /**
     * A four-round balanced Feistel network: split the value in half, and four times
     * replace one half with itself XOR a function of the other. Every round is reversible whatever
     * {@link #round(int, int)} returns, so the whole thing is a permutation of
     * {@code 0..}{@link #BLOCK}{@code -1}.
     */
    private static long feistel(long value) {
        int left = (int) ((value >>> HALF_BITS) & HALF_MASK);
        int right = (int) (value & HALF_MASK);

        for (int r = 0; r < ROUND_KEYS.length; r++) {
            int mixed = left ^ round(r, right);
            left = right;
            right = mixed;
        }

        return ((long) left << HALF_BITS) | right;
    }

    /**
     * The round function - a 64-bit avalanche mixer (the splitmix64 finalizer), truncated to half a
     * block. It does not have to be reversible and it does not have to be cryptography; it only has
     * to spread one bit of input across the whole half, so that counters next to each other land
     * nowhere near each other.
     */
    private static int round(int number, int half) {
        long x = half + ROUND_KEYS[number];
        x ^= x >>> 33;
        x *= 0xFF51AFD7ED558CCDL;
        x ^= x >>> 33;
        x *= 0xC4CEB9FE1A85EC53L;
        x ^= x >>> 33;
        return (int) (x & HALF_MASK);
    }
}
