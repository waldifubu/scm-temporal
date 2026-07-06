package com.example.supplychainmanagement.service.impl;

import com.example.supplychainmanagement.entity.Order;
import com.example.supplychainmanagement.entity.OrderItem;
import com.example.supplychainmanagement.entity.Product;
import com.example.supplychainmanagement.entity.users.User;
import com.example.supplychainmanagement.exception.APIException;
import com.example.supplychainmanagement.exception.ResourceNotFoundException;
import com.example.supplychainmanagement.repository.OrderRepository;
import com.example.supplychainmanagement.repository.ProductRepository;
import com.example.supplychainmanagement.repository.UserRepository;
import com.example.supplychainmanagement.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderServiceImpl implements OrderService {
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;

    @Override
    public Flux<Order> findAll() {
        return Mono.fromCallable(orderRepository::findAllBy)
                .flatMapMany(Flux::fromIterable)
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Order> findById(Long id) {
        return Mono.fromCallable(() -> orderRepository.findWithDetailsById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Order", "id", id)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Order> findByOrderNo(long orderNo) {
        return Mono.fromCallable(() -> orderRepository.findByOrderNo(orderNo)
                        .orElseThrow(() -> new ResourceNotFoundException("Order", "orderNo", orderNo)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Transactional
    public Mono<Order> create(Order order) {
        return Mono.fromCallable(() -> {
                    validateOrderNo(order.getOrderNo(), null);
                    bindCustomer(order);
                    bindOrderItems(order);
                    recalculateOrder(order);
                    Order savedOrder = orderRepository.save(order);
                    return orderRepository.findWithDetailsById(savedOrder.getId())
                            .orElseThrow(() -> new ResourceNotFoundException("Order", "id", savedOrder.getId()));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Transactional
    public Mono<Order> update(Long id, Order order) {
        return Mono.fromCallable(() -> {
                    Order existingOrder = orderRepository.findById(id)
                            .orElseThrow(() -> new ResourceNotFoundException("Order", "id", id));

                    validateOrderNo(order.getOrderNo(), existingOrder);
                    existingOrder.setOrderNo(order.getOrderNo());
                    existingOrder.setDueDate(order.getDueDate());
                    existingOrder.setStatus(order.getStatus());
                    existingOrder.setDeliveryDate(order.getDeliveryDate());
                    existingOrder.setCustomer(order.getCustomer());
                    bindCustomer(existingOrder);

                    existingOrder.setOrderItems(order.getOrderItems());
                    bindOrderItems(existingOrder);
                    recalculateOrder(existingOrder);

                    Order savedOrder = orderRepository.save(existingOrder);
                    return orderRepository.findWithDetailsById(savedOrder.getId())
                            .orElseThrow(() -> new ResourceNotFoundException("Order", "id", savedOrder.getId()));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Transactional
    public Mono<Void> deleteById(Long id) {
        return Mono.fromRunnable(() -> {
                    if (!orderRepository.existsById(id)) {
                        throw new ResourceNotFoundException("Order", "id", id);
                    }
                    orderRepository.deleteById(id);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private void validateOrderNo(long orderNo, Order currentOrder) {
        if (orderNo <= 0) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Order number is required!");
        }

        if (currentOrder == null) {
            if (orderRepository.existsByOrderNo(orderNo)) {
                throw new APIException(HttpStatus.CONFLICT, "Order number already exists!");
            }
            return;
        }

        if (orderNo != currentOrder.getOrderNo() && orderRepository.existsByOrderNo(orderNo)) {
            throw new APIException(HttpStatus.CONFLICT, "Order number already exists!");
        }
    }

    private void bindCustomer(Order order) {
        User customer = order.getCustomer();
        if (customer == null || customer.getId() == null) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Customer is required!");
        }

        User persistedCustomer = userRepository.findById(customer.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", customer.getId()));
        order.setCustomer(persistedCustomer);
    }

    private void bindOrderItems(Order order) {
        List<OrderItem> orderItems = order.getOrderItems();
        if (orderItems == null) {
            return;
        }

        for (OrderItem orderItem : orderItems) {
            orderItem.setOrder(order);
            Product product = orderItem.getProduct();
            if (product == null || product.getId() == null) {
                throw new APIException(HttpStatus.BAD_REQUEST, "Product is required for each order item!");
            }

            Product persistedProduct = productRepository.findById(product.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product", "id", product.getId()));
            orderItem.setProduct(persistedProduct);
        }
    }

    private void recalculateOrder(Order order) {
        List<OrderItem> orderItems = order.getOrderItems();
        if (orderItems == null || orderItems.isEmpty()) {
            order.setAmountOfItems(0);
            order.setTotal(BigDecimal.ZERO);
            return;
        }

        int amountOfItems = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (OrderItem orderItem : orderItems) {
            amountOfItems += orderItem.getQty();
            if (orderItem.getProduct() != null && orderItem.getProduct().getUnitPrice() != null) {
                total = total.add(orderItem.getProduct().getUnitPrice().multiply(BigDecimal.valueOf(orderItem.getQty())));
            }
        }

        order.setAmountOfItems(amountOfItems);
        order.setTotal(total);
    }
}
