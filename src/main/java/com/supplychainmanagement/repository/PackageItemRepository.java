package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.PackageItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PackageItemRepository extends JpaRepository<PackageItem, Long> {
}
