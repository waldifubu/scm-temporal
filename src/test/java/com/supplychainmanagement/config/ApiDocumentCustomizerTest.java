package com.supplychainmanagement.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules that turn what springdoc reads off the controllers into something a client can use. The
 * real application is held by {@code OpenApiDocumentTest}; these say <em>which rule</em> broke.
 */
class ApiDocumentCustomizerTest {

    private final ApiDocumentCustomizer customizer = new ApiDocumentCustomizer("1.0");

    /** A GET answering 200 with whatever content type is given - springdoc's own "any". */
    private static Operation get200() {
        Content any = new Content().addMediaType("*/*", new MediaType().schema(new Schema<>().$ref("#/components/schemas/Thing")));
        return new Operation().responses(new ApiResponses().addApiResponse("200", new ApiResponse().description("OK").content(any)));
    }

    private static OpenAPI describing(Object... pathsAndOperations) {
        Paths paths = new Paths();
        for (int i = 0; i < pathsAndOperations.length; i += 2) {
            PathItem item = new PathItem();
            item.setGet((Operation) pathsAndOperations[i + 1]);
            paths.addPathItem((String) pathsAndOperations[i], item);
        }
        return new OpenAPI().paths(paths);
    }

    private OpenAPI customised(Object... pathsAndOperations) {
        OpenAPI openApi = describing(pathsAndOperations);
        customizer.customise(openApi);
        return openApi;
    }

    // ------------------------------------------------------------------ paths

    @Test
    void turnsThePlaceholderIntoTheVersion() {
        OpenAPI openApi = customised("/api/{version}/orders/{orderNo}", get200());

        assertThat(openApi.getPaths()).containsOnlyKeys("/api/1.0/orders/{orderNo}");
    }

    /** The placeholder is replaced, not every brace: {orderNo} stays a path variable. */
    @Test
    void leavesTheOtherPathVariablesAlone() {
        OpenAPI openApi = customised("/api/{version}/shipments/{shipmentId}/packages/{packageId}", get200());

        assertThat(openApi.getPaths()).containsOnlyKeys("/api/1.0/shipments/{shipmentId}/packages/{packageId}");
    }

    @Test
    void dropsTheUnversionedAliasWhenTheVersionedTwinIsDescribed() {
        OpenAPI openApi = customised("/api/auth/login", get200(), "/api/{version}/auth/login", get200());

        assertThat(openApi.getPaths()).containsOnlyKeys("/api/1.0/auth/login");
    }

    /** An unversioned path without a twin is a real endpoint, not a duplicate - it is not thrown away. */
    @Test
    void keepsAnUnversionedPathThatHasNoTwin() {
        OpenAPI openApi = customised("/api/only-here", get200(), "/api/{version}/orders", get200());

        assertThat(openApi.getPaths()).containsOnlyKeys("/api/only-here", "/api/1.0/orders");
    }

    @Test
    void sortsThePaths() {
        OpenAPI openApi = customised("/api/{version}/users", get200(), "/api/{version}/components", get200(),
                "/api/{version}/orders", get200());

        assertThat(openApi.getPaths().keySet()).containsExactly(
                "/api/1.0/components", "/api/1.0/orders", "/api/1.0/users");
    }

    @Test
    void takesTheVersionItIsGiven() {
        OpenAPI openApi = new OpenAPI().paths(describing("/api/{version}/orders", get200()).getPaths());

        new ApiDocumentCustomizer("2.0").customise(openApi);

        assertThat(openApi.getPaths()).containsOnlyKeys("/api/2.0/orders");
    }

    @Test
    void takesTheVersionParameterOutOfTheOperation() {
        Operation operation = get200().addParametersItem(new Parameter().name("version").in("path"))
                .addParametersItem(new Parameter().name("orderNo").in("path"))
                .addParametersItem(new Parameter().name("version").in("query"));

        customised("/api/{version}/orders/{orderNo}", operation);

        // The path parameter is gone; a query parameter that merely shares the name is somebody's own.
        assertThat(operation.getParameters()).extracting(Parameter::getIn).containsExactly("path", "query");
        assertThat(operation.getParameters()).extracting(Parameter::getName).containsExactly("orderNo", "version");
    }

    @Test
    void copesWithADescriptionWithoutPaths() {
        OpenAPI openApi = new OpenAPI();

        customizer.customise(openApi);

        assertThat(openApi.getPaths()).isNull();
        assertThat(openApi.getComponents().getSchemas()).containsKeys("ErrorDetails", "ValidationErrors");
    }

    // ------------------------------------------------------------------ security

