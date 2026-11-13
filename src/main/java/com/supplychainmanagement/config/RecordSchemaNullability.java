package com.supplychainmanagement.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.swagger.v3.core.jackson.TypeNameResolver;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.RecordComponent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Says in the description what a client may rely on: which keys are always there ({@code required}) and
 * which values can be {@code null}. springdoc cannot know either from a record - it lists every
 * property as optional and never as nullable - and a generator then turns each field into
 * {@code field?: T}, which is wrong in both directions.
 * <p>
 * Two halves in one class. As a {@link ModelConverter} it only remembers which record stands behind which
 * schema name (the customizer sees names, not classes); as an {@link OpenApiCustomizer} it then applies
 * the rules, because only there it is known <em>in which direction</em> a schema travels.
 * <p>
 * <strong>An answer</strong> (a schema no request body refers to):
 * <ul>
 *   <li>a component under {@code @JsonInclude(NON_NULL | NON_EMPTY | NON_ABSENT)} - on the component or on
 *       the record - is left out of the JSON while empty: <em>not</em> required, and never null;</li>
 *   <li>every other component is always written, so it is {@code required}; its value can be
 *       {@code null} unless it is a primitive or carries {@code @NotNull}, {@code @NonNull},
 *       {@code @NotBlank} or {@code @NotEmpty}; {@code @Nullable} says so outright.</li>
 * </ul>
 * That default is deliberately the honest one: a boxed {@code Long id} <em>can</em> be null as far as the
 * type says, so it is described as nullable until somebody states otherwise. Stating it is the way to a
 * tighter description, and nothing here guesses.
 * <p>
 * <strong>A request</strong> (a schema a request body refers to, with everything below it): a client has
 * to send only what validation demands, so a component is {@code required} only if it is a primitive or
 * carries one of the four non-null annotations, and nothing is marked nullable except by
 * {@code @Nullable}. Making every unannotated field of a request required would refuse requests the
 * server accepts.
 * <p>
 * <strong>{@code @NullMarked}</strong> (JSpecify, on the record, a class around it or its package) turns the
 * answer default round: a value is then non-null unless it is {@code @Nullable}. A request is not affected.
 * <p>
 * Only records are looked at - the DTOs are records, and an entity (which should not be in a description
 * at all) is left as springdoc wrote it.
 */
public class RecordSchemaNullability implements ModelConverter, OpenApiCustomizer {

    private static final Set<String> NON_NULL = Set.of("NotNull", "NonNull", "NotBlank", "NotEmpty");
    private static final Set<String> SKIPPED_WHEN_EMPTY = Set.of("NON_NULL", "NON_EMPTY", "NON_ABSENT");
    private static final String SCHEMA_PREFIX = "#/components/schemas/";

    /** Schema name to the record it was read from; filled while springdoc builds the description. */
    private final Map<String, Class<?>> records = new ConcurrentHashMap<>();

    // ------------------------------------------------------------ the converter half

