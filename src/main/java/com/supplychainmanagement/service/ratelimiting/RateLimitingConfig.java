package com.supplychainmanagement.service.ratelimiting;

import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class RateLimitingConfig {

    // Throttle the JSON API only. Vaadin (/app/*), the Thymeleaf pages and static resources stay
    // out of it - otherwise a single page view with its follow-up requests would exhaust the quota
    // immediately.
    @Bean
    public FilterRegistrationBean<RateLimitingFilter> rateLimitingFilterRegistration(
            PricingPlanService pricingPlanService, ObjectMapper objectMapper) {
        var registration = new FilterRegistrationBean<>(new RateLimitingFilter(pricingPlanService, objectMapper));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.LOWEST_PRECEDENCE);
        registration.setName("rateLimitingFilter");
        return registration;
    }
}