    @Test
    void marksTheAuthCallsPublicWithAnEmptyRequirement() {
        Operation login = get200();
        Operation orders = get200();

        customised("/api/{version}/auth/login", login, "/api/{version}/orders", orders);

        // Empty, not absent: absent inherits the global requirement, which is the one a login lacks.
        assertThat(login.getSecurity()).isNotNull().isEmpty();
        assertThat(orders.getSecurity()).isNull();
    }

    @Test
    void doesNotTreatAnotherPathWithAuthInItsNameAsPublic() {
        Operation authors = get200();

        customised("/api/{version}/authors", authors, "/api/{version}/orders/auth/check", get200());

        assertThat(authors.getSecurity()).isNull();
    }

    // ------------------------------------------------------------------ the answers

    @Test
    void declaresNotLoggedInOnSecuredOperationsOnly() {
        Operation secured = get200();
        Operation login = get200();

        customised("/api/{version}/orders", secured, "/api/{version}/auth/login", login);

        assertThat(secured.getResponses()).containsKey("401");
        assertThat(login.getResponses()).doesNotContainKey("401");
    }

    /** 403 is "you hold none of the roles", so it is only there where roles are demanded. */
    @Test
    void declaresForbiddenOnlyWhereRolesAreDemanded() {
        Operation withRoles = get200();
        withRoles.addExtension(ApiOperationCustomizer.ROLES_EXTENSION, List.of("ADMIN"));
        Operation anyone = get200();

        customised("/api/{version}/storehouses", withRoles, "/api/{version}/me", anyone);

        assertThat(withRoles.getResponses()).containsKey("403");
        assertThat(anyone.getResponses()).doesNotContainKey("403");
    }

    @Test
    void declaresTheRateLimitAndTheDefaultErrorOnEveryOperation() {
        Operation secured = get200();
        Operation login = get200();

        customised("/api/{version}/orders", secured, "/api/{version}/auth/login", login);

        assertThat(secured.getResponses()).containsKeys("429", "default");
        assertThat(login.getResponses()).containsKeys("429", "default");
    }

    @Test
    void doesNotOverwriteAnAnswerTheControllerDeclaredItself() {
        Operation operation = get200();
        operation.getResponses().addApiResponse("429", new ApiResponse().description("my own"));
        operation.getResponses().addApiResponse("default", new ApiResponse().description("my own default"));

        customised("/api/{version}/orders", operation);

        assertThat(operation.getResponses().get("429").getDescription()).isEqualTo("my own");
        assertThat(operation.getResponses().get("default").getDescription()).isEqualTo("my own default");
    }

    @Test
    void copesWithAnOperationWithoutResponses() {
        Operation bare = new Operation();

        customised("/api/{version}/orders", bare);

        assertThat(bare.getResponses()).containsKeys("401", "429", "default");
    }

    /** The default is the error body of the API: ErrorDetails, or the field map of a failed validation. */
    @Test
    void describesTheDefaultAnswerAsOneOfTheTwoErrorShapes() {
        Operation operation = get200();

        OpenAPI openApi = customised("/api/{version}/orders", operation);

        Schema<?> schema = operation.getResponses().get("default").getContent().get("application/json").getSchema();
        assertThat(schema.getOneOf()).extracting(Schema::get$ref)
                .containsExactly("#/components/schemas/ErrorDetails", "#/components/schemas/ValidationErrors");
        assertThat(openApi.getComponents().getSchemas()).containsKeys("ErrorDetails", "ValidationErrors");
    }

    @Test
    void readsTheErrorDetailsSchemaFromTheRecord() {
        OpenAPI openApi = customised("/api/{version}/orders", get200());

        assertThat(openApi.getComponents().getSchemas().get("ErrorDetails").getProperties())
                .containsOnlyKeys("timestamp", "message", "path", "errorCode");
    }

    // ------------------------------------------------------------------ content types

    @Test
    void declaresJsonWhereSpringdocWroteAnyContentType() {
        Operation operation = get200();

        customised("/api/{version}/orders", operation);

        Content content = operation.getResponses().get("200").getContent();
        assertThat(content).containsOnlyKeys("application/json");
        assertThat(content.get("application/json").getSchema().get$ref()).isEqualTo("#/components/schemas/Thing");
    }

    @Test
    void leavesAContentTypeTheControllerNamedAlone() {
        Content csv = new Content().addMediaType("text/csv", new MediaType());
        Operation operation = new Operation().responses(new ApiResponses().addApiResponse("200", new ApiResponse().content(csv)));

        customised("/api/{version}/export", operation);

        assertThat(operation.getResponses().get("200").getContent()).containsOnlyKeys("text/csv");
    }

    @Test
    void copesWithAnAnswerWithoutContent() {
        Operation operation = new Operation().responses(new ApiResponses().addApiResponse("204", new ApiResponse().description("none")));

        customised("/api/{version}/orders/{orderNo}", operation);

        assertThat(operation.getResponses().get("204").getContent()).isNull();
    }
}
