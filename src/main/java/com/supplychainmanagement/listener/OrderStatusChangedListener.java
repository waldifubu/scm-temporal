package com.supplychainmanagement.listener;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderHistory;
import com.supplychainmanagement.event.OrderStatusChangedEvent;
import com.supplychainmanagement.repository.OrderHistoryRepository;
import com.supplychainmanagement.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class OrderStatusChangedListener {
    private final OrderRepository orderRepository;
    private final OrderHistoryRepository orderHistoryRepository;

    /**
     * REQUIRES_NEW is not optional here. An AFTER_COMMIT callback runs while the original
     * transaction's resources are still bound but the transaction itself is already committed.
     * With the default propagation the repository call would join that finished transaction, and
     * its EntityManager is closed without another flush - the history row would be dropped
     * silently, without an error and without a log entry. A fresh transaction actually commits it.
     * <p>
     * Note what this does and does not buy: the status change itself is already committed and stays
     * committed. The exception from a failing audit write does however propagate out of the outer
     * commit, so the caller still sees a 500 for an operation that actually succeeded. Making the
     * audit truly non-blocking would need this call wrapped in a try/catch around a separate bean -
     * a try/catch inside this method would not help, since the constraint violation only surfaces
     * when the transaction commits, which happens after the method body returns.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {
        Order order = orderRepository.findById(event.orderId())
                .orElseThrow(() -> new IllegalArgumentException("Order not found for history entry: " + event.orderId()));

        OrderHistory historyEntry = OrderHistory.builder()
                .userId(event.userId())
                .order(order)
                .previousStatus(event.previousStatus())
                .newStatus(event.newStatus())
                .build();

        orderHistoryRepository.save(historyEntry);
    }
}
