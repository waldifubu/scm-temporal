package com.supplychainmanagement.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link Stock#restore} is the reverse of {@link Stock#consume}: what a pick took off the shelf goes back. */
class StockRestoreTest {

    private static Stock stock(int onHand, int reserved) {
        Stock stock = new Stock();
        stock.setOnHand(onHand);
        stock.setReserved(reserved);
        return stock;
    }

    @Test
    void putsTheQuantityBackOnTheShelfAndReservesNothing() {
        Stock stock = stock(10, 3);
        stock.consume(3);
        assertThat(stock.getOnHand()).isEqualTo(7);

        stock.restore(3);

        assertThat(stock.getOnHand()).isEqualTo(10);
        // consume took the reservation with it; the quantity is back, available to anybody.
        assertThat(stock.getReserved()).isZero();
        assertThat(stock.getAvailable()).isEqualTo(10);
    }

    @Test
    void leavesTheOtherReservationsOfTheRowAlone() {
        Stock stock = stock(10, 5);
        stock.consume(2);

        stock.restore(2);

        assertThat(stock.getOnHand()).isEqualTo(10);
        assertThat(stock.getReserved()).isEqualTo(3);
    }

    @Test
    void refusesAQuantityBelowOne() {
        assertThatThrownBy(() -> stock(1, 0).restore(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stock(1, 0).restore(-2)).isInstanceOf(IllegalArgumentException.class);
    }
}
