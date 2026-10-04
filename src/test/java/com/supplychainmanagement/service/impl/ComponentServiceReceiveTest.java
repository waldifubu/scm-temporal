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
import com.supplychainmanagement.repository.StorehouseRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The goods receipt: the warehouse books a delivered request in, and the quantity lands in stock.
 * <p>
 * The stock side is the point of the whole step - component stock grew only by hand before, so
 * {@code assemble()} eventually had nothing left to build from.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ComponentServiceReceiveTest {

    private static final Long REQUEST_ID = 5L;
    private static final Long STOREHOUSE_ID = 1L;
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
    @Mock
    private StorehouseRepository storehouseRepository;
    @Mock
    private StockService stockService;

    @InjectMocks
    private ComponentServiceImpl service;

    /** A request in the given status, over the given quantity, with a known storehouse to book into. */
    private RequestComponent request(RequestStatus status, Long qty) {
        Supplier supplier = new Supplier();
        supplier.setId(315L);

        Component component = new Component();
        component.setId(11L);
        component.setSku(SKU);
        component.setName("Blech");
        component.setQty(1);

        RequestComponent request = new RequestComponent();
        request.setId(REQUEST_ID);
        request.setSupplier(supplier);
        request.setComponent(component);
        request.setQty(qty);
        request.setRequestStatus(status);

        when(requestComponentRepository.findForUpdateById(REQUEST_ID)).thenReturn(Optional.of(request));
        when(storehouseRepository.existsById(STOREHOUSE_ID)).thenReturn(true);
        return request;
    }

    private static void assertStatus(Throwable thrown, HttpStatus status) {
        assertThat(thrown).isInstanceOfSatisfying(APIException.class, e -> assertThat(e.getStatus()).isEqualTo(status));
    }

    /** The whole point: the delivered quantity lands on the stock of that SKU in that storehouse. */
    @Test
    void booksTheQuantityIntoTheStockOfThatSkuAndStorehouse() {
        RequestComponent request = request(RequestStatus.DELIVERED, 12L);

        RequestComponentResponse response = service.receiveRequest(REQUEST_ID, STOREHOUSE_ID, 99L);

        verify(stockService).add(SKU, STOREHOUSE_ID, 12);
        assertThat(request.getRequestStatus()).isEqualTo(RequestStatus.IN_STOCK);
        assertThat(response.requestStatus()).isEqualTo(RequestStatus.IN_STOCK);
    }

    /**
     * The quantity goes to the stock, never to Component.qty - that one is the bill of materials, and
     * adding a delivery to it would rewrite the recipe of every product using the part.
     */
    @Test
    void leavesTheRecipeQuantityAlone() {
        RequestComponent request = request(RequestStatus.DELIVERED, 12L);

        service.receiveRequest(REQUEST_ID, STOREHOUSE_ID, 99L);

        assertThat(request.getComponent().getQty()).isEqualTo(1);
    }

    /**
     * Only from DELIVERED: the supplier reports the handover first. IN_TRANSIT is refused on purpose -
     * the goods are on the road, not at the dock.
     */
    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = {"OPEN", "APPROVED", "IN_TRANSIT", "IN_STOCK"})
    void booksInADeliveredRequestOnly(RequestStatus status) {
        RequestComponent request = request(status, 12L);

        assertStatus(catchThrowable(() -> service.receiveRequest(REQUEST_ID, STOREHOUSE_ID, 99L)),
                HttpStatus.CONFLICT);

        assertThat(request.getRequestStatus()).isEqualTo(status);
        verify(stockService, never()).add(any(), any(), any());
    }

    /** A wrong path variable is a 404, not the 500 StockService would have produced. */
    @Test
    void answersAnUnknownStorehouseWith404() {
        RequestComponent request = request(RequestStatus.DELIVERED, 12L);
        when(storehouseRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.receiveRequest(REQUEST_ID, 99L, 99L))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(request.getRequestStatus()).isEqualTo(RequestStatus.DELIVERED);
        verify(stockService, never()).add(any(), any(), any());
    }

    /** Nothing bookable, nothing booked - and the status stays where it is. */
    @Test
    void refusesARequestWithoutAUsableQuantity() {
        RequestComponent request = request(RequestStatus.DELIVERED, 0L);

        assertStatus(catchThrowable(() -> service.receiveRequest(REQUEST_ID, STOREHOUSE_ID, 99L)),
                HttpStatus.CONFLICT);

        assertThat(request.getRequestStatus()).isEqualTo(RequestStatus.DELIVERED);
        verify(stockService, never()).add(any(), any(), any());
    }

    @Test
    void answersAnUnknownRequestWith404() {
        when(requestComponentRepository.findForUpdateById(77L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.receiveRequest(77L, STOREHOUSE_ID, 99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
