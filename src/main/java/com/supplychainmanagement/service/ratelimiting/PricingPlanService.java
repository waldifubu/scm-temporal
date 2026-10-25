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
     * The bucket a request draws from, decided in this order:
     * <ol>
     *   <li>a key of a paid plan ({@code BX001-}, {@code PX001-}, ...): a bucket of that key, on that plan;</li>
     *   <li>otherwise a logged-in user: a bucket of that user, {@link PricingPlan#USER} - whatever else
     *       the request sends in {@code X-API-KEY}, so inventing a key buys a logged-in caller nothing;</li>
     *   <li>otherwise any other key: a bucket of that key, {@link PricingPlan#FREE};</li>
     *   <li>otherwise the remote address, {@link PricingPlan#FREE}.</li>
     * </ol>
     * The key families are prefixed ({@code key:}, {@code user:}, {@code ip:}), so a header that merely
     * <em>reads</em> like another family's key cannot land in that family's bucket.
     * <p>
     * Behind a reverse proxy the remote address is the proxy's unless the container is told to read
     * {@code X-Forwarded-For} ({@code server.forward-headers-strategy}) - see the template properties.
     *
     * @param apiKey     the {@code X-API-KEY} header, may be null or blank
     * @param username   the authenticated user, null when nobody is logged in
     * @param remoteAddr the caller's address, the last resort
     */
    public Bucket resolveBucket(String apiKey, String username, String remoteAddr) {
        boolean hasKey = apiKey != null && !apiKey.isBlank();
        PricingPlan keyPlan = PricingPlan.resolvePlanFromApiKey(apiKey);

        if (hasKey && keyPlan != PricingPlan.FREE) {
            return bucket("key:" + apiKey, keyPlan);
        }
        if (username != null && !username.isBlank()) {
            return bucket("user:" + username, PricingPlan.USER);
        }
        if (hasKey) {
            return bucket("key:" + apiKey, PricingPlan.FREE);
        }
        return bucket("ip:" + remoteAddr, PricingPlan.FREE);
    }

    private Bucket bucket(String key, PricingPlan plan) {
        return cache.computeIfAbsent(key, ignored -> Bucket.builder()
                .addLimit(plan.getLimit())
                .build());
    }
}
