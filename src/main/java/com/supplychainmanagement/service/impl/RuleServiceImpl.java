package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.rule.DistributorRuleRequest;
import com.supplychainmanagement.dto.rule.DistributorRuleResponse;
import com.supplychainmanagement.dto.rule.SupplierRuleRequest;
import com.supplychainmanagement.dto.rule.SupplierRuleResponse;
import com.supplychainmanagement.entity.DistributorRule;
import com.supplychainmanagement.entity.SupplierRule;
import com.supplychainmanagement.entity.users.Customer;
import com.supplychainmanagement.entity.users.Distributor;
import com.supplychainmanagement.entity.users.Supplier;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.repository.ComponentRepository;
import com.supplychainmanagement.repository.DistributorRuleRepository;
import com.supplychainmanagement.repository.ProductRepository;
import com.supplychainmanagement.repository.UserRepository;
import org.hibernate.Hibernate;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import com.supplychainmanagement.repository.SupplierRuleRepository;
import com.supplychainmanagement.service.RuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RuleServiceImpl implements RuleService {

    private final SupplierRuleRepository supplierRuleRepository;
    private final DistributorRuleRepository distributorRuleRepository;
    private final UserRepository userRepository;
    private final ComponentRepository componentRepository;
    private final ProductRepository productRepository;

    @Override
    public Page<SupplierRuleResponse> findSupplierRules(Pageable pageable) {
        return supplierRuleRepository.findAllBy(withIdTieBreak(pageable)).map(SupplierRuleResponse::from);
    }

    @Override
    public Page<DistributorRuleResponse> findDistributorRules(Pageable pageable) {
        return distributorRuleRepository.findAllBy(withIdTieBreak(pageable)).map(DistributorRuleResponse::from);
    }

    @Override
    @Transactional
    public SupplierRuleResponse createSupplierRule(SupplierRuleRequest request) {
        return SupplierRuleResponse.from(supplierRuleRepository.saveAndFlush(apply(new SupplierRule(), request)));
    }

    @Override
    @Transactional
    public SupplierRuleResponse updateSupplierRule(Long id, SupplierRuleRequest request) {
        SupplierRule rule = supplierRuleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("SupplierRule", "id", id));
        return SupplierRuleResponse.from(supplierRuleRepository.saveAndFlush(apply(rule, request)));
    }

    @Override
    @Transactional
    public DistributorRuleResponse createDistributorRule(DistributorRuleRequest request) {
        return DistributorRuleResponse.from(
                distributorRuleRepository.saveAndFlush(apply(new DistributorRule(), request)));
    }

    @Override
    @Transactional
    public DistributorRuleResponse updateDistributorRule(Long id, DistributorRuleRequest request) {
        DistributorRule rule = distributorRuleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("DistributorRule", "id", id));
        return DistributorRuleResponse.from(distributorRuleRepository.saveAndFlush(apply(rule, request)));
    }

    @Override
    @Transactional
    public void deleteSupplierRule(Long id) {
        supplierRuleRepository.delete(supplierRuleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("SupplierRule", "id", id)));
    }

    @Override
    @Transactional
    public void deleteDistributorRule(Long id) {
        distributorRuleRepository.delete(distributorRuleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("DistributorRule", "id", id)));
    }

    private SupplierRule apply(SupplierRule rule, SupplierRuleRequest request) {
        rule.setSupplier(userOfType(request.supplierId(), Supplier.class, "supplier"));
        Long componentId = request.componentId();
        rule.setComponent(componentId == null ? null : componentRepository.findById(componentId)
                .orElseThrow(() -> new ResourceNotFoundException("Component", "id", componentId)));
        Long productId = request.productId();
        rule.setProduct(productId == null ? null : productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", "id", productId)));
        rule.setPriority(request.priority() == null ? 0 : request.priority());
        return rule;
    }

    private DistributorRule apply(DistributorRule rule, DistributorRuleRequest request) {
        requireRange("weight", request.minWeight(), request.maxWeight());
        requireRange("volume", request.minVolume(), request.maxVolume());
        rule.setDistributor(userOfType(request.distributorId(), Distributor.class, "distributor"));
        Long customerId = request.customerId();
        rule.setCustomer(customerId == null ? null : userOfType(customerId, Customer.class, "customer"));
        rule.setMinWeight(request.minWeight());
        rule.setMaxWeight(request.maxWeight());
        rule.setMinVolume(request.minVolume());
        rule.setMaxVolume(request.maxVolume());
        rule.setPriority(request.priority() == null ? 0 : request.priority());
        return rule;
    }

    /** A bound is {@code [min, max)}: a range whose minimum is not below its maximum would match nothing. */
    private static void requireRange(String name, BigDecimal min, BigDecimal max) {
        if (min != null && max != null && min.compareTo(max) >= 0) {
            throw new APIException(HttpStatus.BAD_REQUEST,
                    "The " + name + " range is empty: the minimum has to be below the maximum");
        }
    }

    /** The user with this id, checked on the unproxied instance - a lazy proxy would fail an {@code instanceof}. */
    private <T> T userOfType(Long id, Class<T> type, String what) {
        Object user = Hibernate.unproxy(userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", id)));
        if (!type.isInstance(user)) {
            throw new APIException(HttpStatus.BAD_REQUEST, "User " + id + " is not a " + what);
        }
        return type.cast(user);
    }

    /** Rules share priorities and conditions, so the id decides between equal rows - the next page must not reshuffle. */
    private static Pageable withIdTieBreak(Pageable pageable) {
        if (pageable.getSort().getOrderFor("id") != null) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                pageable.getSort().and(Sort.by("id")));
    }
}
