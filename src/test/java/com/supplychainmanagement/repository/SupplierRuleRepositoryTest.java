package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.entity.SupplierRule;
import com.supplychainmanagement.entity.users.Supplier;
import com.supplychainmanagement.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code supplier_rule} against the real schema: the supplier is required, component and product are not, the timestamps stamp themselves. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class SupplierRuleRepositoryTest {

    @Autowired
    private SupplierRuleRepository repository;
    @Autowired
    private TestData testData;

    @Test
    void savesARuleAndStampsItsTimestamps() {
        Supplier supplier = testData.supplier();
        SupplierRule rule = new SupplierRule();
        rule.setSupplier(supplier);
        rule.setProduct(testData.product());

        SupplierRule saved = repository.saveAndFlush(rule);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(repository.findBySupplierId(supplier.getId())).extracting(SupplierRule::getId).containsExactly(saved.getId());
    }

    @Test
    void aRuleCanNameAComponent() {
        Component component = testData.component();
        SupplierRule rule = new SupplierRule();
        rule.setSupplier(testData.supplier());
        rule.setComponent(component);

        SupplierRule saved = repository.saveAndFlush(rule);

        assertThat(saved.getComponent().getId()).isEqualTo(component.getId());
    }

    @Test
    void componentAndProductAreOptional() {
        Supplier supplier = testData.supplier();
        SupplierRule rule = new SupplierRule();
        rule.setSupplier(supplier);

        SupplierRule saved = repository.saveAndFlush(rule);

        assertThat(saved.getComponent()).isNull();
        assertThat(saved.getProduct()).isNull();
    }

    @Test
    void aRuleWithoutASupplierIsRefused() {
        SupplierRule rule = new SupplierRule();
        rule.setProduct(testData.product());

        assertThatThrownBy(() -> repository.saveAndFlush(rule)).isInstanceOf(Exception.class);
    }

    @Test
    void priorityIsStoredAndDefaultsToZero() {
        SupplierRule plain = new SupplierRule();
        plain.setSupplier(testData.supplier());
        SupplierRule urgent = new SupplierRule();
        urgent.setSupplier(testData.supplier());
        urgent.setPriority(7);

        assertThat(repository.saveAndFlush(plain).getPriority()).isZero();
        assertThat(repository.saveAndFlush(urgent).getPriority()).isEqualTo(7);
    }

    /** What ordering reads: the rules of a deactivated supplier are not among them, with the supplier already fetched. */
    @Test
    void findAllWithActiveSupplierSkipsRulesOfADeactivatedSupplier() {
        Supplier active = testData.supplier();
        Supplier inactive = testData.supplier();
        inactive.setIsActive(false);
        SupplierRule kept = new SupplierRule();
        kept.setSupplier(active);
        SupplierRule dropped = new SupplierRule();
        dropped.setSupplier(inactive);
        repository.saveAndFlush(kept);
        repository.saveAndFlush(dropped);
        repository.flush();

        assertThat(repository.findAllWithActiveSupplier())
                .extracting(rule -> rule.getSupplier().getId())
                .contains(active.getId())
                .doesNotContain(inactive.getId());
    }
}
