package com.supplychainmanagement.model.enums;

import io.github.bucket4j.Bandwidth;

import java.time.Duration;

public enum PricingPlan {
    // Declare method (could also be abstract)

    /**
     * Whoever has not logged in and names no paid key - one bucket per remote address, and the one
     * that stands in front of the login. Deliberately small.
     */
    FREE(null) {
        @Override
        public Bandwidth getLimit() {
            return Bandwidth.builder()
                    .capacity(30)
                    .refillGreedy(20, Duration.ofMinutes(2))
                    .build();
        }
    },
    /**
     * A logged-in user without a paid key: a bucket of their own, keyed by user name, so one person's
     * traffic never uses up another's - and a burst a page makes (a dozen requests for one view) fits.
     * Never resolved from a key: {@link #resolvePlanFromApiKey} skips plans without a prefix.
     */
    USER(null) {
        @Override
        public Bandwidth getLimit() {
            return Bandwidth.builder()
                    .capacity(120)
                    .refillGreedy(60, Duration.ofMinutes(1))
                    .build();
        }
    },
    BASIC("BX001-") {
        @Override
        public Bandwidth getLimit() {
            return Bandwidth.builder()
                    .capacity(40)
                    .refillGreedy(20, Duration.ofHours(1))
                    .build();
        }
    },
    PROFESSIONAL("PX001-") {
        @Override
        public Bandwidth getLimit() {
            return Bandwidth.builder()
                    .capacity(100)
                    .refillGreedy(40, Duration.ofHours(1))
                    .build();
        }
    },
    NO_LIMIT("NL001-ÄÖÜ") {
        @Override
        public Bandwidth getLimit() {
            return Bandwidth.builder()
                    .capacity(Long.MAX_VALUE)
                    .refillGreedy(Long.MAX_VALUE, Duration.ofSeconds(1))
                    .build();
        }
    };

    private final String apiKeyPrefix;

    PricingPlan(String apiKeyPrefix) {
        this.apiKeyPrefix = apiKeyPrefix;
    }

    public static PricingPlan resolvePlanFromApiKey(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            return FREE;
        }
        for (PricingPlan plan : values()) {
            if (plan.apiKeyPrefix != null && apiKey.startsWith(plan.apiKeyPrefix)) {
                return plan;
            }
        }
        return FREE;
    }

    public abstract Bandwidth getLimit();
}
