package com.supplychainmanagement.dto.mapper;

import com.supplychainmanagement.dto.component.RequestComponentResponseDto;
import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.RequestComponent;
import com.supplychainmanagement.model.enums.RequestStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code GET /components/my-requests} answers, at the mapping level.
 * <p>
 * Two things used to go wrong here, both silently. The DTO carried the {@code Component}
 * <strong>entity</strong> in a field, so the response serialized the component's product along and
 * resolved every LAZY reference as the writer touched it; and its {@code qty} was an
 * {@code Integer} against the entity's {@code Long}, copied from the component's
 * bill-of-materials quantity that sits in the same response under the same name - MapStruct
 * narrowed the ordered quantity without a word, and nothing failed.
 */
class RequestComponentMapperTest {

    private static final UUID SKU = UUID.fromString("706a99c3-944b-11f1-9b51-001e064520d8");

    private final RequestComponentMapper mapper = new RequestComponentMapperImpl();

    /** A request over the given quantity, for a component that needs 4 per product. */
    private static RequestComponent request(Long orderedQty) {
        Product product = new Product();
        product.setArticleNo(900L);
        product.setName("Regal");

        Component component = new Component();
        component.setId(11L);
        component.setSku(SKU);
        component.setName("Blech");
        component.setArticleNo("A-11");
        component.setQty(4);
        component.setProduct(product);

        RequestComponent request = new RequestComponent();
        request.setId(5L);
        request.setComponent(component);
        request.setQty(orderedQty);
        request.setRequestStatus(RequestStatus.APPROVED);
        return request;
    }

    /** The component arrives as a DTO - no entity anywhere in the answer. */
    @Test
    void mapsTheComponentToADtoAndNotTheEntity() {
        RequestComponentResponseDto dto = mapper.mapToDto(request(12L));

        assertThat(dto.component()).isNotNull().satisfies(component -> {
            assertThat(component.sku()).isEqualTo(SKU.toString());
            assertThat(component.name()).isEqualTo("Blech");
            // The product is a reference of two fields, not the Product entity with its own graph.
            assertThat(component.product().articleNo()).isEqualTo(900L);
            assertThat(component.product().name()).isEqualTo("Regal");
        });
    }

    /**
     * The ordered quantity survives the whole long range. The column behind it is a bigint, so this
     * is a value a request can really hold.
     */
    @Test
    void keepsAnOrderedQuantityBeyondTheIntRange() {
        RequestComponentResponseDto dto = mapper.mapToDto(request(3_000_000_000L));

        assertThat(dto.qty()).isEqualTo(3_000_000_000L);
    }

    /**
     * The two quantities in one response stay apart: the request's is what was ordered, the
     * component's is how many go into one product. Mixing them up is what made the outer one an
     * Integer in the first place.
     */
    @Test
    void keepsTheOrderedQuantityApartFromTheRecipeQuantity() {
        RequestComponentResponseDto dto = mapper.mapToDto(request(12L));

        assertThat(dto.qty()).isEqualTo(12L);
        assertThat(dto.component().qty()).isEqualTo(4);
    }

    /** A request without a component maps to a row without one, not to a failure. */
    @Test
    void mapsARequestWithoutAComponent() {
        RequestComponent request = new RequestComponent();
        request.setId(5L);
        request.setQty(1L);

        RequestComponentResponseDto dto = mapper.mapToDto(request);

        assertThat(dto.component()).isNull();
        assertThat(dto.qty()).isEqualTo(1L);
    }
}
