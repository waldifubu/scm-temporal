package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Stock;
import com.supplychainmanagement.entity.Storehouse;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.repository.StockRepository;
import com.supplychainmanagement.repository.StorehouseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Booking stock onto a (sku, storehouse) row - the one write every inbound path ends in.
 * <p>
 * The point of these is <strong>which finder</strong> is asked: the row is read {@code FOR UPDATE},
 * because the quantity is read and written back. It used to be read without a lock and without a
 * transaction at all when the call came from {@code POST /stock/add}, so two concurrent bookings
 * computed from the same quantity - caught by {@code @Version} and answered with a 500 for something
 * the caller had done nothing wrong in.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StockServiceAddTest {

    private static final UUID SKU = UUID.fromString("706a99c3-944b-11f1-9b51-001e064520d8");
    private static final Long STOREHOUSE_ID = 1L;

    @Mock
    private StockRepository stockRepository;
    @Mock
    private StorehouseRepository storehouseRepository;

    @InjectMocks
    private StockService service;

    private Stock existing(int onHand) {
        Stock stock = new Stock();
        stock.setSku(SKU);
        stock.setOnHand(onHand);
        stock.setReserved(0);
        when(stockRepository.findForUpdateByStorehouseIdAndSku(STOREHOUSE_ID, SKU))
                .thenReturn(Optional.of(stock));
        when(stockRepository.save(any(Stock.class))).thenAnswer(call -> call.getArgument(0));
        return stock;
    }

    private void noRowYet() {
        when(stockRepository.findForUpdateByStorehouseIdAndSku(STOREHOUSE_ID, SKU))
                .thenReturn(Optional.empty());
        Storehouse storehouse = new Storehouse();
        storehouse.setId(STOREHOUSE_ID);
        when(storehouseRepository.findById(STOREHOUSE_ID)).thenReturn(Optional.of(storehouse));
    }

    private static void assertStatus(Throwable thrown, HttpStatus status) {
        assertThat(thrown).isInstanceOfSatisfying(APIException.class,
                e -> assertThat(e.getStatus()).isEqualTo(status));
    }

    /** The quantity is added to what is there, and the row is the locked one. */
    @Test
    void addsToAnExistingRowReadForUpdate() {
        Stock stock = existing(100);

        Stock booked = service.add(SKU, STOREHOUSE_ID, 12);

        assertThat(booked.getOnHand()).isEqualTo(112);
        verify(stockRepository).findForUpdateByStorehouseIdAndSku(STOREHOUSE_ID, SKU);
        // The unlocked finder is what the race came from - it must not be used for a write any more.
        verify(stockRepository, never()).findByStorehouseIdAndSku(any(), any());
        assertThat(stock.getOnHand()).isEqualTo(112);
    }

    /** First delivery of that SKU into that storehouse: the row is created with the quantity on it. */
    @Test
    void createsTheRowOnTheFirstBooking() {
        noRowYet();
        when(stockRepository.saveAndFlush(any(Stock.class))).thenAnswer(call -> call.getArgument(0));

        Stock booked = service.add(SKU, STOREHOUSE_ID, 7);

        assertThat(booked.getSku()).isEqualTo(SKU);
        assertThat(booked.getOnHand()).isEqualTo(7);
        assertThat(booked.getReserved()).isZero();
        assertThat(booked.getStorehouse().getId()).isEqualTo(STOREHOUSE_ID);
    }

    /**
     * The one race that cannot be closed here - there is no row to lock yet, so two first bookings
     * can both insert. Flushed on the spot, so it is a 409 that says what happened instead of a 500
     * at some later commit. Retrying would need a transaction of its own, which the goods receipt
     * must not have: its status and this booking are all or nothing.
     */
    @Test
    void answersAConcurrentFirstBookingWith409() {
        noRowYet();
        when(stockRepository.saveAndFlush(any(Stock.class)))
                .thenThrow(new DataIntegrityViolationException("uq_stock_storehouse_sku"));

        assertStatus(catchThrowable(() -> service.add(SKU, STOREHOUSE_ID, 7)), HttpStatus.CONFLICT);
    }

    /** Nothing to book is not a booking - and it would otherwise write a no-op row. */
    @Test
    void refusesAQuantityOfZeroOrLess() {
        assertStatus(catchThrowable(() -> service.add(SKU, STOREHOUSE_ID, 0)), HttpStatus.BAD_REQUEST);
        assertStatus(catchThrowable(() -> service.add(SKU, STOREHOUSE_ID, -5)), HttpStatus.BAD_REQUEST);
        assertStatus(catchThrowable(() -> service.add(SKU, STOREHOUSE_ID, null)), HttpStatus.BAD_REQUEST);

        verify(stockRepository, never()).save(any());
        verify(stockRepository, never()).saveAndFlush(any());
    }
}
