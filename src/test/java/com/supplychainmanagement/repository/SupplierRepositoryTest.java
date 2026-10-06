package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.users.Supplier;
import com.supplychainmanagement.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who the supplier list actually contains, against the real database - the part a mocked repository
 * cannot say. {@code User} is one table with a {@code user_type} discriminator, so "suppliers only"
 * is a property of how the repository is typed, and "active only" of the derived query.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class SupplierRepositoryTest {

    @Autowired
    private SupplierRepository supplierRepository;
    @Autowired
    private TestData testData;

    private java.util.List<Long> listedIds() {
        return supplierRepository.findAllByIsActiveTrue(PageRequest.of(0, 1000)).getContent().stream()
                .map(Supplier::getId)
                .toList();
    }

    /** An active supplier is what a request can be placed with. */
    @Test
    void listsAnActiveSupplier() {
        Supplier active = testData.supplier();

        assertThat(listedIds()).contains(active.getId());
    }

    /**
     * A disabled supplier cannot log in, so it could never approve, send or deliver - a request placed
     * with it would sit in OPEN for good. It is not offered.
     */
    @Test
    void leavesADisabledSupplierOut() {
        Supplier disabled = testData.supplier();
        disabled.setIsActive(false);
        supplierRepository.saveAndFlush(disabled);

        assertThat(listedIds()).doesNotContain(disabled.getId());
    }

    /**
     * Only suppliers. A customer is a user in the same table and an active one - the discriminator
     * is what keeps them out, and nothing in the query writes it.
     */
    @Test
    void leavesOtherUserTypesOut() {
        var customer = testData.customer();
        Supplier supplier = testData.supplier();

        assertThat(listedIds()).contains(supplier.getId()).doesNotContain(customer.getId());
    }

    /** What comes back is a supplier through and through, not a user that happens to share the table. */
    @Test
    void returnsOnlySuppliers() {
        testData.customer();
        testData.supplier();

        assertThat(supplierRepository.findAllByIsActiveTrue(PageRequest.of(0, 1000)).getContent())
                .isNotEmpty()
                .allSatisfy(user -> assertThat(user.getIsActive()).isTrue());
    }

    /** The page is cut in SQL and sorted by a column of the user. */
    @Test
    void pagesAndSortsByLastName() {
        testData.supplier();
        testData.supplier();

        var page = supplierRepository.findAllByIsActiveTrue(
                PageRequest.of(0, 1, org.springframework.data.domain.Sort.by("lastName")));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(2);
    }
}
