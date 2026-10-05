package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.component.ComponentRequestDto;
import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.repository.ComponentRepository;
import com.supplychainmanagement.repository.ProductRepository;
import com.supplychainmanagement.repository.RequestComponentRepository;
import com.supplychainmanagement.repository.UserRepository;
import com.supplychainmanagement.service.RoleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Creating and changing a component through {@code ComponentRequestDto}.
 * <p>
 * The endpoint bound the {@code Component} entity, so a request could carry an {@code id} - and
 * {@code create} handed the bound object to {@code save()}, which with an id present is a merge over
 * whichever component already had it. The DTO has no id field, so that is closed by construction;
 * what is held here is that the service really builds its own entity, and that the product now
 * arrives as an {@code articleNo} rather than as a nested object of which one field was read.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ComponentServiceRequestDtoTest {

    private static final Long COMPONENT_ID = 11L;
    private static final Long ARTICLE_NO = 900L;
    private static final UUID SKU = UUID.fromString("706a99c3-944b-11f1-9b51-001e064520d8");

    @Mock
    private ComponentRepository componentRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private RequestComponentRepository requestComponentRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleService roleService;

    @InjectMocks
    private ComponentServiceImpl service;

    private Product knownProduct() {
        Product product = new Product();
        product.setId(7L);
        product.setArticleNo(ARTICLE_NO);
        when(productRepository.findByArticleNo(ARTICLE_NO)).thenReturn(Optional.of(product));
        when(componentRepository.save(any(Component.class))).thenAnswer(call -> call.getArgument(0));
        return product;
    }

    private static ComponentRequestDto request(UUID sku) {
        return new ComponentRequestDto(ARTICLE_NO, "Blech", "ACME", "A-11", "ein Blech",
                new BigDecimal("2.5"), "EXT-11", sku, 4);
    }

    private static void assertStatus(Throwable thrown, HttpStatus status) {
        assertThat(thrown).isInstanceOfSatisfying(APIException.class,
                e -> assertThat(e.getStatus()).isEqualTo(status));
    }

    /** Every field the DTO carries lands on a fresh entity, and the product is the resolved one. */
    @Test
    void createsAComponentWithItsOwnEntity() {
        Product product = knownProduct();

        Component created = service.create(request(SKU));

        assertThat(created.getId()).isNull();
        assertThat(created.getProduct()).isSameAs(product);
        assertThat(created.getName()).isEqualTo("Blech");
        assertThat(created.getManufacturer()).isEqualTo("ACME");
        assertThat(created.getArticleNo()).isEqualTo("A-11");
        assertThat(created.getDescription()).isEqualTo("ein Blech");
        assertThat(created.getExternalId()).isEqualTo("EXT-11");
        assertThat(created.getWeight()).isEqualByComparingTo("2.5");
        assertThat(created.getSku()).isEqualTo(SKU);
        assertThat(created.getQty()).isEqualTo(4);
    }

    /** The product is named by articleNo and looked up - an unknown one is a 404. */
    @Test
    void answersAnUnknownProductWith404() {
        when(productRepository.findByArticleNo(ARTICLE_NO)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request(SKU)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(componentRepository, never()).save(any());
    }

    /** A SKU another component already carries is a 409, before anything is written. */
    @Test
    void refusesADuplicateSku() {
        knownProduct();
        when(componentRepository.existsBySku(SKU)).thenReturn(true);

        assertStatus(catchThrowable(() -> service.create(request(SKU))), HttpStatus.CONFLICT);

        verify(componentRepository, never()).save(any());
    }

    /** No SKU sent means the entity keeps what it has - on create, what @PrePersist gives it. */
    @Test
    void leavesTheSkuAloneWhenNoneIsSent() {
        knownProduct();

        Component created = service.create(request(null));

        assertThat(created.getSku()).isNull();
        verify(componentRepository, never()).existsBySku(any());
    }

    /**
     * Update writes the same set of fields as create. It used to copy a smaller selection -
     * description, weight, articleNo and qty could be set on create but never changed afterwards,
     * and qty is the recipe quantity.
     */
    @Test
    void updateWritesTheSameFieldsAsCreate() {
        Product product = knownProduct();
        Component existing = new Component();
        existing.setId(COMPONENT_ID);
        existing.setName("alt");
        existing.setQty(1);
        when(componentRepository.findById(COMPONENT_ID)).thenReturn(Optional.of(existing));

        service.update(COMPONENT_ID, request(SKU));

        assertThat(existing.getId()).isEqualTo(COMPONENT_ID);
        assertThat(existing.getName()).isEqualTo("Blech");
        assertThat(existing.getDescription()).isEqualTo("ein Blech");
        assertThat(existing.getWeight()).isEqualByComparingTo("2.5");
        assertThat(existing.getQty()).isEqualTo(4);
        assertThat(existing.getProduct()).isSameAs(product);
    }

    /** Keeping your own SKU is not a conflict with yourself. */
    @Test
    void letsAComponentKeepItsOwnSku() {
        knownProduct();
        Component existing = new Component();
        existing.setId(COMPONENT_ID);
        existing.setSku(SKU);
        when(componentRepository.findById(COMPONENT_ID)).thenReturn(Optional.of(existing));
        when(componentRepository.existsBySku(SKU)).thenReturn(true);

        service.update(COMPONENT_ID, request(SKU));

        assertThat(existing.getSku()).isEqualTo(SKU);
    }
}
