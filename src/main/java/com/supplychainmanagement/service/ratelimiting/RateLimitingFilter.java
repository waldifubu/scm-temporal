package com.supplychainmanagement.service.ratelimiting;

import tools.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Rate limiting for the JSON API. Deliberately NOT a @Component: as a filter bean Spring Boot would
 * register it for *every* request (Vaadin UIDL, Thymeleaf, static resources) and thereby throttle
 * the entire application. Registration happens in {@link RateLimitingConfig} with the URL pattern
 * /api/* instead.
 */
public class RateLimitingFilter extends OncePerRequestFilter {

    static final String API_KEY_HEADER = "X-API-KEY";

    private final PricingPlanService pricingPlanService;
    private final ObjectMapper objectMapper;

    public RateLimitingFilter(PricingPlanService pricingPlanService, ObjectMapper objectMapper) {
        this.pricingPlanService = pricingPlanService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        // The bucket key must never be null: PricingPlanService keeps the buckets in a
        // ConcurrentHashMap and computeIfAbsent(null, ...) would throw an NPE. Without an API key
        // the caller falls back to one bucket per remote address (and thus to PricingPlan.FREE)
        // rather than being rejected - a missing key is not a rate limit violation.
        String apiKey = request.getHeader(API_KEY_HEADER);
        String bucketKey = (apiKey != null && !apiKey.isBlank()) ? apiKey : "ip:" + request.getRemoteAddr();

        Bucket bucket = pricingPlanService.resolveBucket(bucketKey, apiKey);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (!probe.isConsumed()) {
            long millisToWait = TimeUnit.NANOSECONDS.toMillis(probe.getNanosToWaitForRefill());

            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.addHeader("Retry-After", String.valueOf(TimeUnit.MILLISECONDS.toSeconds(millisToWait)));

            Map<String, String> body = Map.of(
                    "error", "RATE_LIMIT_EXCEEDED",
                    "message", "Rate limit exceeded. Please try again in " + millisToWait + " ms"
            );

            objectMapper.writeValue(response.getWriter(), body);
            response.getWriter().flush();
            return;
        }

        response.addHeader("Token-Available", String.valueOf(probe.getRemainingTokens()));

        filterChain.doFilter(request, response);
    }
}
