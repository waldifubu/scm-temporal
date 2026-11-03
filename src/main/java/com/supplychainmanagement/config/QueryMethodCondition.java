package com.supplychainmanagement.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.mvc.condition.AbstractRequestCondition;

import java.util.Collection;
import java.util.List;

/**
 * Lets a request through only if its HTTP method is {@code QUERY}. The one thing {@code RequestMethodsRequestCondition}
 * cannot say, because it works on the {@code RequestMethod} enum, which has no such value.
 * <p>
 * A CORS preflight is an {@code OPTIONS} request that announces the method of the real one, so for it the
 * announced method is compared - without that, a browser could never make the call.
 */
final class QueryMethodCondition extends AbstractRequestCondition<QueryMethodCondition> {

    static final String METHOD = "QUERY";

    @Override
    protected Collection<?> getContent() {
        return List.of(METHOD);
    }

    @Override
    protected String getToStringInfix() {
        return " || ";
    }

    @Override
    public QueryMethodCondition combine(QueryMethodCondition other) {
        return this;
    }

    @Override
    public QueryMethodCondition getMatchingCondition(HttpServletRequest request) {
        String method = CorsUtils.isPreFlightRequest(request)
                ? request.getHeader("Access-Control-Request-Method")
                : request.getMethod();
        return METHOD.equals(method) ? this : null;
    }

    @Override
    public int compareTo(QueryMethodCondition other, HttpServletRequest request) {
        return 0;
    }
}
