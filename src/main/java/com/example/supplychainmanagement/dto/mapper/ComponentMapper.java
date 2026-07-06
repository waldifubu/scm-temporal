package com.example.supplychainmanagement.dto.mapper;

import com.example.supplychainmanagement.dto.component.ComponentResponseDto;
import com.example.supplychainmanagement.dto.component.ProductRefDto;
import com.example.supplychainmanagement.entity.Component;
import com.example.supplychainmanagement.entity.Product;
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
