package com.supplychainmanagement.service.ratelimiting;

import com.supplychainmanagement.exception.RateLimitExceededException;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
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
    private final HandlerExceptionResolver handlerExceptionResolver;

    public RateLimitingFilter(PricingPlanService pricingPlanService,
                              HandlerExceptionResolver handlerExceptionResolver) {
        this.pricingPlanService = pricingPlanService;
        this.handlerExceptionResolver = handlerExceptionResolver;
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
            long secondsToWait = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill());
            RateLimitExceededException tooManyRequests = new RateLimitExceededException(secondsToWait);

            // A servlet filter runs before the DispatcherServlet, so throwing here would
            // bypass the GlobalExceptionHandler — delegate to it explicitly instead.
            //
            // The return value decides whether that worked: a null ModelAndView means no resolver
            // took the exception, and simply returning would hand the caller an empty 200 instead of
            // the 429. Answer it here in that case - without the JSON body the advice would add.
            if (handlerExceptionResolver.resolveException(request, response, null, tooManyRequests) == null) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(tooManyRequests.getRetryAfterSeconds()));
            }
            return;
        }

        response.addHeader("Token-Available", String.valueOf(probe.getRemainingTokens()));

        filterChain.doFilter(request, response);
    }
}
