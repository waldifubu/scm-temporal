package com.supplychainmanagement.model.enums;

import io.github.bucket4j.Bandwidth;

import java.time.Duration;

public enum PricingPlan {
    // Declare method (could also be abstract)
    FREE(null) {
        @Override
        public Bandwidth getLimit() {
            return Bandwidth.builder()
                    .capacity(2)
                    .refillGreedy(20, Duration.ofMinutes(2))
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
