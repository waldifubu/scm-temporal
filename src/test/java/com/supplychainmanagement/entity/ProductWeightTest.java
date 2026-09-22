package com.supplychainmanagement.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The weight a product gets on insert: the sum of its components, and never a failed insert. */
class ProductWeightTest {

    private static Component component(String weight) {
        Component component = new Component();
        component.setWeight(weight == null ? null : new BigDecimal(weight));
        return component;
    }

    @Test
    void sumsTheWeightOfItsComponents() {
        Product product = new Product();
        product.setComponents(List.of(component("1.5"), component("2.25")));

        product.onCreate();

        assertThat(product.getWeight()).isEqualByComparingTo("3.75");
    }

    /** Used to throw a NullPointerException, which reached the client as a 500. */
    @Test
    void weighsNothingWithoutComponents() {
        Product product = new Product();
        product.setComponents(null);

        product.onCreate();

        assertThat(product.getWeight()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(product.isActive()).isTrue();
    }

    @Test
    void skipsAComponentWithoutWeight() {
        Product product = new Product();
        List<Component> components = new ArrayList<>();
        components.add(component("2"));
        components.add(component(null));
        product.setComponents(components);

        product.onCreate();

        assertThat(product.getWeight()).isEqualByComparingTo("2");
    }
}
