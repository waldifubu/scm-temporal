package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.users.Supplier;
import com.supplychainmanagement.repository.SupplierRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The supplier list the ordering side picks from.
 * <p>
 * The warehouse may place a component request but cannot read {@code /users}, so it had no way to
 * learn a supplier's id. What it gets is deliberately small: an id and a name.
 */
@ExtendWith(MockitoExtension.class)
class ComponentServiceSuppliersTest {

    @Mock
    private SupplierRepository supplierRepository;

    // The other collaborators of ComponentServiceImpl are not used here and arrive as null, which
    // Mockito's constructor injection allows.
    @InjectMocks
    private ComponentServiceImpl service;

    private static Supplier supplier(Long id, String first, String last) {
        Supplier supplier = new Supplier();
        supplier.setId(id);
        supplier.setFirstName(first);
        supplier.setLastName(last);
        supplier.setEmail("private-" + id + "@example.invalid");
        supplier.setUsername("login-" + id);
        return supplier;
    }

    @Test
    void answersIdAndNameAndKeepsThePaging() {
        PageRequest pageable = PageRequest.of(1, 5);
        when(supplierRepository.findAllByIsActiveTrue(pageable))
                .thenReturn(new PageImpl<>(List.of(supplier(12L, "Sam", "Supply")), pageable, 11));

        var page = service.findSuppliers(pageable);

        assertThat(page.getTotalElements()).isEqualTo(11);
        assertThat(page.getContent()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(12L);
            assertThat(row.name()).isEqualTo("Sam Supply");
        });
        verify(supplierRepository).findAllByIsActiveTrue(pageable);
    }

    /**
     * The name is the one the request response uses for the same person - first and last, and the user
     * name only when there is neither. One rule, not two, so a supplier is called the same everywhere.
     */
    @Test
    void namesASupplierWithoutANameByTheirUserName() {
        PageRequest pageable = PageRequest.of(0, 25);
        Supplier nameless = supplier(13L, " ", null);
        when(supplierRepository.findAllByIsActiveTrue(pageable))
                .thenReturn(new PageImpl<>(List.of(nameless), pageable, 1));

        assertThat(service.findSuppliers(pageable).getContent())
                .singleElement().satisfies(row -> assertThat(row.name()).isEqualTo("login-13"));
    }

    /** What the row cannot say, it does not carry: no address, no login, no roles. */
    @Test
    void carriesNothingBeyondIdAndName() {
        assertThat(com.supplychainmanagement.dto.component.SupplierResponse.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("id", "name");
    }
}
