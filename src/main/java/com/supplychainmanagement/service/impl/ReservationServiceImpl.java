package com.supplychainmanagement.service.impl;


import com.supplychainmanagement.dto.reservation.ReserveItem;
import com.supplychainmanagement.entity.Reservation;
import com.supplychainmanagement.entity.Stock;
import com.supplychainmanagement.repository.ReservationRepository;
import com.supplychainmanagement.repository.StockRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ReservationServiceImpl {

    private final StockRepository stockRepository;
    private final ReservationRepository reservationRepository;

    public ReservationServiceImpl(
            StockRepository stockRepository,
            ReservationRepository reservationRepository) {

        this.stockRepository = stockRepository;
        this.reservationRepository = reservationRepository;
    }


    @Transactional
    public void release(String orderId,
                        List<ReserveItem> items) {

        for (ReserveItem item : items) {

            Stock stock = stockRepository.findByStorehouseIdAndSku(item.storehouseId(), item.sku())
                    .orElseThrow(() ->
                            new IllegalArgumentException(
                                    "Stock not found: " + item.sku()));


            /*
             * reserved Bestand reduzieren
             */
            stock.release(item.quantity());


            /*
             * Hibernate erkennt die Änderung automatisch.
             * save() ist optional bei managed Entities,
             * aber explizit ist hier lesbarer.
             */
            stockRepository.save(stock);


            /*
             * Aktive Reservierung suchen
             */
            Reservation reservation =
                    reservationRepository
                            .findActive(orderId, item.sku())
                            .orElseThrow(() ->
                                    new IllegalStateException(
                                            "Active reservation missing"));


            /*
             * ACTIVE -> RELEASED
             */
            reservation.release();


            reservationRepository.save(reservation);
        }
    }
}