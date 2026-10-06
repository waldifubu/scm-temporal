package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.users.Supplier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Suppliers only. {@code User} is one table with a {@code user_type} discriminator, and a repository
 * of the subtype adds the discriminator to every query - so no filter on the type is written here, and
 * none can be forgotten.
 */
public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    /**
     * The suppliers a request can still be placed with. A disabled account cannot log in, so it could
     * never approve, send or deliver anything - a request placed with it would sit in OPEN for good.
     */
    Page<Supplier> findAllByIsActiveTrue(Pageable pageable);
}
