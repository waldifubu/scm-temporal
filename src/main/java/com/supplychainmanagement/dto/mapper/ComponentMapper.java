package com.supplychainmanagement.dto.mapper;

import com.supplychainmanagement.dto.component.ComponentDetailDto;
import com.supplychainmanagement.dto.component.ComponentResponseDto;
import com.supplychainmanagement.dto.component.ProductRefDto;
import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.entity.Product;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * The answer direction is written by hand: the DTO packages are {@code @NullMarked}, and MapStruct refuses
 * to hand a property of an unannotated entity to a non-null parameter. Writing it out is also where each
 * value's null rule is stated - an absent entity gives an absent DTO, the rest is copied as it is.
 */
@Mapper(componentModel = "spring")
public interface ComponentMapper {

    default ComponentResponseDto mapToDto(Component component) {
        return toResponse(component);
    }

    default ComponentDetailDto mapToDetailDto(Component component) {
        if (component == null) {
            return null;
        }
        return new ComponentDetailDto(
                component.getId(),
                component.getManufacturer(),
                component.getName(),
                component.getDescription(),
                component.getSku() != null ? component.getSku().toString() : null,
                component.getArticleNo(),
                component.getExternalId(),
                component.getWeight(),
                component.getQty(),
                component.getCreatedAt(),
                component.getUpdatedAt(),
                toRef(component.getProduct()));
    }

    default ProductRefDto mapToDto(Product product) {
        return toRef(product);
    }

    @Mapping(target = "product", source = "product")
    Component mapToEntity(ComponentResponseDto componentResponseDto);

    Product mapToEntity(ProductRefDto productRefDto);

    /** Shared with {@link RequestComponentMapper}. */
    static ComponentResponseDto toResponse(Component component) {
        if (component == null) {
            return null;
        }
        return new ComponentResponseDto(
                component.getId(),
                component.getManufacturer(),
                component.getName(),
                component.getSku() != null ? component.getSku().toString() : null,
                component.getArticleNo(),
                component.getQty(),
                toRef(component.getProduct()));
    }

    static ProductRefDto toRef(Product product) {
        return product == null ? null : new ProductRefDto(product.getArticleNo(), product.getName());
    }
}
