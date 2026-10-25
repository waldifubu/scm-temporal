package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.users.Distributor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Carriers only. {@code User} is one table with a {@code user_type} discriminator, and a repository of
 * the subtype adds the discriminator to every query - so no filter on the type is written here, and
 * none can be forgotten. Same arrangement as {@link SupplierRepository}.
 */
public interface DistributorRepository extends JpaRepository<Distributor, Long> {

    /**
     * The carriers a shipment can be handed to. A disabled account cannot log in, so it could never
     * accept, report in transit or deliver - a shipment handed to it would sit in DISPATCH_REQUESTED for
     * good.
     */
    Page<Distributor> findAllByIsActiveTrue(Pageable pageable);
}
