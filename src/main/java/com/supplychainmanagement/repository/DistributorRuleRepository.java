package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.DistributorRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface DistributorRuleRepository extends JpaRepository<DistributorRule, Long> {

    /**
     * Every rule that applies to a shipment of this customer, weight (kg) and volume (cm&sup3;) and whose
     * carrier is active - a disabled account could never accept the shipment, so a rule pointing at one is
     * skipped and the next one gets its turn. A dimension a rule leaves {@code null} is no condition; bounds
     * are {@code [min, max)}. A parameter that is {@code null} compares to nothing, so it matches only the rules
     * that do not set that dimension. Which of the rules wins is {@link DistributorRule#specificity()} and
     * the priority, worked out by the caller.
     */
    @Query("""
            select r from DistributorRule r join fetch r.distributor d
            where d.isActive = true
              and (r.customer is null or r.customer.id = :customerId)
              and (r.minWeight is null or r.minWeight <= :weight)
              and (r.maxWeight is null or r.maxWeight > :weight)
              and (r.minVolume is null or r.minVolume <= :volume)
              and (r.maxVolume is null or r.maxVolume > :volume)
            """)
    List<DistributorRule> findMatching(@Param("customerId") Long customerId,
                                       @Param("weight") BigDecimal weight,
                                       @Param("volume") BigDecimal volume);
}
