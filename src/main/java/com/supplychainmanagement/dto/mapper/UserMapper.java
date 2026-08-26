package com.supplychainmanagement.dto.mapper;

import com.supplychainmanagement.dto.user.UserDto;
import com.supplychainmanagement.entity.Role;
import com.supplychainmanagement.entity.users.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.LinkedHashSet;
import java.util.Set;

@Mapper(componentModel = "spring")
public interface UserMapper {

    @Mapping(target = "roles", source = "roles", qualifiedByName = "rolesToNames")
    UserDto mapToDto(User user);

    /**
     * Expose role names only, not the Role entities: through {@code Role.users} those drag along
     * the entire user list (kept out of the JSON by @JsonIgnore, but still a lazy collection that
     * mapping would touch).
     */
    @Named("rolesToNames")
    default Set<String> rolesToNames(Set<Role> roles) {
        if (roles == null) {
            return Set.of();
        }

        Set<String> names = new LinkedHashSet<>();
        for (Role role : roles) {
            if (role.getRolename() != null) {
                names.add(role.getRolename().name());
            }
        }
        return names;
    }
}
