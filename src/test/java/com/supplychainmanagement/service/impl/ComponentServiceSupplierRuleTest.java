package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.component.RequestComponentResponse;
import com.supplychainmanagement.dto.component.RequestComponentsRequest;
import com.supplychainmanagement.dto.component.SupplierResolution;
import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.RequestComponent;
import com.supplychainmanagement.entity.SupplierRule;
import com.supplychainmanagement.entity.users.Supplier;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.repository.ComponentRepository;
import com.supplychainmanagement.repository.RequestComponentRepository;
import com.supplychainmanagement.repository.SupplierRuleRepository;
import com.supplychainmanagement.repository.UserRepository;
import com.supplychainmanagement.service.RoleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ordering components without naming the supplier: the supplier rules give it. The winning rule is
 * the most specific one, then the higher priority, then the lower id; a component no rule covers
 * refuses the whole request.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ComponentServiceSupplierRuleTest {

    private static final Long ORDERED_BY = 77L;
    private static final UUID SCREW = UUID.fromString("706a99c3-944b-11f1-9b51-001e064520d8");
    private static final UUID PLANK = UUID.fromString("806a99c3-944b-11f1-9b51-001e064520d8");
    private static final UUID GLUE = UUID.fromString("906a99c3-944b-11f1-9b51-001e064520d8");

    @Mock
    private ComponentRepository componentRepository;
    @Mock
    private RequestComponentRepository requestComponentRepository;
    @Mock
    private SupplierRuleRepository supplierRuleRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleService roleService;

    @InjectMocks
    private ComponentServiceImpl service;

    private static Product product(long id) {
        Product product = new Product();
        product.setId(id);
        return product;
    }

    private static Component component(long id, UUID sku, Product product) {
        Component component = new Component();
        component.setId(id);
        component.setSku(sku);
        component.setProduct(product);
        return component;
    }

    private static Supplier supplier(long id, String lastName) {
        Supplier supplier = new Supplier();
        supplier.setId(id);
        supplier.setFirstName("S");
        supplier.setLastName(lastName);
        return supplier;
    }

    private static SupplierRule rule(long id, Component component, Product product, Supplier supplier, int priority) {
        SupplierRule rule = new SupplierRule();
        rule.setId(id);
        rule.setComponent(component);
        rule.setProduct(product);
        rule.setSupplier(supplier);
        rule.setPriority(priority);
        return rule;
    }

    private void known(Component... components) {
        when(componentRepository.findBySkuIn(any())).thenReturn(List.of(components));
    }

    private void rules(SupplierRule... rules) {
        when(supplierRuleRepository.findAllWithActiveSupplier()).thenReturn(List.of(rules));
    }

    private static RequestComponentsRequest request(UUID... skus) {
        return new RequestComponentsRequest(java.util.Arrays.stream(skus)
                .map(sku -> new RequestComponentsRequest.Item(sku, 3L, null)).toList());
    }

    private void savesWhatItIsGiven() {
        when(requestComponentRepository.saveAll(anyList())).thenAnswer(call -> {
            List<RequestComponent> rows = call.getArgument(0);
            rows.forEach(RequestComponent::prePersist);
            return rows;
        });
    }

    @Test
    void sendsEachLineToTheSupplierItsRuleNames() {
        Product wood = product(1);
        Product metal = product(2);
        Component screw = component(5, SCREW, metal);
        Component plank = component(6, PLANK, wood);
        Supplier forWood = supplier(315, "Wood");
        Supplier forMetal = supplier(316, "Metal");
        known(screw, plank);
        rules(rule(1, null, wood, forWood, 0), rule(2, null, metal, forMetal, 0));
        savesWhatItIsGiven();

        List<RequestComponentResponse> placed = service.requestComponents(request(SCREW, PLANK), ORDERED_BY);

        assertThat(placed).hasSize(2);
        // The answer names the supplier, not only its id.
        assertThat(placed).extracting(RequestComponentResponse::supplierId, r -> r.supplierName().contains("Metal") || r.supplierName().contains("Wood"))
                .containsExactly(org.assertj.core.api.Assertions.tuple(316L, true), org.assertj.core.api.Assertions.tuple(315L, true));
        ArgumentCaptor<List<RequestComponent>> captor = ArgumentCaptor.forClass(List.class);
        verify(requestComponentRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(row -> row.getSupplier().getId()).containsExactly(316L, 315L);
    }

    @Test
    void aRuleForTheComponentBeatsARuleForItsProduct() {
        Product metal = product(2);
        Component screw = component(5, SCREW, metal);
        known(screw);
        // The product rule comes first, has the higher priority and the lower id - and still loses.
        rules(rule(1, null, metal, supplier(316, "Metal"), 9), rule(2, screw, null, supplier(317, "Screws"), 0));

        List<SupplierResolution> resolved = service.resolveSuppliers(List.of(SCREW));

        assertThat(resolved).singleElement().satisfies(r -> {
            assertThat(r.supplierId()).isEqualTo(317L);
            assertThat(r.ruleId()).isEqualTo(2L);
        });
    }

    @Test
    void priorityBreaksATieBetweenEquallySpecificRules() {
        Product metal = product(2);
        known(component(5, SCREW, metal));
        rules(rule(1, null, metal, supplier(316, "Low"), 1), rule(2, null, metal, supplier(317, "High"), 5));

        assertThat(service.resolveSuppliers(List.of(SCREW))).singleElement()
                .extracting(SupplierResolution::supplierId).isEqualTo(317L);
    }

    @Test
    void theLowerIdWinsWhenSpecificityAndPriorityAreEqual() {
        Product metal = product(2);
        known(component(5, SCREW, metal));
        rules(rule(8, null, metal, supplier(317, "Later"), 2), rule(3, null, metal, supplier(316, "Earlier"), 2));

        assertThat(service.resolveSuppliers(List.of(SCREW))).singleElement()
                .extracting(SupplierResolution::supplierId).isEqualTo(316L);
    }

    @Test
    void aRuleForAnotherProductOrComponentDoesNotApply() {
        Product metal = product(2);
        Component screw = component(5, SCREW, metal);
        Component other = component(6, PLANK, product(1));
        known(screw);
        rules(rule(1, other, null, supplier(316, "Other"), 0), rule(2, null, product(1), supplier(317, "Wood"), 0));

        assertThat(service.resolveSuppliers(List.of(SCREW))).singleElement().satisfies(r -> {
            assertThat(r.supplierId()).isNull();
            assertThat(r.supplierName()).isNull();
            assertThat(r.ruleId()).isNull();
        });
    }

    @Test
    void aCatchAllRuleCoversEverythingButRanksLast() {
        Product metal = product(2);
        known(component(5, SCREW, metal));
        rules(rule(1, null, null, supplier(399, "Any"), 100), rule(2, null, metal, supplier(316, "Metal"), 0));

        assertThat(service.resolveSuppliers(List.of(SCREW))).singleElement()
                .extracting(SupplierResolution::supplierId).isEqualTo(316L);
    }

    @Test
    void refusesWithConflictNamingEveryUncoveredComponentAndPlacesNothing() {
        Product metal = product(2);
        known(component(5, SCREW, metal), component(6, PLANK, product(1)), component(7, GLUE, product(3)));
        rules(rule(1, null, metal, supplier(316, "Metal"), 0));

        Throwable thrown = catchThrowable(() -> service.requestComponents(request(SCREW, PLANK, GLUE), ORDERED_BY));

        assertThat(thrown).isInstanceOf(APIException.class);
        APIException failure = (APIException) thrown;
        assertThat(failure.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(failure.getMessage()).contains(PLANK.toString(), GLUE.toString()).doesNotContain(SCREW.toString());
        verify(requestComponentRepository, never()).saveAll(anyList());
    }

    @Test
    void anUnknownSkuIsNotFoundAndPlacesNothing() {
        known(component(5, SCREW, product(2)));
        rules(rule(1, null, null, supplier(316, "Any"), 0));

        Throwable thrown = catchThrowable(() -> service.requestComponents(request(SCREW, PLANK), ORDERED_BY));

        assertThat(thrown).isInstanceOf(APIException.class);
        assertThat(((APIException) thrown).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(thrown.getMessage()).contains(PLANK.toString());
        verify(requestComponentRepository, never()).saveAll(anyList());
    }

    @Test
    void resolvingAnUnknownSkuIsNotFound() {
        known();
        rules();

        Throwable thrown = catchThrowable(() -> service.resolveSuppliers(List.of(SCREW)));

        assertThat(thrown).isInstanceOf(APIException.class);
        assertThat(((APIException) thrown).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aRepeatedSkuIsResolvedOnce() {
        Product metal = product(2);
        known(component(5, SCREW, metal));
        rules(rule(1, null, metal, supplier(316, "Metal"), 0));

        assertThat(service.resolveSuppliers(List.of(SCREW, SCREW))).hasSize(1);
    }
}
