package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.reservation.ReserveItem;
import com.supplychainmanagement.entity.Reservation;
import com.supplychainmanagement.entity.Stock;
import com.supplychainmanagement.repository.ReservationRepository;
import com.supplychainmanagement.repository.StockRepository;
import com.supplychainmanagement.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {
    private final StockRepository stockRepository;
    private final ReservationRepository reservationRepository;


    @Override
    public void reserveWithRetry(String orderId, List<ReserveItem> items) {

        int retries = 3;

        while (retries-- > 0) {
            try {
                reserve(orderId, items);
                return;

            } catch (OptimisticLockingFailureException ex) {
                if (retries == 0) throw ex;

                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    @Override
    public void releaseWithRetry(String orderId, List<ReserveItem> items) {
        int maxAttempts = 3;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                release(orderId, items);
                return;

            } catch (OptimisticLockingFailureException ex) {
                if (attempt == maxAttempts) {
                    throw ex;
                }

                backoff(attempt);
            }
        }
    }

    @Transactional
    public void reserve(String orderId, List<ReserveItem> items) {
        for (ReserveItem item : items) {
            Stock stock = stockRepository.findByStorehouseIdAndSku(item.storehouseId(), item.sku())
                    .orElseThrow();

            stock.reserve(item.quantity());
            stockRepository.save(stock);

            reservationRepository.save(Reservation.active(orderId, item.sku(), item.quantity()));
        }
    }

    @Transactional
    public void release(String orderId, List<ReserveItem> items) {

        for (ReserveItem item : items) {
            Stock stock = stockRepository.findByStorehouseIdAndSku(item.storehouseId(), item.sku())
                    .orElseThrow();
            stock.release(item.quantity());
            stockRepository.save(stock);

            Reservation reservation = reservationRepository.findActive(orderId, item.sku()).orElseThrow();
            reservation.release();
            reservationRepository.save(reservation);
        }
    }

    @Transactional
    public void consume(String orderId, List<ReserveItem> items) {
        for (ReserveItem item : items) {

            Stock stock = stockRepository.findByStorehouseIdAndSku(item.storehouseId(), item.sku())
                    .orElseThrow();
            stock.consume(item.quantity());
            stockRepository.save(stock);

            Reservation reservation = reservationRepository.findActive(orderId, item.sku()).orElseThrow();
            reservation.consume();
            reservationRepository.save(reservation);
        }
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(50L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}