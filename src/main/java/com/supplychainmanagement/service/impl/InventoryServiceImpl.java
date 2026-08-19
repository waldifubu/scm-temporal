package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.reservation.ReserveItem;
import com.supplychainmanagement.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {
    private static final int MAX_ATTEMPTS = 3;

    private final InventoryReservationTransactionService transactionService;

    @Override
    public void reserveWithRetry(String orderId, List<ReserveItem> items) {

        executeWithRetry(() -> transactionService.reserve(orderId, items));
    }

    @Override
    public void releaseWithRetry(String orderId, List<ReserveItem> items) {
        executeWithRetry(() -> transactionService.release(orderId, items));
    }

    @Override
    public void consumeWithRetry(String orderId, List<ReserveItem> items) {
        executeWithRetry(() -> transactionService.consume(orderId, items));
    }

    private void executeWithRetry(Runnable transaction) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                transaction.run();
                return;
            } catch (OptimisticLockingFailureException ex) {
                if (attempt == MAX_ATTEMPTS) {
                    throw ex;
                }
                backoff(attempt);
            }
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
