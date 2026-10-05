package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.product.ProductRequestDto;
import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.ProductCategory;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.repository.ProductCategoryRepository;
import com.supplychainmanagement.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a product request may and may not do - the reason {@code ProductRequestDto} exists.
 * <p>
 * The endpoint bound the {@code Product} entity, and two of its fields were reachable that should
 * not have been. An {@code id} made {@code create}'s {@code save()} a merge, so a POST overwrote an
 * existing product. And a component line carrying the id of <em>another</em> product's component was
 * reassigned to this product by the {@code cascade = ALL} on {@code Product.components} - a foreign
 * bill of materials rewritten, with nothing in the request saying so.
 * <p>
 * The first is closed by construction: the DTO has no id field at all, so there is nothing to test
 * but that the service builds its own entity. The second needs a check, and that is what most of
 * these are about.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductServiceRequestDtoTest {

    private static final Long PRODUCT_ID = 7L;
    private static final Long ARTICLE_NO = 900L;

    @Mock
    private ProductRepository productRepository;
    @Mock
    private ProductCategoryRepository productCategoryRepository;

    @InjectMocks
    private ProductServiceImpl service;

    private static ProductRequestDto request(ProductRequestDto.ComponentLine... lines) {
        return new ProductRequestDto(ARTICLE_NO, "Regal", "ein Regal", BigDecimal.TEN,
                BigDecimal.ONE, null, null, null, lines.length == 0 ? null : List.of(lines));
    }

    private static ProductRequestDto.ComponentLine line(Long id, String name) {
        return new ProductRequestDto.ComponentLine(id, name, null, null, null, null, null, null, 4);
    }

    /** An existing product with one component line of its own. */
    private Product existingWith(Long componentId) {
        Product product = new Product();
        product.setId(PRODUCT_ID);
        product.setArticleNo(ARTICLE_NO);

        List<Component> components = new ArrayList<>();
        if (componentId != null) {
            Component own = new Component();
            own.setId(componentId);
            own.setName("Blech");
            own.setProduct(product);
            components.add(own);
        }
        product.setComponents(components);

        when(productRepository.findWithComponentsById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(call -> call.getArgument(0));
        return product;
    }

    private void savesNewProduct() {
        when(productRepository.save(any(Product.class))).thenAnswer(call -> {
            Product saved = call.getArgument(0);
            saved.setId(PRODUCT_ID);
            when(productRepository.findWithComponentsById(PRODUCT_ID)).thenReturn(Optional.of(saved));
            return saved;
        });
    }

    private static void assertStatus(Throwable thrown, HttpStatus status) {
        assertThat(thrown).isInstanceOfSatisfying(APIException.class,
                e -> assertThat(e.getStatus()).isEqualTo(status));
    }

    // ------------------------------------------------------------------ creating

    /** The service builds its own entity, so nothing a client sends can become a merge target. */
    @Test
    void createsAProductWithItsOwnEntity() {
        savesNewProduct();

        Product created = service.create(request(line(null, "Blech")));

        assertThat(created.getArticleNo()).isEqualTo(ARTICLE_NO);
        assertThat(created.getName()).isEqualTo("Regal");
        assertThat(created.getComponents()).singleElement().satisfies(component -> {
            assertThat(component.getId()).isNull();
            assertThat(component.getName()).isEqualTo("Blech");
            assertThat(component.getQty()).isEqualTo(4);
            assertThat(component.getProduct()).isSameAs(created);
        });
    }

    /**
     * A component id on create is refused: a product that does not exist yet has no lines of its
     * own, so any id would be somebody else's - which is exactly the row the cascade used to move.
     */
    @Test
    void refusesAComponentIdOnCreate() {
        savesNewProduct();

        assertStatus(catchThrowable(() -> service.create(request(line(11L, "Blech")))),
                HttpStatus.BAD_REQUEST);

        verify(productRepository, never()).save(any());
    }

    @Test
    void refusesADuplicateArticleNumber() {
        when(productRepository.existsByArticleNo(ARTICLE_NO)).thenReturn(true);

        assertStatus(catchThrowable(() -> service.create(request())), HttpStatus.CONFLICT);
    }

    // ------------------------------------------------------------------ updating

    /** An id that is one of the product's own lines changes that line instead of adding one. */
    @Test
    void updatesTheProductsOwnComponentLine() {
        Product product = existingWith(11L);

        service.update(PRODUCT_ID, request(line(11L, "Blech verzinkt")));

        assertThat(product.getComponents()).singleElement().satisfies(component -> {
            assertThat(component.getId()).isEqualTo(11L);
            assertThat(component.getName()).isEqualTo("Blech verzinkt");
        });
    }

    /**
     * The one that matters: an id belonging to another product is a 400, not a silent reassignment.
     * The cascade would have pulled that row over and left the other product short a line.
     */
    @Test
    void refusesAComponentOfAnotherProduct() {
        Product product = existingWith(11L);

        assertStatus(catchThrowable(() -> service.update(PRODUCT_ID, request(line(99L, "geklaut")))),
                HttpStatus.BAD_REQUEST);

        assertThat(product.getComponents()).singleElement()
                .satisfies(component -> assertThat(component.getId()).isEqualTo(11L));
        verify(productRepository, never()).save(any());
    }

    /** A line without an id is added to the product it was sent for. */
    @Test
    void addsALineWithoutAnId() {
        Product product = existingWith(11L);

        service.update(PRODUCT_ID, request(line(11L, "Blech"), line(null, "Schraube")));

        assertThat(product.getComponents()).hasSize(2)
                .allSatisfy(component -> assertThat(component.getProduct()).isSameAs(product));
    }

    /** No components in the request means the bill of materials is left alone, not emptied. */
    @Test
    void leavesTheComponentsAloneWhenNoneAreSent() {
        Product product = existingWith(11L);

        service.update(PRODUCT_ID, request());

        assertThat(product.getComponents()).singleElement()
                .satisfies(component -> assertThat(component.getName()).isEqualTo("Blech"));
    }

    /**
     * The recipe quantity of an existing line. applyComponents copies articleNo, name, description,
     * weight and sku onto a matched line and says nothing about qty - so the question is whether the
     * value set in componentsFor survives that merge, or whether PUT /products silently ignores it.
     */
    @Test
    void changesTheRecipeQuantityOfAnExistingLine() {
        Product product = existingWith(11L);
        product.getComponents().getFirst().setQty(1);

        service.update(PRODUCT_ID, request(line(11L, "Blech")));

        assertThat(product.getComponents()).singleElement()
                .satisfies(component -> assertThat(component.getQty()).isEqualTo(4));
    }

    // ------------------------------------------------------------------ categories

    /** Categories are looked up by id, never taken from the body. */
    @Test
    void resolvesCategoriesById() {
        Product product = existingWith(null);
        ProductCategory category = new ProductCategory();
        category.setId(3L);
        when(productCategoryRepository.findAllById(Set.of(3L))).thenReturn(List.of(category));

        service.update(PRODUCT_ID, new ProductRequestDto(ARTICLE_NO, "Regal", null, null, null,
                null, null, Set.of(3L), null));

        assertThat(product.getCategories()).containsExactly(category);
    }

    /** An unknown category id is a 404 naming it, rather than an invented category being attached. */
    @Test
    void refusesAnUnknownCategory() {
        existingWith(null);
        when(productCategoryRepository.findAllById(Set.of(3L))).thenReturn(List.of());

        Throwable thrown = catchThrowable(() -> service.update(PRODUCT_ID,
                new ProductRequestDto(ARTICLE_NO, "Regal", null, null, null, null, null,
                        Set.of(3L), null)));

        assertStatus(thrown, HttpStatus.NOT_FOUND);
        assertThat(thrown).hasMessageContaining("3");
    }
}
