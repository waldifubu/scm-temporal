package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.Role;
import com.supplychainmanagement.model.enums.RoleEnum;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {
        Optional<Role> findByRolename(RoleEnum rolename);
}
