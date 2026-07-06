package com.example.supplychainmanagement.repository;

import com.example.supplychainmanagement.entity.Role;
import com.example.supplychainmanagement.model.enums.RoleEnum;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {
        Optional<Role> findByRolename(RoleEnum rolename);
}
