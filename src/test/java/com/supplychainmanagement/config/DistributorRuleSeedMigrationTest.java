package com.supplychainmanagement.config;

import com.supplychainmanagement.entity.users.Customer;
import com.supplychainmanagement.entity.users.Distributor;
import com.supplychainmanagement.repository.DistributorRuleRepository;
import com.supplychainmanagement.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seed of {@code distributor_rule} against the real table: written into an empty one, never next to rules
 * that are there, and only for users that exist with the right type. Everything happens inside the test's
 * transaction, so the table is emptied for the test and comes back as it was.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class DistributorRuleSeedMigrationTest {

    @Autowired
    private DistributorRuleSeedMigration migration;
    @Autowired
    private DistributorRuleRepository rules;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TestData testData;

    private void emptyTable() {
        jdbcTemplate.update("DELETE FROM distributor_rule");
    }

    @Test
    void writesACustomerRuleForEachPairWhoseUsersExist() {
        emptyTable();
        Customer customer = testData.customer();
        Distributor distributor = testData.distributor();

        int written = migration.seed(Map.of(customer.getId(), distributor.getId()));

        assertThat(written).isEqualTo(1);
        var matching = rules.findMatching(customer.getId(), null, null);
        assertThat(matching).hasSize(1);
        assertThat(matching.getFirst().getDistributor().getId()).isEqualTo(distributor.getId());
        assertThat(matching.getFirst().getPriority()).isZero();
    }

    @Test
    void writesNothingNextToRulesThatAreAlreadyThere() {
        emptyTable();
        Customer customer = testData.customer();
        Distributor distributor = testData.distributor();
        assertThat(migration.seed(Map.of(customer.getId(), distributor.getId()))).isEqualTo(1);

        Customer other = testData.customer();
        assertThat(migration.seed(Map.of(other.getId(), distributor.getId()))).isZero();
        assertThat(rules.findMatching(other.getId(), null, null)).isEmpty();
    }

    @Test
    void skipsAPairWhoseUsersAreMissingOrOfTheWrongType() {
        emptyTable();
        Customer customer = testData.customer();
        Distributor distributor = testData.distributor();

        assertThat(migration.seed(Map.of(-1L, distributor.getId()))).isZero();
        assertThat(migration.seed(Map.of(customer.getId(), -2L))).isZero();
        // A distributor is no customer, and a customer no distributor.
        assertThat(migration.seed(Map.of(distributor.getId(), customer.getId()))).isZero();
    }

    @Test
    void theDevelopmentPairsAreTheOnesTheFixedMapHad() {
        assertThat(DistributorRuleSeedMigration.DISTRIBUTOR_OF_CUSTOMER).containsExactlyInAnyOrderEntriesOf(Map.of(311L, 319L, 312L, 320L));
    }
}
