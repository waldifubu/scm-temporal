package com.supplychainmanagement.dto.shipping;

import com.supplychainmanagement.model.enums.PackageType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a package type has to reach the API. The annotation driving this sits on {@link PackageType}
 * itself rather than on a property - putting it on {@code ShipmentPackage} has no effect, because
 * that entity is never deserialized: this record is the only thing bound from a request body.
 * <p>
 * Uses a plain mapper on purpose. The project configures none, so this is the same behaviour the
 * running application has.
 */
class CreatePackageRequestTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private PackageType typeOf(String rawType) {
        String json = """
                {
                  "items": [ { "orderItemId": 11, "qty": 2 } ],
                  "packageType": "%s",
                  "weight": 18.5
                }
                """.formatted(rawType);

        return mapper.readValue(json, CreatePackageRequest.class).packageType();
    }

    @Test
    void readsTheConstantName() {
        assertThat(typeOf("METAL_CONTAINER")).isEqualTo(PackageType.METAL_CONTAINER);
    }

    @Test
    void readsItRegardlessOfCase() {
        assertThat(typeOf("metal_container")).isEqualTo(PackageType.METAL_CONTAINER);
        assertThat(typeOf("Metal_Container")).isEqualTo(PackageType.METAL_CONTAINER);
    }

    /**
     * The flip side of READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE: a typo does not fail the
     * request any more, it silently becomes OTHER. Worth knowing - it also means the enum listing
     * that GlobalExceptionHandler builds for an InvalidFormatException never fires for this type.
     */
    @Test
    void fallsBackToOtherForAnUnknownValue() {
        assertThat(typeOf("METALCONTAINER")).isEqualTo(PackageType.OTHER);
    }

    /** An absent type stays null - the fallback covers unknown values, not missing ones. */
    @Test
    void leavesAnAbsentTypeNull() {
        String json = """
                { "items": [ { "orderItemId": 11, "qty": 2 } ], "weight": 18.5 }
                """;

        assertThat(mapper.readValue(json, CreatePackageRequest.class).packageType()).isNull();
    }
}
