package com.supplychainmanagement.config;

import com.supplychainmanagement.exception.ErrorDetails;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;

import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Brings the generated description in line with what a client actually meets. springdoc reads the
 * controllers faithfully, and faithfully reproduces three things a client cannot use:
 * <ul>
 *   <li><strong>{@code {version}} as a path variable nobody declares.</strong> Every controller is
 *       mapped under {@code /api/{version}/...}; the description then has a placeholder with no
 *       parameter behind it, which a generator rejects or turns into a pointless argument. The
 *       description is of <em>one</em> version, so the placeholder becomes that version
 *       ({@code /api/1.0/...}).</li>
 *   <li><strong>The unversioned alias.</strong> {@code /api/auth/*} exists next to
 *       {@code /api/{version}/auth/*} (the filter rewrites any unversioned call to the default
 *       version) and would appear as a second, identical operation.</li>
 *   <li><strong>Only success answers, all of them {@code *}{@code /*}.</strong> Nothing in a controller says
 *       it can answer 401, 403, 429 or the error body, and without a declared content type a generator
 *       cannot tell JSON from anything else.</li>
 * </ul>
 * <p>
 * The error answers are the same for the whole API, so they are added here once instead of being
 * written on every method: 401 and 403 (the latter only where roles are demanded, which
 * {@link ApiOperationCustomizer} records as {@code x-roles}), 429 for the rate limit, and a
 * {@code default} that is {@link ErrorDetails} - or, for a failed bean validation, a map of field to
 * message. 401 and 403 declare no body: the entry point answers some of them with {@code sendError},
 * which has none, and a description that promised one would be wrong now and then.
 * <p>
 * Everything under {@code /auth} is public (register, login, logout) and says so with an empty
 * security requirement - without it the global one would apply to the very calls that create the
 * session.
 */
public class ApiDocumentCustomizer implements OpenApiCustomizer {

    static final String ERROR_DETAILS = "ErrorDetails";
    static final String VALIDATION_ERRORS = "ValidationErrors";

    private static final String VERSION_PLACEHOLDER = "{version}";
    private static final String JSON = "application/json";
    private static final String ANY = "*/*";
    private static final Pattern PUBLIC_PATH = Pattern.compile("^/api/[^/]+/auth/.*$");

    private final String version;

    public ApiDocumentCustomizer(String version) {
        this.version = version;
    }

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        addErrorSchemas(openApi.getComponents());

        Paths original = openApi.getPaths();
        if (original == null) {
            return;
        }
        // Sorted: the description is read by people and diffed by tools, and the order controllers
        // happen to be scanned in is neither alphabetical nor stable.
        Map<String, PathItem> sorted = new TreeMap<>();
        for (Map.Entry<String, PathItem> entry : original.entrySet()) {
            String path = entry.getKey();
            if (isUnversionedAlias(path, original)) {
                continue;
            }
            sorted.put(path.replace(VERSION_PLACEHOLDER, version), entry.getValue());
        }
        Paths paths = new Paths();
        paths.putAll(sorted);
        openApi.setPaths(paths);

        paths.forEach((path, item) -> item.readOperations().forEach(operation -> customise(path, operation)));
    }

    /** {@code /api/auth/login} when {@code /api/{version}/auth/login} is described as well. */
    private static boolean isUnversionedAlias(String path, Paths all) {
        if (!path.startsWith("/api/") || path.startsWith("/api/" + VERSION_PLACEHOLDER + "/")) {
            return false;
        }
        return all.containsKey("/api/" + VERSION_PLACEHOLDER + "/" + path.substring("/api/".length()));
    }

    private void customise(String path, Operation operation) {
        boolean isPublic = PUBLIC_PATH.matcher(path).matches();
        if (isPublic) {
            operation.setSecurity(new ArrayList<>());
        }

        // The version is part of the path now; it must not come back as a parameter as well.
        if (operation.getParameters() != null) {
            operation.getParameters().removeIf(p -> "version".equals(p.getName()) && "path".equals(p.getIn()));
        }

        ApiResponses responses = operation.getResponses();
        if (responses == null) {
            responses = new ApiResponses();
            operation.setResponses(responses);
        }
        responses.values().forEach(ApiDocumentCustomizer::declareJson);

        if (!isPublic) {
            responses.putIfAbsent("401", new ApiResponse()
                    .description("Not logged in, or the session has expired - log in again."));
            if (operation.getExtensions() != null && operation.getExtensions().containsKey("x-roles")) {
                responses.putIfAbsent("403", new ApiResponse()
                        .description("The logged-in user holds none of the roles in x-roles, "
                                + "or may not touch this particular resource."));
            }
        }
        responses.putIfAbsent("429", new ApiResponse()
                .description("Rate limit exceeded; Retry-After says for how many seconds."));
        responses.putIfAbsent("default", errorResponse());
    }

    /** What has no content type is JSON: the controllers declare none, so springdoc writes any. */
    private static void declareJson(ApiResponse response) {
        Content content = response.getContent();
        if (content == null || !content.containsKey(ANY)) {
            return;
        }
        Content replaced = new Content();
        content.forEach((type, media) -> replaced.addMediaType(ANY.equals(type) ? JSON : type, media));
        response.setContent(replaced);
    }

    private static ApiResponse errorResponse() {
        Schema<Object> body = new Schema<>();
        body.addOneOfItem(new Schema<>().$ref("#/components/schemas/" + ERROR_DETAILS));
        body.addOneOfItem(new Schema<>().$ref("#/components/schemas/" + VALIDATION_ERRORS));
        return new ApiResponse()
                .description("Any other error. A failed bean validation (400) answers a map of field to "
                        + "message; everything else (400, 404, 409, 500, ...) answers ErrorDetails.")
                .content(new Content().addMediaType(JSON, new MediaType().schema(body)));
    }

    /**
     * {@link ErrorDetails} is read from the record itself, so the description cannot drift from it;
     * the validation answer is not a class at all - the handler writes a plain map - and is described
     * as what it is.
     */
    private static void addErrorSchemas(Components components) {
        ModelConverters.getInstance(true).read(ErrorDetails.class).forEach(components::addSchemas);

        Schema<Object> message = new Schema<>();
        message.addType("string");
        Schema<Object> validation = new Schema<>();
        validation.addType("object");
        validation.setAdditionalProperties(message);
        validation.setDescription("Field name to the message of its violated constraint.");
        components.addSchemas(VALIDATION_ERRORS, validation);
    }
}
