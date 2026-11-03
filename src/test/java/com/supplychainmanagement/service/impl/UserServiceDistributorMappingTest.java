package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.user.DistributorQueryRequest;
import com.supplychainmanagement.entity.DistributorRule;
import com.supplychainmanagement.entity.users.Customer;
import com.supplychainmanagement.entity.users.Distributor;
import com.supplychainmanagement.repository.DistributorRuleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Which carriers the rules name: the ranking of the matches. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceDistributorMappingTest {

    private static final DistributorQueryRequest ANY = new DistributorQueryRequest(null, null, null);

    @Mock
    private DistributorRuleRepository distributorRuleRepository;

    @InjectMocks
    private UserServiceImpl service;

    private static Distributor distributor(long id) {
        Distributor distributor = new Distributor();
        distributor.setId(id);
        return distributor;
    }

    /** A rule of the carrier; the customer and the two ranges are what makes it specific. */
    private static DistributorRule rule(long id, long distributorId, int priority, boolean customer, boolean weight, boolean volume) {
        DistributorRule rule = new DistributorRule();
        rule.setId(id);
        rule.setDistributor(distributor(distributorId));
        rule.setPriority(priority);
        if (customer) {
            rule.setCustomer(new Customer());
        }
        if (weight) {
            rule.setMinWeight(BigDecimal.TEN);
        }
        if (volume) {
            rule.setMaxVolume(BigDecimal.valueOf(1000));
        }
        return rule;
    }

    private void matches(DistributorRule... rules) {
        when(distributorRuleRepository.findMatching(any(), any(), any())).thenReturn(List.of(rules));
    }

    private static List<Long> ids(List<Distributor> distributors) {
        return distributors.stream().map(Distributor::getId).toList();
    }

    @Test
    void passesCustomerWeightAndVolumeToTheQuery() {
        matches();
        service.queryDistributors(new DistributorQueryRequest(311L, BigDecimal.valueOf(60), BigDecimal.valueOf(500)));

        verify(distributorRuleRepository).findMatching(311L, BigDecimal.valueOf(60), BigDecimal.valueOf(500));
    }

    @Test
    void theRuleThatSetsMoreDimensionsLeadsWhateverThePriority() {
        matches(
                rule(1, 10, 99, false, false, false),
                rule(2, 20, 0, true, true, false),
                rule(3, 30, 0, true, false, false));

        assertThat(ids(service.queryDistributors(ANY))).containsExactly(20L, 30L, 10L);
    }

    @Test
    void thePriorityDecidesBetweenEquallySpecificRulesAndTheOlderRuleBetweenEqualPriorities() {
        matches(
                rule(5, 50, 1, true, false, false),
                rule(4, 40, 2, true, false, false),
                rule(3, 30, 1, true, false, false));

        assertThat(ids(service.queryDistributors(ANY))).containsExactly(40L, 30L, 50L);
    }

    @Test
    void aVolumeRangeCountsAsADimensionLikeTheOthers() {
        matches(
                rule(1, 10, 0, false, true, false),
                rule(2, 20, 0, false, true, true));

        assertThat(ids(service.queryDistributors(ANY))).containsExactly(20L, 10L);
    }

    @Test
    void aCarrierNamedByTwoRulesAppearsOnceAtItsBestPlace() {
        matches(
                rule(1, 10, 0, false, false, false),
                rule(2, 20, 0, true, false, false),
                rule(3, 10, 0, true, true, false));

        assertThat(ids(service.queryDistributors(ANY))).containsExactly(10L, 20L);
    }

    @Test
    void eachCarrierKnowsTheBestRuleThatNamedIt() {
        matches(
                rule(1, 10, 0, false, false, false),
                rule(2, 20, 0, true, false, false),
                rule(3, 10, 0, true, true, false));

        List<Distributor> found = service.queryDistributors(ANY);

        assertThat(found).extracting(Distributor::getId).containsExactly(10L, 20L);
        assertThat(found).extracting(Distributor::getRuleId).containsExactly(3L, 2L);
    }

    @Test
    void noRuleMeansNoCarrier() {
        matches();
        assertThat(service.queryDistributors(ANY)).isEmpty();
    }
}
