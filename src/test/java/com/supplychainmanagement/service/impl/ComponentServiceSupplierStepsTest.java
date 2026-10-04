package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.component.RequestComponentResponse;
import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.entity.RequestComponent;
import com.supplychainmanagement.entity.users.Supplier;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;

/**
 * The supplier's two steps on a component request: accepting it and reporting it sent. One status at
 * a time, and only on a request that was placed with them.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ComponentServiceSupplierStepsTest {

    private static final Long REQUEST_ID = 5L;
    private static final Long SUPPLIER_ID = 315L;
    private static final Long OTHER_SUPPLIER_ID = 316L;
    private static final UUID SKU = UUID.fromString("706ea4bd-944b-11f1-9b51-001e064520d8");

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

    /** The request as the locking read hands it back, placed with SUPPLIER_ID. */
    private RequestComponent request(RequestStatus status) {
        Supplier supplier = new Supplier();
        supplier.setId(SUPPLIER_ID);

        Component component = new Component();
        component.setId(11L);
        component.setSku(SKU);
        component.setName("Blech auÃŸen");

        RequestComponent request = new RequestComponent();
        request.setId(REQUEST_ID);
        request.setSupplier(supplier);
        request.setComponent(component);
        request.setQty(12L);
        request.setRequestStatus(status);

        when(requestComponentRepository.findForUpdateById(REQUEST_ID)).thenReturn(Optional.of(request));
        return request;
    }

    private static void assertStatus(Throwable thrown, HttpStatus status) {
        assertThat(thrown).isInstanceOfSatisfying(APIException.class, e -> assertThat(e.getStatus()).isEqualTo(status));
    }

    // ------------------------------------------------------------------ accepting

    @Test
    void approveTakesAnOpenRequestToApproved() {
        RequestComponent request = request(RequestStatus.OPEN);

        RequestComponentResponse response = service.approveRequest(REQUEST_ID, SUPPLIER_ID);

        assertThat(request.getRequestStatus()).isEqualTo(RequestStatus.APPROVED);
        assertThat(response.requestStatus()).isEqualTo(RequestStatus.APPROVED);
        assertThat(response.componentId()).isEqualTo(SKU);
        assertThat(response.qty()).isEqualTo(12L);
    }

    /** One step at a time: anything but OPEN is a 409, a second accept included. */
    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = {"APPROVED", "IN_TRANSIT", "DELIVERED", "IN_STOCK"})
    void approvesAnOpenRequestOnly(RequestStatus status) {
        RequestComponent request = request(status);

        assertStatus(catchThrowable(() -> service.approveRequest(REQUEST_ID, SUPPLIER_ID)), HttpStatus.CONFLICT);

        assertThat(request.getRequestStatus()).isEqualTo(status);
    }

    // ------------------------------------------------------------------ sending

    @Test
    void inTransitTakesAnApprovedRequestOnTheRoad() {
        RequestComponent request = request(RequestStatus.APPROVED);

        RequestComponentResponse response = service.requestInTransit(REQUEST_ID, SUPPLIER_ID);

        assertThat(request.getRequestStatus()).isEqualTo(RequestStatus.IN_TRANSIT);
        assertThat(response.requestStatus()).isEqualTo(RequestStatus.IN_TRANSIT);
    }

    /** Not from OPEN either - the supplier accepts first and sends second. */
    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = {"OPEN", "IN_TRANSIT", "DELIVERED", "IN_STOCK"})
    void sendsAnApprovedRequestOnly(RequestStatus status) {
        RequestComponent request = request(status);

        assertStatus(catchThrowable(() -> service.requestInTransit(REQUEST_ID, SUPPLIER_ID)), HttpStatus.CONFLICT);

        assertThat(request.getRequestStatus()).isEqualTo(status);
    }

    // ------------------------------------------------------------------ handing over

    /** The supplier's last step: the goods are at our dock. Nothing is booked by it. */
    @Test
    void deliveredTakesAnInTransitRequestToDelivered() {
        RequestComponent request = request(RequestStatus.IN_TRANSIT);

        RequestComponentResponse response = service.requestDelivered(REQUEST_ID, SUPPLIER_ID);

        assertThat(request.getRequestStatus()).isEqualTo(RequestStatus.DELIVERED);
        assertThat(response.requestStatus()).isEqualTo(RequestStatus.DELIVERED);
    }

    /** Not from APPROVED either - sent first, handed over second. */
    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = {"OPEN", "APPROVED", "DELIVERED", "IN_STOCK"})
    void handsOverASentRequestOnly(RequestStatus status) {
        RequestComponent request = request(status);

        assertStatus(catchThrowable(() -> service.requestDelivered(REQUEST_ID, SUPPLIER_ID)),
                HttpStatus.CONFLICT);

        assertThat(request.getRequestStatus()).isEqualTo(status);
    }

    /**
     * The escape hatch for a silent supplier: an ADMIN reports the handover for them, so a strict
     * DELIVERED gate on the goods receipt never blocks the warehouse for good.
     */
    @Test
    void letsAnAdminReportTheHandoverForASilentSupplier() {
        RequestComponent request = request(RequestStatus.IN_TRANSIT);
        when(roleService.isAdmin(1L)).thenReturn(true);

        service.requestDelivered(REQUEST_ID, 1L);

        assertThat(request.getRequestStatus()).isEqualTo(RequestStatus.DELIVERED);
    }

    // ------------------------------------------------------------------ whose request it is

    /** Another supplier does not answer somebody else's request. */
    @Test
    void refusesASupplierTheRequestWasNotPlacedWith() {
        RequestComponent request = request(RequestStatus.OPEN);

        Throwable thrown = catchThrowable(() -> service.approveRequest(REQUEST_ID, OTHER_SUPPLIER_ID));

        assertStatus(thrown, HttpStatus.FORBIDDEN);
        assertThat(thrown).hasMessageContaining("another supplier");
        assertThat(request.getRequestStatus()).isEqualTo(RequestStatus.OPEN);
    }

    /** ADMIN answers any of them - the role that has to be able to correct things. */
    @Test
    void letsAnAdminAnswerAnyRequest() {
        RequestComponent request = request(RequestStatus.OPEN);
        when(roleService.isAdmin(1L)).thenReturn(true);

        service.approveRequest(REQUEST_ID, 1L);

        assertThat(request.getRequestStatus()).isEqualTo(RequestStatus.APPROVED);
    }

    /**
     * Asked before the status, so a supplier poking at a request that is not theirs learns nothing
     * about where it stands.
     */
    @Test
    void doesNotTellAnotherSupplierWhatStatusItIsIn() {
        request(RequestStatus.IN_STOCK);

        assertStatus(catchThrowable(() -> service.approveRequest(REQUEST_ID, OTHER_SUPPLIER_ID)),
                HttpStatus.FORBIDDEN);
    }

    @Test
    void answersAnUnknownRequestWith404() {
        when(requestComponentRepository.findForUpdateById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approveRequest(99L, SUPPLIER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
