package com.supplychainmanagement.config;

import com.supplychainmanagement.annotation.HttpQuery;
import org.springframework.boot.webmvc.autoconfigure.WebMvcRegistrations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.web.servlet.mvc.condition.RequestCondition;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Everything the HTTP {@code QUERY} method needs besides the annotation {@link HttpQuery}, in the places that each
 * refuse it by default:
 * <ul>
 *   <li>Spring MVC: a handler mapping that gives a method annotated {@code @HttpQuery} the condition that only
 *       {@code QUERY} passes.</li>
 *   <li>Spring Security: {@code StrictHttpFirewall} rejects any method outside its short list before a filter
 *       sees the request, so the method is added to it.</li>
 * </ul>
 * The servlet side needs nothing: since Spring 7 {@code FrameworkServlet} dispatches every method it does not know
 * from the servlet API itself, and Tomcat takes any method token. The allowed CORS methods are in
 * {@code SecurityBeanConfig}.
 */
@Configuration
public class QueryMethodConfig {

    /** Boot hands the mapping created here the API versioning, content negotiation and interceptors as for its own. */
    @Bean
    public WebMvcRegistrations queryMethodRegistrations() {
        return new WebMvcRegistrations() {
            @Override
            public RequestMappingHandlerMapping getRequestMappingHandlerMapping() {
                return new QueryHandlerMapping();
            }
        };
    }

    @Bean
    public StrictHttpFirewall httpFirewall() {
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        firewall.setAllowedHttpMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", QueryMethodCondition.METHOD));
        return firewall;
    }

    /** Adds the {@code QUERY} condition to the mapping of every {@code @HttpQuery} method. */
    static class QueryHandlerMapping extends RequestMappingHandlerMapping {

        @Override
        protected RequestCondition<?> getCustomMethodCondition(Method method) {
            return AnnotatedElementUtils.hasAnnotation(method, HttpQuery.class)
                    ? new QueryMethodCondition()
                    : super.getCustomMethodCondition(method);
        }
    }
}
