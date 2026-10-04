package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.component.RequestComponentResponse;
import com.supplychainmanagement.dto.component.RequestComponentsRequest;
import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.entity.RequestComponent;
import com.supplychainmanagement.entity.users.Customer;
import com.supplychainmanagement.entity.users.Supplier;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.model.enums.RequestStatus;
import com.supplychainmanagement.repository.ComponentRepository;
import com.supplychainmanagement.repository.ProductRepository;
import com.supplychainmanagement.repository.RequestComponentRepository;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ordering components from a supplier: one row per line, the component found by SKU, and nothing
 * placed at all when something about the request does not hold.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ComponentServiceRequestTest {

    private static final Long SUPPLIER_ID = 12L;
    private static final UUID SCREW = UUID.fromString("706a99c3-944b-11f1-9b51-001e064520d8");
    private static final UUID PLANK = UUID.fromString("806a99c3-944b-11f1-9b51-001e064520d8");

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

    private Supplier supplier() {
        Supplier supplier = new Supplier();
        supplier.setId(SUPPLIER_ID);
        supplier.setFirstName("Ada");
        supplier.setLastName("Lovelace");
        when(userRepository.findById(SUPPLIER_ID)).thenReturn(Optional.of(supplier));
        return supplier;
    }

    private static Component component(Long id, UUID sku, String name) {
        Component component = new Component();
        component.setId(id);
        component.setSku(sku);
        component.setName(name);
        return component;
    }

    private void known(Component... components) {
        when(componentRepository.findBySkuIn(any())).thenReturn(List.of(components));
    }

    /** saveAll hands back what it was given, so the response can be read off the entities. */
    private void savesWhatItIsGiven() {
        when(requestComponentRepository.saveAll(anyList())).thenAnswer(call -> call.getArgument(0));
    }

    private static RequestComponentsRequest request(RequestComponentsRequest.Item... items) {
        return new RequestComponentsRequest(List.of(items));
    }

    private static RequestComponentsRequest.Item item(UUID sku, long qty, String comment) {
        return new RequestComponentsRequest.Item(sku, qty, comment);
    }

    @SuppressWarnings("unchecked")
    private List<RequestComponent> saved() {
        ArgumentCaptor<List<RequestComponent>> captor = ArgumentCaptor.forClass(List.class);
        verify(requestComponentRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    /** One line, one row - with the component found by SKU and the status the entity starts in. */
    @Test
    void placesOneRequestPerLine() {
        Supplier supplier = supplier();
        known(component(5L, SCREW, "screw"));
        savesWhatItIsGiven();

        List<RequestComponentResponse> placed =
                service.requestComponents(SUPPLIER_ID, request(item(SCREW, 12, "Notwendig")));

        assertThat(saved()).singleElement().satisfies(row -> {
            assertThat(row.getComponent().getSku()).isEqualTo(SCREW);
            assertThat(row.getSupplier()).isSameAs(supplier);
            assertThat(row.getQty()).isEqualTo(12L);
            assertThat(row.getComment()).isEqualTo("Notwendig");
            assertThat(row.getRequestStatus()).isEqualTo(RequestStatus.OPEN);
        });
        assertThat(placed).singleElement().satisfies(response -> {
            assertThat(response.componentId()).isEqualTo(SCREW);
            assertThat(response.componentName()).isEqualTo("screw");
            assertThat(response.supplierId()).isEqualTo(SUPPLIER_ID);
        });
    }

    /**
     * The same SKU twice stays two rows. Unlike an order line, where a repeated article is folded and
     * the quantities added up, two requests for the same part are two requests - each with its own
     * quantity and its own reason.
     */
    @Test
    void keepsARepeatedSkuAsTwoRequests() {
        supplier();
        known(component(5L, SCREW, "screw"));
        savesWhatItIsGiven();

        service.requestComponents(SUPPLIER_ID,
                request(item(SCREW, 12, "für die Halle"), item(SCREW, 3, "Reserve")));

        assertThat(saved())
                .extracting(RequestComponent::getQty, RequestComponent::getComment)
                .containsExactly(tuple(12L, "für die Halle"), tuple(3L, "Reserve"));
    }

    /** Several lines, one lookup - not one query per line. */
    @Test
    void looksTheComponentsUpInOneQuery() {
        supplier();
        known(component(5L, SCREW, "screw"), component(6L, PLANK, "plank"));
        savesWhatItIsGiven();

        service.requestComponents(SUPPLIER_ID,
                request(item(SCREW, 12, null), item(PLANK, 2, null), item(SCREW, 1, null)));

        verify(componentRepository).findBySkuIn(any());
        verify(componentRepository, never()).findBySku(any());
        assertThat(saved()).hasSize(3);
    }

    /** The comment is optional, and an empty one is no comment - it stays out of the JSON. */
    @Test
    void storesAnEmptyCommentAsNothing() {
        supplier();
        known(component(5L, SCREW, "screw"));
        savesWhatItIsGiven();

        service.requestComponents(SUPPLIER_ID, request(item(SCREW, 1, "   "), item(SCREW, 1, null)));

        assertThat(saved()).extracting(RequestComponent::getComment).containsExactly(null, null);
    }

    /** All or nothing: one unknown SKU places nothing, and the message names every one of them. */
    @Test
    void placesNothingWhenASkuIsUnknown() {
        supplier();
        known(component(5L, SCREW, "screw"));

        Throwable thrown = catchThrowable(() -> service.requestComponents(SUPPLIER_ID,
                request(item(SCREW, 12, null), item(PLANK, 2, null))));

        assertThat(thrown).isInstanceOfSatisfying(APIException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(thrown).hasMessageContaining(PLANK.toString());
        verify(requestComponentRepository, never()).saveAll(anyList());
    }

    @Test
    void answersAnUnknownSupplierWith404() {
        when(userRepository.findById(SUPPLIER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requestComponents(SUPPLIER_ID, request(item(SCREW, 1, null))))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(requestComponentRepository, never()).saveAll(anyList());
    }

    /**
     * Any other user is a 400 - and checked on the unproxied instance, because a cast up front would
     * make it a ClassCastException and with it a 500.
     */
    @Test
    void refusesAUserWhoIsNotASupplier() {
        User customer = new Customer();
        customer.setId(SUPPLIER_ID);
        when(userRepository.findById(SUPPLIER_ID)).thenReturn(Optional.of(customer));

        Throwable thrown = catchThrowable(() -> service.requestComponents(SUPPLIER_ID, request(item(SCREW, 1, null))));

        assertThat(thrown).isInstanceOfSatisfying(APIException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(thrown).hasMessageContaining("is not a supplier");
        verify(requestComponentRepository, never()).saveAll(anyList());
    }
}
