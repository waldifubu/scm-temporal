package com.supplychainmanagement.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two properties the scheme stands on - it is a permutation, and it does not look like a counter
 * - plus the range. The first one is the important one: it is what lets the uniqueness check and the
 * retry loop go away.
 */
class OrderNumberScramblerTest {

    /**
     * Every result is inside the range, starts with 9 and has the width the range has - read off
     * FIRST rather than written down, so moving the range does not turn this into a false assertion.
     */
    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 17, 1_000, 9_999})
    void staysInTheRange(long counter) {
        int width = String.valueOf(OrderNumberScrambler.FIRST).length();

        long orderNo = OrderNumberScrambler.orderNoFor(counter);

        assertThat(orderNo).isBetween(OrderNumberScrambler.FIRST, OrderNumberScrambler.LAST);
        assertThat(String.valueOf(orderNo)).hasSize(width).startsWith("9");
        // "at least 9000" on the other reading too: the leading four digits.
        assertThat(Long.parseLong(String.valueOf(orderNo).substring(0, 4))).isGreaterThanOrEqualTo(9000);
    }

    /** The last counter the range holds is still a valid one. */
    @Test
    void stillWorksAtTheLastCounter() {
        long orderNo = OrderNumberScrambler.orderNoFor(OrderNumberScrambler.COUNT);

        assertThat(orderNo).isBetween(OrderNumberScrambler.FIRST, OrderNumberScrambler.LAST);
    }

    /**
     * The Feistel block follows the range. It is not a correctness property but a cost one: cycle
     * walking repeats until a result lands inside the range, so a block far larger than the range
     * costs {@code BLOCK / COUNT} rounds per number - over a thousand when the two drift apart, as
     * they did when the range was narrowed from eight digits to five and the block stayed at 2^24.
     */
    @Test
    void theBlockFollowsTheRange() {
        long block = OrderNumberScrambler.blockSize();

        assertThat(block).isGreaterThanOrEqualTo(OrderNumberScrambler.COUNT);
        // The next size down would no longer hold the range - so this is the smallest one that does.
        assertThat(block / 4).isLessThan(OrderNumberScrambler.COUNT);
    }

    /**
     * The whole point, and over the <strong>entire</strong> range rather than a sample: every counter
     * the scheme accepts gets its own number, and together they use the range up exactly once. That
     * is what makes the uniqueness check and the retry loop unnecessary - a sample could only ever
     * say "no collision found yet".
     */
    @Test
    void isABijectionOverTheWholeRange() {
        Set<Long> seen = new HashSet<>((int) (OrderNumberScrambler.COUNT * 2));

        for (long counter = 1; counter <= OrderNumberScrambler.COUNT; counter++) {
            assertThat(seen.add(OrderNumberScrambler.orderNoFor(counter)))
                    .as("counter %d produced a number already handed out", counter)
                    .isTrue();
        }

        assertThat(seen).hasSize((int) OrderNumberScrambler.COUNT);
        assertThat(seen).allSatisfy(orderNo -> assertThat(orderNo)
                .isBetween(OrderNumberScrambler.FIRST, OrderNumberScrambler.LAST));
    }

    /**
     * It must not read as a counter. Neighbouring inputs land far apart - measured rather than
     * asserted by eye: over 10,000 consecutive pairs the average distance is a large share of the
     * range, and the difference is not a constant (which is what a plain {@code id * prime mod n}
     * would give, where every pair differs by the same step).
     */
    @Test
    void consecutiveCountersLandFarApartAndNotByAFixedStep() {
        int pairs = (int) Math.min(10_000, OrderNumberScrambler.COUNT - 1);
        long totalDistance = 0;
        Set<Long> differences = new HashSet<>();

        for (long counter = 1; counter <= pairs; counter++) {
            long here = OrderNumberScrambler.orderNoFor(counter);
            long next = OrderNumberScrambler.orderNoFor(counter + 1);
            totalDistance += Math.abs(next - here);
            differences.add(next - here);
        }

        long averageDistance = totalDistance / pairs;
        // A counter would give 1. A third of the range is the expectation for a random permutation.
        assertThat(averageDistance).isGreaterThan(OrderNumberScrambler.COUNT / 5);
        // A multiplicative scheme would give exactly one or two distinct differences.
        assertThat(differences).hasSizeGreaterThan(pairs / 2);
    }

    /** The first few are not 1, 2, 3 and not in order - which is what was asked for. */
    @Test
    void doesNotStartOffLookingSequential() {
        long first = OrderNumberScrambler.orderNoFor(1);
        long second = OrderNumberScrambler.orderNoFor(2);
        long third = OrderNumberScrambler.orderNoFor(3);

        assertThat(second - first).isNotEqualTo(third - second);
        assertThat(Math.abs(second - first)).isGreaterThan(OrderNumberScrambler.COUNT / 100);
    }

    /** The same counter always gives the same number - it is a mapping, not a draw. */
    @Test
    void isDeterministic() {
        assertThat(OrderNumberScrambler.orderNoFor(4711))
                .isEqualTo(OrderNumberScrambler.orderNoFor(4711));
    }

    /**
     * What the range holds, stated so a narrowing shows up here rather than as a failed order
     * months later. This is the ceiling of the whole scheme - one order past it is refused.
     */
    @Test
    void saysHowManyOrdersTheRangeHolds() {
        System.out.println("order number range " + OrderNumberScrambler.FIRST + ".."
                + OrderNumberScrambler.LAST + " holds " + OrderNumberScrambler.COUNT + " orders");

        assertThat(OrderNumberScrambler.COUNT)
                .isEqualTo(OrderNumberScrambler.LAST - OrderNumberScrambler.FIRST + 1);
    }

    /** No legacy order number can be produced: the existing ones are 3632..9038. */
    @Test
    void cannotCollideWithTheNumbersAlreadyInTheDatabase() {
        assertThat(OrderNumberScrambler.FIRST).isGreaterThan(9_038L);
    }

    /**
     * Exhaustion is refused, not wrapped. Wrapping would start handing out numbers that are already
     * in use - the failure this class exists to remove, back again and harder to see.
     */
    @Test
    void refusesToWrapWhenTheRangeIsUsedUp() {
        assertThatThrownBy(() -> OrderNumberScrambler.orderNoFor(OrderNumberScrambler.COUNT + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exhausted");
    }

    @Test
    void refusesACounterBelowOne() {
        assertThatThrownBy(() -> OrderNumberScrambler.orderNoFor(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** What the numbers actually look like - printed so the choice can be judged, not just trusted. */
    @Test
    void showsWhatTheFirstTenLookLike() {
        StringBuilder sample = new StringBuilder();
        for (long counter = 1; counter <= 10; counter++) {
            sample.append(OrderNumberScrambler.orderNoFor(counter)).append(' ');
        }
        System.out.println("orderNo for counters 1..10: " + sample.toString().trim());

        assertThat(sample).isNotEmpty();
    }
}
