package com.supplychainmanagement.dto.shipping;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POST /packing takes its items either wrapped - {"items": [ ... ]} - or as the bare array.
 */
class CreatePackageItemsRequestTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private CreatePackageItemsRequest read(String json) {
        return mapper.readValue(json, CreatePackageItemsRequest.class);
    }

    private List<String> violatedPaths(CreatePackageItemsRequest request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .toList();
    }

    @Test
    void readsTheBareArray() {
        CreatePackageItemsRequest request = read("""
                [ { "orderItemId": 11, "qty": 2 }, { "orderItemId": 12, "qty": 5 } ]
                """);

        assertThat(request.items()).containsExactly(new PackItem(11L, 2), new PackItem(12L, 5));
    }

    /** The existing shape keeps working next to the new one. */
    @Test
    void stillReadsTheWrappedForm() {
        CreatePackageItemsRequest request = read("""
                { "items": [ { "orderItemId": 11, "qty": 2 }, { "orderItemId": 12, "qty": 5 } ] }
                """);

        assertThat(request.items()).containsExactly(new PackItem(11L, 2), new PackItem(12L, 5));
    }

    /** Both shapes end in the same record, so both are validated alike. */
    @Test
    void validatesTheBareArrayLikeTheWrappedForm() {
        assertThat(violatedPaths(read("""
                [ { "orderItemId": 11, "qty": 0 } ]
                """))).containsExactly("items[0].qty");

        assertThat(violatedPaths(read("""
                { "items": [ { "orderItemId": 11, "qty": 0 } ] }
                """))).containsExactly("items[0].qty");
    }

    /** An empty array is read, then rejected - it does not slip past @NotEmpty. */
    @Test
    void rejectsAnEmptyBareArray() {
        assertThat(violatedPaths(read("[]"))).containsExactly("items");
    }
}
