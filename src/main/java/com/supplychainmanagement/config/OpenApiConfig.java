package com.supplychainmanagement.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

/**
 * The OpenAPI description of the REST API (springdoc), at {@code /v3/api-docs} and, as a page to try
 * it from, {@code /swagger-ui.html}.
 * <p>
 * <strong>Off unless somebody turned it on.</strong> springdoc defaults to on, which would publish every
 * endpoint and role of the API to anybody who asks. {@code openapi-defaults.properties} turns both off
 * and ranks below {@code application.properties}, so a properties file that says nothing gets nothing;
 * the development profile sets {@code springdoc.api-docs.enabled=true} and
 * {@code springdoc.swagger-ui.enabled=true}. Both live outside {@code /api}, in the Thymeleaf chain,
 * which has no login of its own for a token - that is why "who may read it" is a switch rather than a
 * role.
 * <p>
 * The description is of <strong>one</strong> version, {@code spring.mvc.apiversion.default}: the paths
 * carry it literally and a method mapped for another version is left out. See
 * {@link ApiDocumentCustomizer} and {@link ApiOperationCustomizer}.
 * <p>
 * Authentication is the session cookie the login sets ({@code HttpOnly}, so a browser sends it by
 * itself and Swagger UI served from the same origin just works) or the same token as a bearer header.
 */
@Configuration
@PropertySource("classpath:openapi-defaults.properties")
public class OpenApiConfig {

    static final String COOKIE_AUTH = "cookieAuth";
    static final String BEARER_AUTH = "bearerAuth";

    @Bean
    public OpenAPI apiDescription(@Value("${spring.mvc.apiversion.default}") String version,
                                  @Value("${app.cookie.name}") String cookieName) {
        return new OpenAPI()
                .info(new Info()
                        .title("Supply Chain Management API")
                        .version(version)
                        .description("""
                                Orders, fulfilment, packing, shipments and the supplier side.

                                **Log in** with `POST /api/%s/auth/login`; the answer sets the session as an \
                                `HttpOnly` cookie. `GET /api/%s/me` says who is logged in and with which roles. \
                                Every operation that demands roles lists them in `x-roles`.

                                **Errors** answer `ErrorDetails`, or a map of field to message when a bean \
                                validation fails. A 401 anywhere but on the login call means the session is over."""
                                .formatted(version, version)))
                .components(new Components()
                        .addSecuritySchemes(COOKIE_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name(cookieName)
                                .description("The session cookie set by the login."))
                        .addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("The same token as a header, for clients that are not a browser.")))
                // Alternatives, not both at once: one list entry per way of authenticating.
                .addSecurityItem(new SecurityRequirement().addList(COOKIE_AUTH))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }

    @Bean
    public OpenApiCustomizer apiDocumentCustomizer(@Value("${spring.mvc.apiversion.default}") String version) {
        return new ApiDocumentCustomizer(version);
    }

    @Bean
    public OperationCustomizer apiOperationCustomizer(@Value("${spring.mvc.apiversion.default}") String version) {
        return new ApiOperationCustomizer(version);
    }
}
