package com.supplychainmanagement.dto.mapper;

import com.supplychainmanagement.dto.user.UserDto;
import com.supplychainmanagement.entity.Role;
import com.supplychainmanagement.entity.users.Distributor;
import com.supplychainmanagement.entity.users.User;
import org.mapstruct.Mapper;

import java.util.LinkedHashSet;
import java.util.Set;

/** Written by hand, see {@link ComponentMapper}. */
@Mapper(componentModel = "spring")
public interface UserMapper {

    default UserDto mapToDto(User user) {
        if (user == null) {
            return null;
        }
        return new UserDto(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getUsername(),
                user.getEmail(),
                user.getColor(),
                // a missing flag counts as not active, as everywhere else
                Boolean.TRUE.equals(user.getIsActive()),
                rolesToNames(user.getRoles()),
                ruleIdOf(user));
    }

    /**
     * The rule that named a carrier in a {@code queryDistributors} answer. It lives on {@code Distributor} only, so a
     * mapping by property name finds nothing on {@code User} - this reads it off the carrier.
     * {@code null} for every other user, and for a carrier that was not looked up through a rule.
     */
    default Long ruleIdOf(User user) {
        return user instanceof Distributor distributor ? distributor.getRuleId() : null;
    }

    /**
     * Expose role names only, not the Role entities: through {@code Role.users} those drag along
     * the entire user list (kept out of the JSON by @JsonIgnore, but still a lazy collection that
     * mapping would touch). Never null: no roles is an empty set.
     */
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
