package com.supplychainmanagement.service.ratelimiting;

import com.supplychainmanagement.model.enums.PricingPlan;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PricingPlanService {
    private final Map<String, Bucket> cache = new ConcurrentHashMap<>();

    /**
     * @param bucketKey identifies the bucket (API key, otherwise the remote address) - must not be
     *                  null, ConcurrentHashMap does not allow null keys.
     * @param apiKey    determines the plan; null or empty yields {@link PricingPlan#FREE}.
     */
    public Bucket resolveBucket(String bucketKey, String apiKey) {
        return cache.computeIfAbsent(bucketKey, ignored -> newBucket(apiKey));
    }

    private Bucket newBucket(String apiKey) {
        PricingPlan pricingPlan = PricingPlan.resolvePlanFromApiKey(apiKey);
        return Bucket.builder()
                .addLimit(pricingPlan.getLimit())
                .build();
    }
}
