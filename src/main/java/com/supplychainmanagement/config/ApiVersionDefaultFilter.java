package com.supplychainmanagement.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // Muss vor Spring MVC und Spring Security laufen
public class ApiVersionDefaultFilter extends OncePerRequestFilter {

    // If value doesn't exists, we take 1.0
    @Value("${spring.mvc.apiversion.default:1.0}")
    private String defaultVersion;

    @Override
    protected void doFilterInternal(HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String uri = request.getRequestURI();

        // Greift NUR, wenn /api/ aufgerufen wird UND KEINE Version enthalten ist (z.B. /api/products/1001)
        if (uri.startsWith("/api/") && !uri.matches("^/api/[0-9]+\\.[0-9]+/.*")) {
            HttpServletRequest wrappedRequest = getWrappedRequest(request, uri);

//            wrappedRequest.removeAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE);
//            wrappedRequest.removeAttribute(UrlPathHelper.PATH_ATTRIBUTE);

            filterChain.doFilter(wrappedRequest, response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private @NonNull HttpServletRequest getWrappedRequest(HttpServletRequest request, String uri) {
        String contextPath = request.getContextPath();
        String pathWithoutContext = uri.substring(contextPath.length());

        String newPathWithoutContext = pathWithoutContext.replaceFirst("^/api/", "/api/" + defaultVersion + "/");
        String newFullUri = contextPath + newPathWithoutContext;

        return new HttpServletRequestWrapper(request) {
            @Override
            public String getRequestURI() {
                return newFullUri;
            }

            @Override
            public StringBuffer getRequestURL() {
                StringBuffer url = new StringBuffer();
                url.append(request.getScheme())
                        .append("://")
                        .append(request.getServerName())
                        .append(":")
                        .append(request.getServerPort())
                        .append(newFullUri);
                return url;
            }

            @Override
            public String getServletPath() {
                return newPathWithoutContext;
            }
        };
    }
}