package com.supplychainmanagement.service;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.model.enums.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Deliberately synchronous: blocking JPA sits underneath. These methods used to return Mono/Flux,
 * which rendered {@code @Transactional} useless - the proxy committed as soon as the method
 * returned the (not yet executed) Mono, and the actual database work then ran without a
 * transaction on a boundedElastic thread.
 */
public interface OrderService {

    List<Order> findAll();

    Page<Order> findAll(Pageable pageable);

    Page<Order> findAllByUserAndStatus(org.springframework.security.core.userdetails.User authUser, OrderStatus orderStatus, Pageable pageable);

    Page<Order> findAllByStatus(OrderStatus orderStatus, Pageable pageable);

    Order findById(Long id);

    Order findByOrderNo(Long orderNo);

    /**
     * The same lookup, but scoped to what the caller is allowed to see: a customer only gets an
     * order they are the customer of. Privileged roles are unrestricted.
     */
    Order findByOrderNoForUser(Long orderNo, org.springframework.security.core.userdetails.User authUser);

    Order create(Order order, org.springframework.security.core.userdetails.User user);

    /**
     * Accepts an incoming order and confirms a delivery date for it - the commercial answer a
     * customer waits for, the equivalent of an EDIFACT ORDRSP / X12 855.
     *
     * @param userId the acting user for the audit trail, may be null
     */
    Order acknowledge(Order order, Long userId);

    /**
     * Turns an incoming order down - possible right up to fulfillment, unlike acknowledging, which
     * only makes sense from CREATED.
     * <p>
     * A method of its own rather than the caller setting REJECTED and going through
     * {@link #update(Long, Order, Long)}: with {@code open-in-view} the controller's order and the
     * one update reloads are the same instance, so the status looked unchanged and the change went
     * into the database without an audit row.
     *
     * @param userId the acting user for the audit trail, may be null
     */
    Order reject(Order order, Long userId);

    /**
     * Closes the order - the commercial end, after the goods are in.
     * <p>
     * Checked, not claimed: every line has to have arrived in full, measured as packed quantity in
     * shipments that report DELIVERED. Counting lines would not do, because a line may be packed in
     * several runs and travel in several shipments - 5 of 10 delivered is not a delivered line.
     * An order that is already COMPLETED, one that ended in REJECTED or CANCELLED, and one without
     * lines are each a 409; so is one still missing something, and the message names every line that
     * is short.
     * <p>
     * Deliberately not asking for {@code OrderStatus.DELIVERED} as a precondition: that status is set
     * by whichever shipment arrives first and therefore says less than the quantities do.
     *
     * @param userId the acting user for the audit trail, may be null
     */
    Order complete(Order order, Long userId);

    Order update(Long id, Order order);

    Order update(Long id, Order order, Long userId);

    void deleteById(Long id);
}
