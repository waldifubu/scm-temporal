package com.supplychainmanagement.controller;

import com.supplychainmanagement.dto.common.PageResponse;
import com.supplychainmanagement.dto.rule.DistributorRuleRequest;
import com.supplychainmanagement.dto.rule.DistributorRuleResponse;
import com.supplychainmanagement.dto.rule.SupplierRuleRequest;
import com.supplychainmanagement.dto.rule.SupplierRuleResponse;
import com.supplychainmanagement.model.common.PageRequests;
import com.supplychainmanagement.service.RuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/**
 * The two rule tables - listed, created, changed and deleted - ADMIN only, the same restricted area as
 * {@code /users}. The rules are what {@code POST /components/request} and
 * {@code POST /users/distributors/query} work from.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping({"/api/{version}"})
public class RuleController {

    public static final Set<String> SUPPLIER_RULE_SORTABLE = Set.of(
            "id", "priority", "createdAt", "supplier.lastName", "component.name", "product.name");
    public static final Set<String> DISTRIBUTOR_RULE_SORTABLE = Set.of(
            "id", "priority", "distributor.lastName", "customer.lastName",
            "minWeight", "maxWeight", "minVolume", "maxVolume");

    private final RuleService ruleService;

    @GetMapping(path = "/supplier-rules", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN')")
    public PageResponse<SupplierRuleResponse> supplierRules(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "id") String sort,
            @RequestParam(defaultValue = "ASC") String order) {
        return PageResponse.of(ruleService.findSupplierRules(
                PageRequests.of(page, size, sort, order, SUPPLIER_RULE_SORTABLE)));
    }

    @GetMapping(path = "/distributor-rules", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN')")
    public PageResponse<DistributorRuleResponse> distributorRules(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "id") String sort,
            @RequestParam(defaultValue = "ASC") String order) {
        return PageResponse.of(ruleService.findDistributorRules(
                PageRequests.of(page, size, sort, order, DISTRIBUTOR_RULE_SORTABLE)));
    }

    @PostMapping(path = "/supplier-rules", version = "1.0")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('ADMIN')")
    public SupplierRuleResponse createSupplierRule(@RequestBody @Valid SupplierRuleRequest request) {
        return ruleService.createSupplierRule(request);
    }

    @PutMapping(path = "/supplier-rules/{id}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN')")
    public SupplierRuleResponse updateSupplierRule(@PathVariable Long id,
                                                   @RequestBody @Valid SupplierRuleRequest request) {
        return ruleService.updateSupplierRule(id, request);
    }

    @DeleteMapping(path = "/supplier-rules/{id}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN')")
    public void deleteSupplierRule(@PathVariable Long id) {
        ruleService.deleteSupplierRule(id);
    }

    @DeleteMapping(path = "/distributor-rules/{id}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN')")
    public void deleteDistributorRule(@PathVariable Long id) {
        ruleService.deleteDistributorRule(id);
    }

    @PostMapping(path = "/distributor-rules", version = "1.0")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('ADMIN')")
    public DistributorRuleResponse createDistributorRule(@RequestBody @Valid DistributorRuleRequest request) {
        return ruleService.createDistributorRule(request);
    }

    @PutMapping(path = "/distributor-rules/{id}", version = "1.0")
    @PreAuthorize("hasAnyAuthority('ADMIN')")
    public DistributorRuleResponse updateDistributorRule(@PathVariable Long id,
                                                         @RequestBody @Valid DistributorRuleRequest request) {
        return ruleService.updateDistributorRule(id, request);
    }
}
