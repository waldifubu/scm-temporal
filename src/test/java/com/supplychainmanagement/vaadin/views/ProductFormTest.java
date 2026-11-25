package com.supplychainmanagement.vaadin.views;

import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.ProductCategory;
import com.vaadin.flow.component.textfield.TextField;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The "Product Details" drawer of the Vaadin catalogue shows the categories of the selected product. */
class ProductFormTest {

    private static ProductCategory category(String name) {
        ProductCategory category = new ProductCategory();
        category.setName(name);
        return category;
    }

    private static String categoryShown(ProductForm form) {
        return form.getChildren().findFirst().orElseThrow().getChildren()
                .filter(TextField.class::isInstance)
                .map(TextField.class::cast)
                .filter(field -> "Category".equals(field.getLabel()))
                .findFirst().orElseThrow()
                .getValue();
    }

    @Test
    void showsTheCategoryNamesInOneLine() {
        Product product = new Product();
        product.setCategories(new LinkedHashSet<>(List.of(category("Exterieur"), category("Drive"))));
        ProductForm form = new ProductForm();

        form.setFormDataObject(product);

        assertThat(categoryShown(form)).isEqualTo("Drive, Exterieur");
    }

    @Test
    void staysEmptyWithoutACategoryOrAProduct() {
        ProductForm form = new ProductForm();

        form.setFormDataObject(new Product());
        assertThat(categoryShown(form)).isEmpty();

        form.setFormDataObject(null);
        assertThat(categoryShown(form)).isEmpty();
    }
}
