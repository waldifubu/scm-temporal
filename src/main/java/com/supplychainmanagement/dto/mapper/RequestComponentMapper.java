package com.supplychainmanagement.dto.mapper;

import com.supplychainmanagement.dto.component.ComponentResponseDto;
import com.supplychainmanagement.dto.component.RequestComponentResponseDto;
import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.entity.RequestComponent;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** The answer direction is written by hand, see {@link ComponentMapper}. */
@Mapper(componentModel = "spring")
public interface RequestComponentMapper {

    default RequestComponentResponseDto mapToDto(RequestComponent request) {
        if (request == null) {
            return null;
        }
        return new RequestComponentResponseDto(
                request.getId(),
                request.getComment(),
                request.getRequestDate(),
                request.getRequestStatus(),
                request.getUpdated(),
                request.getQty(),
                ComponentMapper.toResponse(request.getComponent()));
    }

    default ComponentResponseDto mapToDto(Component component) {
        return ComponentMapper.toResponse(component);
    }

    @Mapping(target = "component", source = "component")
    RequestComponent mapToEntity(RequestComponentResponseDto componentResponseDto);

    Component mapToEntity(ComponentResponseDto componentResponseDto);
}
