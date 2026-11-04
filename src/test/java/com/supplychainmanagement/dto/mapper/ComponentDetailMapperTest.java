package com.supplychainmanagement.dto.mapper;

import com.supplychainmanagement.dto.component.ComponentDetailDto;
import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.entity.Product;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The details answer carries every field {@code PATCH /components/{sku}} can change, so a form can start from it. */
class ComponentDetailMapperTest {

    @Test
    void carriesTheEditableFields() {
        Product product = new Product();
        product.setId(7L);
        product.setArticleNo(900L);
        product.setName("Regal");
        Component component = new Component();
        component.setId(11L);
        UUID sku = UUID.randomUUID();
        component.setSku(sku);
        component.setName("Blech");
        component.setManufacturer("ACME");
        component.setDescription("ein Blech");
        component.setExternalId("EXT-1");
        component.setWeight(new BigDecimal("12.500"));
        component.setArticleNo("A-11");
        component.setQty(3);
        component.setProduct(product);
        setTimestamp(component, "createdAt", java.time.LocalDateTime.of(2026, 11, 1, 8, 30, 15));
        setTimestamp(component, "updatedAt", java.time.LocalDateTime.of(2026, 11, 3, 21, 5, 0));

        ComponentDetailDto dto = Mappers.getMapper(ComponentMapper.class).mapToDetailDto(component);

        assertThat(dto.sku()).isEqualTo(sku.toString());
        assertThat(dto.name()).isEqualTo("Blech");
        assertThat(dto.manufacturer()).isEqualTo("ACME");
        assertThat(dto.description()).isEqualTo("ein Blech");
        assertThat(dto.externalId()).isEqualTo("EXT-1");
        assertThat(dto.weight()).isEqualByComparingTo("12.5");
        assertThat(dto.qty()).isEqualTo(3);
        assertThat(dto.product().articleNo()).isEqualTo(900L);
        assertThat(dto.createdAt()).isEqualTo(java.time.LocalDateTime.of(2026, 11, 1, 8, 30, 15));
        assertThat(dto.updatedAt()).isEqualTo(java.time.LocalDateTime.of(2026, 11, 3, 21, 5, 0));
    }

    /** The timestamps have no setter: Hibernate stamps them. */
    private static void setTimestamp(Component component, String field, java.time.LocalDateTime value) {
        try {
            var f = Component.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(component, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
