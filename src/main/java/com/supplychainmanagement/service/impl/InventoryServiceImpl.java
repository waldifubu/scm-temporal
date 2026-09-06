package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.reservation.ReservationResult;
import com.supplychainmanagement.dto.reservation.ReserveItem;
import com.supplychainmanagement.entity.Reservation;
import com.supplychainmanagement.repository.ReservationRepository;
import com.supplychainmanagement.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {
    private static final int MAX_ATTEMPTS = 3;

    private final InventoryReservationTransactionService transactionService;
    private final ReservationRepository reservationRepository;

    @Override
    public ReservationResult reserveWithRetry(String orderId, List<ReserveItem> items) {
        try {
            return executeWithRetry(() -> transactionService.reserve(orderId, items));
        } catch (DataIntegrityViolationException ex) {
            // Race: a concurrent reserve() call for the same order passed the idempotency guard in
            // InventoryReservationTransactionService.reserve() just ahead of us and already
            // committed. The DB unique constraint (order_id, sku, storehouse_id) catches that case.
            // Re-check instead of swallowing blindly: only if an ACTIVE reservation really exists
            // now was it exactly this race - the call is then already done and the existing
            // reservations are returned. Otherwise it was a different, genuine error -> rethrow.
            List<Reservation> existing = reservationRepository.findActive(orderId);
            if (existing.isEmpty()) {
                throw ex;
            }
            // The concurrent call did the inserting, not this one: nothing was created here, even
            // though the order is covered afterwards.
            return new ReservationResult(List.of(), existing);
        }
    }

    @Override
    public void releaseWithRetry(String orderId, List<ReserveItem> items) {
        executeWithRetry(() -> transactionService.release(orderId, items));
    }

    @Override
    public void consumeWithRetry(String orderId, List<ReserveItem> items) {
        executeWithRetry(() -> transactionService.consume(orderId, items));
    }

    private <T> T executeWithRetry(Supplier<T> transaction) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return transaction.get();
            } catch (OptimisticLockingFailureException ex) {
                if (attempt == MAX_ATTEMPTS) {
                    throw ex;
                }
                backoff(attempt);
            }
        }
        throw new IllegalStateException("Retry loop exited without result");
    }

    private void executeWithRetry(Runnable transaction) {
        executeWithRetry(() -> {
            transaction.run();
            return null;
        });
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(100L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
