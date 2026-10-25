package com.supplychainmanagement.config;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenAPI description as a client receives it, from the real application: every controller, the
 * real filter chain, springdoc and the two customizers together. The customizers have unit tests of
 * their own; what only this one can say is that springdoc really calls them and in what shape their
 * output ends up.
 * <p>
 * Switched on here by property - the application's default is off, which {@code OpenApiDisabledTest}
 * holds.
 */
@SpringBootTest(properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiDocumentTest {

    private static final AtomicLong CALLS = new AtomicLong();
    private static String body;
    private static int status;

    @Autowired
    private MockMvc mockMvc;
    @Value("${app.cookie.name}")
    private String cookieName;

    /** Fetched once: it describes the whole API and is the same for every test. */
    private String document() throws Exception {
        if (body == null) {
            MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.get("/v3/api-docs")
                    .header("X-API-KEY", "openapi-" + CALLS.incrementAndGet())).andReturn().getResponse();
            status = response.getStatus();
            body = response.getContentAsString();
        }
        return body;
    }

    @Test
    void answersTheDescription() throws Exception {
        String document = document();

        assertThat(status).as("answered: %.300s", document).isEqualTo(200);
        assertThat((String) JsonPath.read(document, "$.openapi")).startsWith("3.");
        assertThat((String) JsonPath.read(document, "$.info.version")).isEqualTo("1.0");
    }

    /** Every path is versioned and literal: no placeholder nobody declares, no unversioned twin. */
    @Test
    void describesOneVersionWithLiteralPaths() throws Exception {
        Map<String, Object> paths = JsonPath.read(document(), "$.paths");

        assertThat(paths).isNotEmpty();
        assertThat(paths.keySet()).allSatisfy(path -> assertThat(path).startsWith("/api/1.0/"));
        assertThat(paths.keySet()).noneMatch(path -> path.contains("{version}"));
        assertThat(paths).doesNotContainKeys("/api/auth/login", "/api/auth/register", "/api/auth/logout");
        assertThat(paths).containsKeys("/api/1.0/auth/login", "/api/1.0/me", "/api/1.0/storehouses");
    }

    /**
     * The 1.0 and 2.0 login share a path; they used to merge into one operation of mixed halves. Only
     * 1.0 is described, and it is the real one: it takes a body and answers a token, where the 2.0
     * stub answers a plain string.
     */
    @Test
    void describesTheLoginOfTheDocumentedVersionOnly() throws Exception {
        Map<String, Object> post = JsonPath.read(document(), "$.paths['/api/1.0/auth/login'].post");

        assertThat(post.get("operationId")).isEqualTo("login");
        assertThat(post).doesNotContainKey("x-roles");
        Map<String, Object> operations = JsonPath.read(document(), "$.paths['/api/1.0/auth/login']");
        assertThat(operations.keySet()).containsExactly("post");
    }

    /** Who may call it - the whole authorisation of the API, which a client builds its menu from. */
    @Test
    void recordsTheRolesOfAnOperation() throws Exception {
        List<String> roles = JsonPath.read(document(), "$.paths['/api/1.0/storehouses'].get['x-roles']");
        String description = JsonPath.read(document(), "$.paths['/api/1.0/storehouses'].get.description");

        assertThat(roles).containsExactlyInAnyOrder("ADMIN", "WAREHOUSE");
        assertThat(description).contains("ADMIN").contains("WAREHOUSE");
    }

    /** "Any logged-in user" is the absence of a role list, not an empty one. */
    @Test
    void leavesTheRolesOutWhereAnyLoggedInUserMayCall() throws Exception {
        Map<String, Object> me = JsonPath.read(document(), "$.paths['/api/1.0/me'].get");

        assertThat(me).doesNotContainKey("x-roles");
        assertThat((Map<String, Object>) JsonPath.read(document(), "$.paths['/api/1.0/me'].get.responses"))
                .containsKey("401").doesNotContainKey("403");
    }

    @Test
    void declaresTheErrorAnswers() throws Exception {
        Map<String, Object> responses = JsonPath.read(document(), "$.paths['/api/1.0/storehouses'].get.responses");

        assertThat(responses).containsKeys("200", "401", "403", "429", "default");
        List<String> oneOf = JsonPath.read(document(), "$.paths['/api/1.0/storehouses'].get.responses.default"
                + ".content['application/json'].schema.oneOf[*].$ref");
        assertThat(oneOf).containsExactly("#/components/schemas/ErrorDetails", "#/components/schemas/ValidationErrors");
        // Read from the record, so it cannot drift from what the handler writes.
        Map<String, Object> properties = JsonPath.read(document(), "$.components.schemas.ErrorDetails.properties");
        assertThat(properties).containsKeys("timestamp", "message", "path", "errorCode");
        assertThat((String) JsonPath.read(document(), "$.components.schemas.ValidationErrors.type")).isEqualTo("object");
    }

    /** springdoc writes *&#47;* where a controller declares no content type; a generator needs JSON. */
    @Test
    void declaresJsonInsteadOfAnyContentType() throws Exception {
        assertThat(document()).doesNotContain("\"*/*\"");
        Map<String, Object> content = JsonPath.read(document(), "$.paths['/api/1.0/storehouses'].get.responses['200'].content");

        assertThat(content).containsKey("application/json");
    }

    /** Register, login and logout create the session - the global security requirement must not apply. */
    @Test
    void marksTheAuthCallsPublicAndEverythingElseSecured() throws Exception {
        List<Object> loginSecurity = JsonPath.read(document(), "$.paths['/api/1.0/auth/login'].post.security");
        Map<String, Object> storehouses = JsonPath.read(document(), "$.paths['/api/1.0/storehouses'].get");

        assertThat(loginSecurity).isEmpty();
        assertThat(storehouses).doesNotContainKey("security");
        List<Object> global = JsonPath.read(document(), "$.security");
        assertThat(global).hasSize(2);
    }

    @Test
    void describesBothWaysToAuthenticate() throws Exception {
        Map<String, Object> cookie = JsonPath.read(document(), "$.components.securitySchemes.cookieAuth");
        Map<String, Object> bearer = JsonPath.read(document(), "$.components.securitySchemes.bearerAuth");

        assertThat(cookie).containsEntry("type", "apiKey").containsEntry("in", "cookie").containsEntry("name", cookieName);
        assertThat(bearer).containsEntry("type", "http").containsEntry("scheme", "bearer");
    }

    /**
     * The audit the description makes possible: every operation a login is needed for says who may
     * call it, because every one of them carries a {@code @PreAuthorize} naming roles. One that does not
     * is open to <em>any</em> logged-in user, and that should be a decision rather than something
     * nobody noticed.
     * <p>
     * One is known, and the list is exact so a new one turns this red. {@code GET /me} is meant for any
     * logged-in user. A second used to be there: {@code GET /components/{id}} had no annotation and no
     * version, and could not be reached (the versioned {@code /{sku}} next to it won every request and
     * answered 400 for a numeric id - measured with a customer under 1.0, 1.1, 2.0 and 9.9). It was
     * commented out on 2026-10-07 and left this list, as this comment said it would.
     */
    @Test
    void everyOperationBehindALoginNamesItsRolesExceptTheKnownOnes() throws Exception {
        Map<String, Map<String, Map<String, Object>>> paths = JsonPath.read(document(), "$.paths");
        List<String> unguarded = new java.util.ArrayList<>();
        paths.forEach((path, item) -> item.forEach((method, operation) -> {
            boolean isPublic = operation.get("security") instanceof List<?> security && security.isEmpty();
            if (!isPublic && !operation.containsKey("x-roles")) {
                unguarded.add(method.toUpperCase() + " " + path);
            }
        }));

        assertThat(unguarded).containsExactly("GET /api/1.0/me");
    }

    @Test
    void givesEveryOperationAnIdOfItsOwn() throws Exception {
        List<String> ids = JsonPath.read(document(), "$.paths.*.*.operationId");

        assertThat(ids).isNotEmpty().doesNotHaveDuplicates();
    }

    /** The page to try it from, switched on with the description. */
    @Test
    void servesSwaggerUiWhenSwitchedOn() throws Exception {
        int index = mockMvc.perform(MockMvcRequestBuilders.get("/swagger-ui/index.html")
                .header("X-API-KEY", "openapi-ui-" + CALLS.incrementAndGet())).andReturn().getResponse().getStatus();

        assertThat(index).isEqualTo(200);
    }

}
