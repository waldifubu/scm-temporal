package com.supplychainmanagement.service.ratelimiting;

import com.supplychainmanagement.model.enums.PricingPlan;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which bucket a request draws from: a paid key, else the logged-in user, else any key, else the
 * address - and that the buckets of those families never meet.
 */
class PricingPlanServiceTest {

    private final PricingPlanService service = new PricingPlanService();

    @Test
    void aLoggedInUserHasABucketOfTheirOwnAcrossAddressesAndKeys() {
        Bucket first = service.resolveBucket(null, "ada", "10.0.0.1");

        assertThat(service.resolveBucket(null, "ada", "10.0.0.2")).isSameAs(first);
        assertThat(service.resolveBucket("anything", "ada", "10.0.0.3")).isSameAs(first);
        assertThat(service.resolveBucket(null, "grace", "10.0.0.1")).isNotSameAs(first);
    }

    @Test
    void aLoggedInUserGetsTheUserPlanNotTheFreeOne() {
        Bucket user = service.resolveBucket(null, "ada", "10.0.0.1");
        Bucket anonymous = service.resolveBucket(null, null, "10.0.0.1");

        assertThat(user.getAvailableTokens()).isEqualTo(PricingPlan.USER.getLimit().getCapacity());
        assertThat(anonymous.getAvailableTokens()).isEqualTo(PricingPlan.FREE.getLimit().getCapacity());
        assertThat(PricingPlan.USER.getLimit().getCapacity()).isGreaterThan(PricingPlan.FREE.getLimit().getCapacity());
    }

    /** Inventing a key must not give a logged-in caller a bucket of its own per request. */
    @Test
    void anInventedKeyDoesNotLeaveTheUsersBucket() {
        Bucket first = service.resolveBucket("invented-1", "ada", "10.0.0.1");

        assertThat(service.resolveBucket("invented-2", "ada", "10.0.0.1")).isSameAs(first);
    }

    @Test
    void aPaidKeyKeepsItsOwnPlanAndBucketEvenWhenLoggedIn() {
        Bucket professional = service.resolveBucket("PX001-abc", "ada", "10.0.0.1");

        assertThat(professional.getAvailableTokens()).isEqualTo(PricingPlan.PROFESSIONAL.getLimit().getCapacity());
        assertThat(service.resolveBucket("PX001-abc", "grace", "10.0.0.9")).isSameAs(professional);
        assertThat(service.resolveBucket(null, "ada", "10.0.0.1")).isNotSameAs(professional);
    }

    @Test
    void withoutLoginAKeyIsItsOwnFreeBucket() {
        Bucket keyed = service.resolveBucket("some-key", null, "10.0.0.1");

        assertThat(service.resolveBucket("some-key", null, "10.0.0.2")).isSameAs(keyed);
        assertThat(keyed.getAvailableTokens()).isEqualTo(PricingPlan.FREE.getLimit().getCapacity());
    }

    @Test
    void withoutLoginAndKeyTheAddressDecides() {
        Bucket first = service.resolveBucket(null, null, "10.0.0.1");

        assertThat(service.resolveBucket("  ", null, "10.0.0.1")).isSameAs(first);
        assertThat(service.resolveBucket(null, null, "10.0.0.2")).isNotSameAs(first);
    }

    /** A header that merely reads like another family's key must not reach that family's bucket. */
    @Test
    void theFamiliesOfKeysNeverMeet() {
        Bucket user = service.resolveBucket(null, "ada", "10.0.0.1");
        Bucket address = service.resolveBucket(null, null, "10.0.0.1");

        assertThat(service.resolveBucket("user:ada", null, "10.9.9.9")).isNotSameAs(user);
        assertThat(service.resolveBucket("ip:10.0.0.1", null, "10.9.9.9")).isNotSameAs(address);
    }

    @Test
    void userPlanIsNeverResolvedFromAKey() {
        assertThat(PricingPlan.resolvePlanFromApiKey("USER")).isEqualTo(PricingPlan.FREE);
        assertThat(PricingPlan.resolvePlanFromApiKey(null)).isEqualTo(PricingPlan.FREE);
        assertThat(PricingPlan.resolvePlanFromApiKey("BX001-x")).isEqualTo(PricingPlan.BASIC);
    }
}
