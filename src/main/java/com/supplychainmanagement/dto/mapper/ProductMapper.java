package com.supplychainmanagement.dto.mapper;

import com.supplychainmanagement.dto.product.ProductCategoryDto;
import com.supplychainmanagement.dto.product.ProductComponentDto;
import com.supplychainmanagement.dto.product.ProductDto;
import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.ProductCategory;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ProductMapper {

    /** Full view for ADMIN/MANAGER. */
    ProductDto mapToDto(Product product);

    /**
     * View for non-privileged callers: id, categories and components stay empty.
     * <p>
     * Since components is never read, this path also avoids initialising the lazy collection -
     * on the list endpoint that saves one query per product.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "categories", ignore = true)
    @Mapping(target = "components", ignore = true)
    ProductDto mapToPublicDto(Product product);

    ProductCategoryDto mapToDto(ProductCategory category);

    ProductComponentDto mapToDto(Component component);

    default ProductDto mapToDto(Product product, boolean isPrivilegedUser) {
        return isPrivilegedUser ? mapToDto(product) : mapToPublicDto(product);
    }
}
