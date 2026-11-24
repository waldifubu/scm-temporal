package com.supplychainmanagement.dto.mapper;

import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.ProductCategory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProductMapperTest {

    private final ProductMapper mapper = new ProductMapperImpl();

    private Product sampleProduct() {
        Product product = new Product();
        product.setId(7L);
        product.setArticleNo(1001L);
        product.setName("Zahnriemen");
        product.setDescription("Antriebsriemen");
        product.setUnitPrice(new BigDecimal("19.99"));
        product.setWeight(new BigDecimal("2.5"));
        product.setSku(UUID.fromString("706a99c3-944b-11f1-9b51-001e064520d8"));
        product.setActive(true);

        ProductCategory category = new ProductCategory();
        category.setId(3L);
        category.setName("Antrieb");
        product.setCategories(new LinkedHashSet<>(List.of(category)));

        Component component = new Component();
        component.setId(11L);
        component.setName("Spannrolle");
        component.setWeight(new BigDecimal("0.5"));
        product.setComponents(new ArrayList<>(List.of(component)));

        return product;
    }

    @Test
    void privilegedViewCarriesEverything() {
        var dto = mapper.mapToDto(sampleProduct(), true);

        assertThat(dto.id()).isEqualTo(7L);
        assertThat(dto.articleNo()).isEqualTo(1001L);
        assertThat(dto.categories()).extracting("name").containsExactly("Antrieb");
        assertThat(dto.components()).extracting("name").containsExactly("Spannrolle");
    }

    @Test
    void publicViewOmitsInternalFields() {
        var dto = mapper.mapToDto(sampleProduct(), false);

        assertThat(dto.id()).isNull();
        assertThat(dto.categories()).isNull();
        assertThat(dto.components()).isNull();

        // fields meant for the outside world are preserved
        assertThat(dto.articleNo()).isEqualTo(1001L);
        assertThat(dto.name()).isEqualTo("Zahnriemen");
        assertThat(dto.unitPrice()).isEqualByComparingTo("19.99");
    }

    @Test
    void publicJsonContainsNeitherIdNorCategoriesNorComponents() {
        String json = JsonMapper.builder().build()
                .writeValueAsString(mapper.mapToDto(sampleProduct(), false));

        assertThat(json)
                .doesNotContain("\"id\"")
                .doesNotContain("categories")
                .doesNotContain("components");
        assertThat(json).contains("\"articleNo\":1001", "\"name\":\"Zahnriemen\"");
    }

    /**
     * The controller used to null the fields on the entity itself in order to keep them out of the
     * JSON. On a managed entity the next flush would have turned that into deleted rows - components
     * is mapped with orphanRemoval = true.
     */
    @Test
    void mappingLeavesTheEntityUntouched() {
        Product product = sampleProduct();

        mapper.mapToDto(product, false);

        assertThat(product.getId()).isEqualTo(7L);
        assertThat(product.getCategories()).hasSize(1);
        assertThat(product.getComponents()).hasSize(1);
    }
}
