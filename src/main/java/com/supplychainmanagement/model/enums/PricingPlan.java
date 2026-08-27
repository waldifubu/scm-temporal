package com.supplychainmanagement.model.enums;

import io.github.bucket4j.Bandwidth;

import java.time.Duration;

public enum PricingPlan {
    // Declare method (could also be abstract)
    FREE {
        @Override
        public Bandwidth getLimit() {
            return Bandwidth.builder()
                    .capacity(2)
                    .refillGreedy(20, Duration.ofMinutes(2))
                    .build();
        }
    },
    BASIC {
        @Override
        public Bandwidth getLimit() {
            return Bandwidth.builder()
                    .capacity(40)
                    .refillGreedy(20, Duration.ofHours(1))
                    .build();
        }
    },
    PROFESSIONAL {
        @Override
        public Bandwidth getLimit() {
            return Bandwidth.builder()
                    .capacity(100)
                    .refillGreedy(40, Duration.ofHours(1))
                    .build();
        }
    },
    NO_LIMIT {
        @Override
        public Bandwidth getLimit() {
            return Bandwidth.builder()
                    .capacity(Long.MAX_VALUE)
                    .refillGreedy(Long.MAX_VALUE, Duration.ofDays(10))
                    .build();
        }
    };

    public static PricingPlan resolvePlanFromApiKey(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            return FREE;
        } else if (apiKey.startsWith("BX001-")) {
            return BASIC;
        } else if (apiKey.startsWith("PX001-")) {
            return PROFESSIONAL;
        } else if (apiKey.startsWith("NL001-ÄÖÜ")) {
            return NO_LIMIT;
        }
        return FREE;
    }

    public abstract Bandwidth getLimit();
}
