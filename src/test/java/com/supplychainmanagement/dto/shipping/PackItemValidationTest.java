package com.supplychainmanagement.dto.shipping;

import com.supplychainmanagement.model.enums.ShipmentPackageType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.groups.Default;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a controller parameter marked @Valid rejects with a 400 before the service is even called.
 */
class PackItemValidationTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static List<String> violatedPaths(Object request, Class<?>... groups) {
        Set<ConstraintViolation<Object>> violations = VALIDATOR.validate(request, groups);
        return violations.stream().map(violation -> violation.getPropertyPath().toString()).toList();
    }

    @Test
    void rejectsAQuantityOfZero() {
        assertThat(violatedPaths(new CreatePackageItemsRequest(List.of(new PackItem(11L, 0)))))
                .containsExactly("items[0].qty");
    }

    @Test
    void rejectsAMissingQuantityAndOrderItem() {
        assertThat(violatedPaths(new CreatePackageItemsRequest(List.of(new PackItem(null, null)))))
                .containsExactlyInAnyOrder("items[0].qty", "items[0].orderItemId");
    }

    @Test
    void rejectsAnEmptyItemList() {
        assertThat(violatedPaths(new CreatePackageItemsRequest(List.of()))).containsExactly("items");
    }

    @Test
    void acceptsAQuantityOfOne() {
        assertThat(violatedPaths(new CreatePackageItemsRequest(List.of(new PackItem(11L, 1))))).isEmpty();
    }

    /** The package request carries the same rule - both endpoints share PackItem. */
    @Test
    void appliesToThePackageRequestAsWell() {
        CreatePackageRequest request = new CreatePackageRequest(
                List.of(new PackItem(11L, 0)), ShipmentPackageType.CARTON, null, null, null, null, null);

        assertThat(violatedPaths(request)).containsExactly("items[0].qty");
    }

    private static CreatePackageRequest packageRequest(List<PackItem> items) {
        return new CreatePackageRequest(items, ShipmentPackageType.CARTON, null, null, null, null, null);
    }

    /** A package created on its own: plain @Valid (Default group) lets items be left out. */
    @Test
    void letsThePackageRequestGoWithoutItemsByDefault() {
        assertThat(violatedPaths(packageRequest(null))).isEmpty();
        assertThat(violatedPaths(packageRequest(List.of()))).isEmpty();
    }

    /** A package packed together with its items: the WithItems group makes them required. */
    @Test
    void requiresItemsInTheWithItemsGroup() {
        assertThat(violatedPaths(packageRequest(null), Default.class, CreatePackageRequest.WithItems.class))
                .containsExactly("items");
        assertThat(violatedPaths(packageRequest(List.of()), Default.class, CreatePackageRequest.WithItems.class))
                .containsExactly("items");
    }

    /**
     * WithItems has to come together with Default: validated alone, the items are only checked for
     * being there - the rules on each PackItem belong to Default and are skipped.
     */
    @Test
    void checksTheItemsThemselvesOnlyTogetherWithDefault() {
        List<PackItem> zeroQuantity = List.of(new PackItem(11L, 0));

        assertThat(violatedPaths(packageRequest(zeroQuantity), Default.class, CreatePackageRequest.WithItems.class))
                .containsExactly("items[0].qty");
        assertThat(violatedPaths(packageRequest(zeroQuantity), CreatePackageRequest.WithItems.class))
                .isEmpty();
    }
}
