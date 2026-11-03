package com.supplychainmanagement.dto.mapper;

import com.supplychainmanagement.dto.user.UserDto;
import com.supplychainmanagement.entity.Role;
import com.supplychainmanagement.entity.users.Distributor;
import com.supplychainmanagement.entity.users.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.LinkedHashSet;
import java.util.Set;

@Mapper(componentModel = "spring")
public interface UserMapper {

    @Mapping(target = "roles", source = "roles", qualifiedByName = "rolesToNames")
    @Mapping(target = "ruleId", source = "user", qualifiedByName = "ruleIdOf")
    UserDto mapToDto(User user);

    /**
     * The rule that named a carrier in a {@code queryDistributors} answer. It lives on {@code Distributor} only, so a
     * mapping by property name finds nothing on {@code User} and leaves it {@code null} - this reads it off the carrier.
     * {@code null} for every other user, and for a carrier that was not looked up through a rule.
     */
    @Named("ruleIdOf")
    default Long ruleIdOf(User user) {
        return user instanceof Distributor distributor ? distributor.getRuleId() : null;
    }

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
