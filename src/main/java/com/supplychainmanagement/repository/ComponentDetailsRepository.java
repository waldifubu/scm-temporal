package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.Component;
import org.springframework.data.repository.CrudRepository;

public interface ComponentDetailsRepository extends CrudRepository<Component, Long> {
}