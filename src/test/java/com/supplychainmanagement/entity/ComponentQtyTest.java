package com.supplychainmanagement.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bill-of-materials quantity of a component: how many of it go into one unit of the product.
 * <p>
 * The column is NOT NULL and the recipe reads it directly, so a value that cannot be meant must not
 * reach either. In the entity rather than the service, because the callback is package-private -
 * same as ProductWeightTest next to it.
 */
class ComponentQtyTest {

    private static final UUID SKU = UUID.fromString("706a99c3-944b-11f1-9b51-001e064520d8");

    private static Component component(Integer qty) {
        Component component = new Component();
        component.setSku(SKU);
        component.setQty(qty);
        return component;
    }

    /** Nothing said: a component that is part of the recipe is needed once. */
    @Test
    void defaultsAMissingQuantityToOne() {
        Component component = component(null);

        component.onCreate();

        assertThat(component.getQty()).isEqualTo(1);
    }

    /** Zero or less would tell the production routine the product can be built out of nothing. */
    @ParameterizedTest
    @ValueSource(ints = {0, -3})
    void liftsAQuantityThatCannotBeMeantToOne(int qty) {
        Component component = component(qty);

        component.onCreate();

        assertThat(component.getQty()).isEqualTo(1);
    }

    /** A real quantity is kept as it is. */
    @Test
    void keepsAQuantityThatWasSet() {
        Component component = component(4);

        component.onCreate();

        assertThat(component.getQty()).isEqualTo(4);
    }
}
