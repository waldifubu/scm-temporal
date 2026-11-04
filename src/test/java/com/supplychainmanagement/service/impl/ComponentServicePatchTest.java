package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.component.ComponentPatchRequest;
import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.exception.APIException;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code PATCH /components/{sku}}: only what is sent changes, and only name, manufacturer and qty. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ComponentServicePatchTest {

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

    private Component existing() {
        Component component = new Component();
        component.setId(11L);
        component.setSku(SKU);
        component.setName("Blech");
        component.setManufacturer("ACME");
        component.setArticleNo("A-11");
        component.setDescription("ein Blech");
        component.setWeight(new BigDecimal("12.5"));
        component.setQty(2);
        component.setExternalId("EXT-1");
        when(componentRepository.findBySku(SKU)).thenReturn(Optional.of(component));
        when(componentRepository.save(any(Component.class))).thenAnswer(call -> call.getArgument(0));
        return component;
    }

    @Test
    void changesAllThreeFields() {
        Component component = existing();

        service.patch(SKU, new ComponentPatchRequest("Stahlblech", "Beta", "stark", "EXT-9", new BigDecimal("3.250"), 5));

        assertThat(component.getName()).isEqualTo("Stahlblech");
        assertThat(component.getManufacturer()).isEqualTo("Beta");
        assertThat(component.getDescription()).isEqualTo("stark");
        assertThat(component.getExternalId()).isEqualTo("EXT-9");
        assertThat(component.getWeight()).isEqualByComparingTo("3.25");
        assertThat(component.getQty()).isEqualTo(5);
    }

    @Test
    void leavesWhatIsNotSentAlone() {
        Component component = existing();

        service.patch(SKU, new ComponentPatchRequest(null, null, null, null, null, 9));

        assertThat(component.getQty()).isEqualTo(9);
        assertThat(component.getName()).isEqualTo("Blech");
        assertThat(component.getManufacturer()).isEqualTo("ACME");
        assertThat(component.getExternalId()).isEqualTo("EXT-1");
        assertThat(component.getArticleNo()).isEqualTo("A-11");
        assertThat(component.getDescription()).isEqualTo("ein Blech");
        assertThat(component.getWeight()).isEqualByComparingTo("12.5");
        assertThat(component.getSku()).isEqualTo(SKU);
    }

    @Test
    void anEmptyManufacturerOrDescriptionClearsIt() {
        Component component = existing();

        service.patch(SKU, new ComponentPatchRequest(null, "", "", "", null, null));

        assertThat(component.getManufacturer()).isNull();
        assertThat(component.getDescription()).isNull();
        assertThat(component.getName()).isEqualTo("Blech");
    }

    @Test
    void anEmptyPatchIsA400AndWritesNothing() {
        existing();

        assertThatThrownBy(() -> service.patch(SKU, new ComponentPatchRequest(null, null, null, null, null, null)))
                .isInstanceOfSatisfying(APIException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(componentRepository, never()).save(any());
    }

    @Test
    void anExternalIdOfAnotherComponentIsA409AndWritesNothing() {
        Component component = existing();
        when(componentRepository.existsByExternalId("EXT-2")).thenReturn(true);

        assertThatThrownBy(() -> service.patch(SKU, new ComponentPatchRequest(null, null, null, "EXT-2", null, null)))
                .isInstanceOfSatisfying(APIException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(component.getExternalId()).isEqualTo("EXT-1");
        verify(componentRepository, never()).save(any());
    }

    @Test
    void keepingItsOwnExternalIdIsNoConflictAndABlankOneClearsIt() {
        Component component = existing();
        when(componentRepository.existsByExternalId("EXT-1")).thenReturn(true);

        service.patch(SKU, new ComponentPatchRequest(null, null, null, "EXT-1", null, null));
        assertThat(component.getExternalId()).isEqualTo("EXT-1");

        service.patch(SKU, new ComponentPatchRequest(null, null, null, " ", null, null));
        assertThat(component.getExternalId()).isNull();
    }

    @Test
    void anUnknownSkuIsA404() {
        when(componentRepository.findBySku(SKU)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.patch(SKU, new ComponentPatchRequest("x", null, null, null, null, null)))
                .isInstanceOfSatisfying(APIException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