    @Override
    public Schema<?> resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        if (!chain.hasNext()) {
            return null;
        }
        Schema<?> schema = chain.next().resolve(type, context, chain);
        if (schema != null && type.getType() != null) {
            JavaType java = TypeFactory.defaultInstance().constructType(type.getType());
            if (java.getRawClass().isRecord()) {
                // The name springdoc gives the definition: simple name, type arguments appended.
                records.put(TypeNameResolver.std.nameForType(java), java.getRawClass());
            }
        }
        return schema;
    }

    // ---------------------------------------------------------- the customizer half

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            return;
        }
        Map<String, Schema> schemas = openApi.getComponents().getSchemas();
        Set<String> requests = requestSchemas(openApi, schemas);

        schemas.forEach((name, schema) -> {
            Class<?> record = records.get(name);
            if (record != null) {
                refine(schema, record, requests.contains(name));
            }
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void refine(Schema schema, Class<?> record, boolean request) {
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null) {
            return;
        }
        boolean skippedWhenEmptyOnRecord = skipsEmpty(record);
        boolean nullMarked = isNullMarked(record);
        Set<String> required = new LinkedHashSet<>();
        if (schema.getRequired() != null) {
            required.addAll(schema.getRequired());
        }

        for (RecordComponent component : record.getRecordComponents()) {
            String key = jsonName(component);
            Schema property = properties.get(key);
            if (property == null) {
                continue;
            }
            boolean primitive = component.getType().isPrimitive();
            // An id is the identity of what the record describes: whatever has one has it.
            boolean nullable = hasAny(component, Set.of("Nullable"));
            boolean nonNull = primitive || "id".equals(component.getName()) || hasAny(component, NON_NULL);

            if (request) {
                if (nonNull) {
                    required.add(key);
                }
                if (nullable) {
                    properties.put(key, orNull(property));
                }
                continue;
            }

            boolean leftOutWhenEmpty = skippedWhenEmptyOnRecord || skipsEmpty(component);
            if (leftOutWhenEmpty && !primitive) {
                required.remove(key);
                continue;
            }
            required.add(key);
            // Under @NullMarked a value is non-null unless @Nullable says otherwise (an answer only: a request
            // field that is merely un-annotated is optional, not forbidden to be null).
            if (!nonNull && !(nullMarked && !nullable)) {
                properties.put(key, orNull(property));
            }
        }
        if (!required.isEmpty()) {
            schema.setRequired(new ArrayList<>(required));
        }
    }

    /** The same schema, or {@code null} as well - a reference cannot take a type, so it is wrapped. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema orNull(Schema property) {
        if (property.getTypes() != null && property.getTypes().contains("null")) {
            return property;
        }
        if (property.get$ref() != null || property.getTypes() == null || property.getTypes().isEmpty()) {
            Schema wrapped = new Schema();
            wrapped.addOneOfItem(property);
            Schema nothing = new Schema();
            nothing.addType("null");
            wrapped.addOneOfItem(nothing);
            return wrapped;
        }
        property.addType("null");
        return property;
    }

    // ------------------------------------------------------------------ direction

    /** Every schema a request body names, and everything those refer to in turn. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Set<String> requestSchemas(OpenAPI openApi, Map<String, Schema> schemas) {
        Deque<String> open = new ArrayDeque<>();
        if (openApi.getPaths() != null) {
            openApi.getPaths().values().forEach(item -> item.readOperations().forEach(operation -> {
                if (operation.getRequestBody() != null && operation.getRequestBody().getContent() != null) {
                    Content content = operation.getRequestBody().getContent();
                    content.values().forEach(media -> collectRefs(media.getSchema(), open));
                }
            }));
        }
        Set<String> seen = new HashSet<>();
        while (!open.isEmpty()) {
            String name = open.pop();
            if (seen.add(name) && schemas.get(name) != null) {
                collectRefs(schemas.get(name), open);
            }
        }
        return seen;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void collectRefs(Schema schema, Deque<String> into) {
        if (schema == null) {
            return;
        }
        if (schema.get$ref() != null && schema.get$ref().startsWith(SCHEMA_PREFIX)) {
            into.push(schema.get$ref().substring(SCHEMA_PREFIX.length()));
        }
        if (schema.getProperties() != null) {
            schema.getProperties().values().forEach(p -> collectRefs((Schema) p, into));
        }
        collectRefs(schema.getItems(), into);
        if (schema.getAdditionalProperties() instanceof Schema additional) {
            collectRefs(additional, into);
        }
        for (List<Schema> group : java.util.Arrays.asList(schema.getAllOf(), schema.getOneOf(), schema.getAnyOf())) {
            if (group != null) {
                group.forEach(s -> collectRefs(s, into));
            }
        }
    }

    // --------------------------------------------------------------------- reading

    private static String jsonName(RecordComponent component) {
        JsonProperty named = component.getAnnotation(JsonProperty.class);
        if (named == null) {
            named = accessorOrField(component, JsonProperty.class);
        }
        return named != null && !named.value().isEmpty() ? named.value() : component.getName();
    }

    /** Whether the record, a class around it or its package carries JSpecify's {@code @NullMarked} (by simple name). */
    private static boolean isNullMarked(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getEnclosingClass()) {
            if (named(c, "NullMarked")) {
                return true;
            }
        }
        return type.getPackage() != null && named(type.getPackage(), "NullMarked");
    }

    private static boolean named(AnnotatedElement element, String simpleName) {
        return java.util.Arrays.stream(element.getAnnotations())
                .anyMatch(a -> a.annotationType().getSimpleName().equals(simpleName));
    }

    private static boolean skipsEmpty(AnnotatedElement element) {
        JsonInclude include = element.getAnnotation(JsonInclude.class);
        if (include == null && element instanceof RecordComponent component) {
            include = accessorOrField(component, JsonInclude.class);
        }
        return include != null && (SKIPPED_WHEN_EMPTY.contains(include.value().name())
                || SKIPPED_WHEN_EMPTY.contains(include.content().name()));
    }

    private static <A extends Annotation> A accessorOrField(RecordComponent component, Class<A> type) {
        A found = component.getAccessor().getAnnotation(type);
        if (found != null) {
            return found;
        }
        try {
            return component.getDeclaringRecord().getDeclaredField(component.getName()).getAnnotation(type);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    /**
     * Whether the component carries an annotation of one of these simple names - on the component, its
     * accessor, its field, or its type (a type-use annotation such as JSpecify's {@code @Nullable}). By
     * simple name, so the Jakarta, Spring and JSpecify variants all count.
     */
    private static boolean hasAny(RecordComponent component, Set<String> names) {
        List<Annotation> all = new ArrayList<>(List.of(component.getAnnotations()));
        all.addAll(List.of(component.getAccessor().getAnnotations()));
        all.addAll(List.of(component.getAnnotatedType().getAnnotations()));
        try {
            all.addAll(List.of(component.getDeclaringRecord().getDeclaredField(component.getName()).getAnnotations()));
        } catch (NoSuchFieldException ignored) {
            // a record always has the field; nothing to add if the lookup somehow fails
        }
        return all.stream().anyMatch(a -> names.contains(a.annotationType().getSimpleName()));
    }
}
