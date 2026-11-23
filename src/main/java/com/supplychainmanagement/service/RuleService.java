package com.supplychainmanagement.service;

import com.supplychainmanagement.dto.rule.DistributorRuleRequest;
import com.supplychainmanagement.dto.rule.DistributorRuleResponse;
import com.supplychainmanagement.dto.rule.SupplierRuleRequest;
import com.supplychainmanagement.dto.rule.SupplierRuleResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** The two rule tables, read-only: which supplier a request and which carrier a shipment is handed to. */
public interface RuleService {

    /** One page of supplier rules, mapped while the session is open. */
    Page<SupplierRuleResponse> findSupplierRules(Pageable pageable);

    /** One page of distributor rules, mapped while the session is open. */
    Page<DistributorRuleResponse> findDistributorRules(Pageable pageable);

    /** Creates a supplier rule; 404 for an unknown supplier, component or product, 400 for a user who is no supplier. */
    SupplierRuleResponse createSupplierRule(SupplierRuleRequest request);

    SupplierRuleResponse updateSupplierRule(Long id, SupplierRuleRequest request);

    /**
     * Creates a distributor rule; 404 for an unknown distributor or customer, 400 for a user of the wrong type or a
     * range whose minimum is not below its maximum.
     */
    DistributorRuleResponse createDistributorRule(DistributorRuleRequest request);

    DistributorRuleResponse updateDistributorRule(Long id, DistributorRuleRequest request);

    /** Deletes a rule for good; 404 for an unknown one. Nothing refers to a rule, so nothing else is touched. */
    void deleteSupplierRule(Long id);

    void deleteDistributorRule(Long id);
}
