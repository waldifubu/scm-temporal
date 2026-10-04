package com.supplychainmanagement.dto.mapper;

import com.supplychainmanagement.dto.component.ComponentResponseDto;
import com.supplychainmanagement.dto.component.ProductRefDto;
import com.supplychainmanagement.dto.component.RequestComponentResponseDto;
import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.RequestComponent;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface RequestComponentMapper {
    @Mapping(target = "component", source = "component")
    RequestComponentResponseDto mapToDto(RequestComponent component);

    @Mapping(target = "component", source = "component")
    RequestComponent mapToEntity(RequestComponentResponseDto componentResponseDto);

    ComponentResponseDto mapToDto(Component component);

    Component mapToEntity(ComponentResponseDto componentResponseDto);
}
