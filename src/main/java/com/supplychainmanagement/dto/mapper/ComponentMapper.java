package com.supplychainmanagement.dto.mapper;

import com.supplychainmanagement.dto.component.ComponentResponseDto;
import com.supplychainmanagement.dto.component.ProductRefDto;
import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.entity.Product;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ComponentMapper {
    @Mapping(target = "product", source = "product")
    ComponentResponseDto mapToDto(Component component);

    @Mapping(target = "product", source = "product")
    Component mapToEntity(ComponentResponseDto componentResponseDto);

    ProductRefDto mapToDto(Product product);

    Product mapToEntity(ProductRefDto productRefDto);
}
