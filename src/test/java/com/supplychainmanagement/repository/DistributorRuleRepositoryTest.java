package com.supplychainmanagement.repository;

import com.supplychainmanagement.entity.DistributorRule;
import com.supplychainmanagement.entity.users.Customer;
import com.supplychainmanagement.entity.users.Distributor;
import com.supplychainmanagement.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code findMatching} against real rows: the bounds are [min, max), a null bound is no condition, a parameter
 * that is null matches only the rules that leave the dimension open, and a rule of a deactivated carrier is
 * not offered.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestData.class)
@Transactional
class DistributorRuleRepositoryTest {

    @Autowired
    private DistributorRuleRepository repository;
    @Autowired
    private TestData testData;

    private static BigDecimal number(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private void rule(Distributor distributor, Customer customer, String minWeight, String maxWeight,
                      String minVolume, String maxVolume) {
        DistributorRule rule = new DistributorRule();
        rule.setDistributor(distributor);
        rule.setCustomer(customer);
        rule.setMinWeight(number(minWeight));
        rule.setMaxWeight(number(maxWeight));
        rule.setMinVolume(number(minVolume));
        rule.setMaxVolume(number(maxVolume));
        repository.saveAndFlush(rule);
    }

    private List<Long> distributorsFor(Customer customer, String weight, String volume) {
        return repository.findMatching(customer == null ? null : customer.getId(), number(weight), number(volume))
                .stream().map(r -> r.getDistributor().getId()).toList();
    }

    @Test
    void aWeightBoundIsInclusiveAtTheBottomAndExclusiveAtTheTop() {
        Distributor light = testData.distributor();
        Distributor heavy = testData.distributor();
        rule(light, null, null, "50", null, null);
        rule(heavy, null, "50", null, null, null);

        assertThat(distributorsFor(null, "49.9999", null)).contains(light.getId()).doesNotContain(heavy.getId());
        assertThat(distributorsFor(null, "50", null)).contains(heavy.getId()).doesNotContain(light.getId());
    }

    @Test
    void aVolumeBoundWorksTheSameWay() {
        Distributor small = testData.distributor();
        Distributor big = testData.distributor();
        rule(small, null, null, null, null, "1000");
        rule(big, null, null, null, "1000", null);

        assertThat(distributorsFor(null, null, "999")).contains(small.getId()).doesNotContain(big.getId());
        assertThat(distributorsFor(null, null, "1000")).contains(big.getId()).doesNotContain(small.getId());
    }

    @Test
    void aRuleForACustomerIsNotOfferedToAnother() {
        Customer mine = testData.customer();
        Customer other = testData.customer();
        Distributor carrier = testData.distributor();
        rule(carrier, mine, null, null, null, null);

        assertThat(distributorsFor(mine, null, null)).contains(carrier.getId());
        assertThat(distributorsFor(other, null, null)).doesNotContain(carrier.getId());
        assertThat(distributorsFor(null, null, null)).doesNotContain(carrier.getId());
    }

    @Test
    void aRuleForAnyCustomerIsOfferedToEveryone() {
        Customer customer = testData.customer();
        Distributor carrier = testData.distributor();
        rule(carrier, null, null, null, null, null);

        assertThat(distributorsFor(customer, "10", "10")).contains(carrier.getId());
        assertThat(distributorsFor(null, null, null)).contains(carrier.getId());
    }

    @Test
    void anUnknownWeightMatchesOnlyRulesWithoutAWeightRange() {
        Distributor ranged = testData.distributor();
        Distributor open = testData.distributor();
        rule(ranged, null, "10", "20", null, null);
        rule(open, null, null, null, null, null);

        assertThat(distributorsFor(null, null, null)).contains(open.getId()).doesNotContain(ranged.getId());
    }

    @Test
    void aDeactivatedCarrierIsNotOffered() {
        Distributor gone = testData.distributor();
        gone.setIsActive(false);
        rule(gone, null, null, null, null, null);

        assertThat(distributorsFor(null, null, null)).doesNotContain(gone.getId());
    }
}
