package com.supplychainmanagement.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which keys a client may rely on and which values can be null, for an answer and for a request. Run through
 * swagger's own converters, so the part that remembers the record behind a schema name is held as well.
 */
class RecordSchemaNullabilityTest {

    record Answer(
            long count,
            Long id,
            @NotNull Long other,
            String comment,
            @JsonInclude(JsonInclude.Include.NON_NULL) String note,
            Inner inner,
            List<String> tags) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Sparse(String left, String right) {}

    @NullMarked
    record Marked(String name, @Nullable String comment, Inner inner, @Nullable Inner maybe) {}

    record Inner(@NotBlank String name) {}

    record Body(@NotBlank String reason, String optional, int number) {}

    private static OpenAPI described(Class<?> answer, Class<?> request) {
        RecordSchemaNullability nullability = new RecordSchemaNullability();
        ModelConverters converters = new ModelConverters();
        converters.addConverter(nullability);

        Map<String, Schema> schemas = new java.util.LinkedHashMap<>();
        schemas.putAll(converters.readAll(answer));
        schemas.putAll(converters.readAll(request));

        Content body = new Content().addMediaType("application/json",
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + request.getSimpleName())));
        PathItem item = new PathItem().post(new Operation().requestBody(new RequestBody().content(body)));
        OpenAPI openApi = new OpenAPI()
                .paths(new Paths().addPathItem("/x", item))
                .components(new Components().schemas(schemas));
        nullability.customise(openApi);
        return openApi;
    }

    private static Schema<?> schema(OpenAPI openApi, Class<?> type) {
        return openApi.getComponents().getSchemas().get(type.getSimpleName());
    }

    private static boolean nullable(Schema<?> property) {
        return (property.getTypes() != null && property.getTypes().contains("null"))
                || (property.getOneOf() != null && property.getOneOf().stream()
                        .anyMatch(s -> s.getTypes() != null && s.getTypes().contains("null")));
    }

    @Test
    void anAnswerWritesEveryKeyAndSaysWhichValuesCanBeNull() {
        Schema<?> answer = schema(described(Answer.class, Body.class), Answer.class);

        assertThat(answer.getRequired()).contains("count", "id", "comment", "inner", "tags");
        assertThat(nullable(answer.getProperties().get("comment"))).isTrue();
        assertThat(nullable(answer.getProperties().get("inner"))).isTrue();
        assertThat(nullable(answer.getProperties().get("tags"))).isTrue();
    }

    @Test
    void aPrimitiveOrAnnotatedComponentIsNeverNull() {
        Schema<?> answer = schema(described(Answer.class, Body.class), Answer.class);

        assertThat(nullable(answer.getProperties().get("count"))).isFalse();
        assertThat(nullable(answer.getProperties().get("id"))).isFalse();
        assertThat(nullable(answer.getProperties().get("other"))).isFalse();
    }

    /** An id names what the record describes; it is never null whatever its boxed type says. */
    @Test
    void anIdIsNeverNullWithoutAnyAnnotation() {
        Schema<?> answer = schema(described(Answer.class, Body.class), Answer.class);

        assertThat(answer.getRequired()).contains("id");
        assertThat(nullable(answer.getProperties().get("id"))).isFalse();
        assertThat(nullable(answer.getProperties().get("comment"))).isTrue();
    }

    @Test
    void aComponentLeftOutWhileEmptyIsNeitherRequiredNorNullable() {
        Schema<?> answer = schema(described(Answer.class, Body.class), Answer.class);

        assertThat(answer.getRequired()).doesNotContain("note");
        assertThat(nullable(answer.getProperties().get("note"))).isFalse();
    }

    @Test
    void aRecordLeftOutWhileEmptyAsAWholeIsOptionalThroughout() {
        OpenAPI openApi = described(Sparse.class, Body.class);

        assertThat(schema(openApi, Sparse.class).getRequired()).isNullOrEmpty();
    }

    @Test
    void aRequestDemandsOnlyWhatValidationDemands() {
        Schema<?> body = schema(described(Answer.class, Body.class), Body.class);

        assertThat(body.getRequired()).containsExactlyInAnyOrder("reason", "number");
        assertThat(nullable(body.getProperties().get("optional"))).isFalse();
    }

    /** What a request body refers to is a request all the way down. */
    @Test
    void aSchemaBelowARequestIsARequestToo() {
        record Outer(Inner inner, String other) {}
        OpenAPI openApi = described(Answer.class, Outer.class);

        // Inner is reached through Outer and also used by Answer; the request reading wins, so its
        // @NotBlank still makes the key required and nothing is turned into a nullable.
        assertThat(schema(openApi, Inner.class).getRequired()).containsExactly("name");
        assertThat(schema(openApi, Outer.class).getRequired()).isNullOrEmpty();
    }

    @Test
    void underNullMarkedAValueIsNonNullUnlessItIsNullable() {
        Schema<?> marked = schema(described(Marked.class, Body.class), Marked.class);

        assertThat(marked.getRequired()).contains("name", "comment", "inner", "maybe");
        assertThat(nullable(marked.getProperties().get("name"))).isFalse();
        assertThat(nullable(marked.getProperties().get("inner"))).isFalse();
        assertThat(nullable(marked.getProperties().get("comment"))).isTrue();
        assertThat(nullable(marked.getProperties().get("maybe"))).isTrue();
    }
}
