package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.SupplierRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SupplierRuleRepository extends JpaRepository<SupplierRule, Long> {

    /** Every rule that hands requests to this supplier. */
    List<SupplierRule> findBySupplierId(Long supplierId);

    /**
     * Every rule whose supplier is active - a disabled account could never approve or deliver, so a rule
     * pointing at one is skipped and the next one gets its turn. Which rule applies to a component, and
     * which of several wins, is {@link SupplierRule#appliesTo} and {@link SupplierRule#BEST_FIRST}, worked
     * out by the caller: the table is small, and one query serves a whole request.
     */
    @Query("select r from SupplierRule r join fetch r.supplier s where s.isActive = true")
    List<SupplierRule> findAllWithActiveSupplier();
}
