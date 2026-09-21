package com.supplychainmanagement.controller;

import com.supplychainmanagement.config.WebConfig;
import org.springframework.web.accept.ApiVersionStrategy;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;

/**
 * The project's own API version strategy from WebConfig, for standalone MockMvc - so controller
 * tests call /api/1.0/... like real clients do.
 */
final class ApiVersioningTestSupport {

    private ApiVersioningTestSupport() {
    }

    static ApiVersionStrategy apiVersionStrategy() {
        var configurer = new ApiVersionConfigurer() {
            ApiVersionStrategy strategy() {
                return getApiVersionStrategy();
            }
        };
        new WebConfig().configureApiVersioning(configurer);
        return configurer.strategy();
    }
}
