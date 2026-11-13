package com.supplychainmanagement.dto.mapper;

import com.supplychainmanagement.dto.product.ProductCategoryDto;
import com.supplychainmanagement.dto.product.ProductComponentDto;
import com.supplychainmanagement.dto.product.ProductDto;
import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.ProductCategory;
import org.mapstruct.Mapper;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Written by hand, see {@link ComponentMapper}. */
@Mapper(componentModel = "spring")
public interface ProductMapper {

    /** Full view for ADMIN/MANAGER. */
    default ProductDto mapToDto(Product product) {
        if (product == null) {
            return null;
        }
        Set<ProductCategoryDto> categories = product.getCategories() == null ? null
                : product.getCategories().stream().map(this::mapToDto)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        List<ProductComponentDto> components = product.getComponents() == null ? null
                : product.getComponents().stream().map(this::mapToDto).toList();
        return build(product, product.getId(), categories, components);
    }

    /**
     * View for non-privileged callers: id, categories and components stay empty.
     * <p>
     * Since components is never read, this path also avoids initialising the lazy collection -
     * on the list endpoint that saves one query per product.
     */
    default ProductDto mapToPublicDto(Product product) {
        return product == null ? null : build(product, null, null, null);
    }

    default ProductCategoryDto mapToDto(ProductCategory category) {
        return category == null ? null
                : new ProductCategoryDto(category.getId(), category.getName(), category.getDescription());
    }

    default ProductComponentDto mapToDto(Component component) {
        if (component == null) {
            return null;
        }
        return new ProductComponentDto(
                component.getId(),
                component.getManufacturer(),
                component.getName(),
                component.getArticleNo(),
                component.getDescription(),
                component.getWeight() == null ? null : component.getWeight().doubleValue(),
                component.getSku(),
                component.getQty());
    }

    default ProductDto mapToDto(Product product, boolean isPrivilegedUser) {
        return isPrivilegedUser ? mapToDto(product) : mapToPublicDto(product);
    }

    private static ProductDto build(Product product, Long id, Set<ProductCategoryDto> categories,
                                    List<ProductComponentDto> components) {
        BigDecimal weight = product.getWeight();
        return new ProductDto(
                id,
                product.getArticleNo(),
                product.getName(),
                product.getDescription(),
                product.getUnitPrice(),
                weight == null ? null : weight.doubleValue(),
                product.getSku(),
                product.isActive(),
                product.getCreatedAt(),
                product.getUpdatedAt(),
                categories,
                components);
    }
}
