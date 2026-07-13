package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.repository.OrderRepository;
import com.supplychainmanagement.repository.ProductRepository;
import com.supplychainmanagement.repository.UserRepository;
import com.supplychainmanagement.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    public Mono<Page<Order>> findAll(Pageable pageable) {
        return Mono.fromCallable(() -> orderRepository.findAllBy(pageable))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<Order> findAllByStatus(OrderStatus orderStatus) {
            return Mono.fromCallable(() -> orderRepository.findAllByStatus(orderStatus))
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
    public Mono<Order> findByOrderNo(Long orderNo) {
        return Mono.fromCallable(() -> orderRepository.findByOrderNo(orderNo)
                        .orElseThrow(() -> new ResourceNotFoundException("Order", "orderNo", orderNo)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Transactional
    public Mono<Order> create(Order order) {
        return Mono.fromCallable(() -> {
                    order.setOrderNo(randomOrderNo());
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

    private long randomOrderNo() {
        return (long) (Math.random() * 9000) + 1000;
    }

    @Override
    @Transactional
    public Mono<Order> update(Long id, Order order) {
        return Mono.fromCallable(() -> {
                    Order existingOrder = orderRepository.findById(id)
                            .orElseThrow(() -> new ResourceNotFoundException("Order", "id", id));

                    Long nextOrderNo = order.getOrderNo() != null ? order.getOrderNo() : existingOrder.getOrderNo();
                    validateOrderNo(nextOrderNo, existingOrder);
                    existingOrder.setOrderNo(nextOrderNo);
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

    private void validateOrderNo(Long orderNo, Order currentOrder) {
        if (orderNo == null || orderNo <= 1000) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Order number is required!");
        }

        if (currentOrder == null) {
            if (orderRepository.existsByOrderNo(orderNo)) {
                throw new APIException(HttpStatus.CONFLICT, "Order number already exists!");
            }
            return;
        }

        if (!orderNo.equals(currentOrder.getOrderNo()) && orderRepository.existsByOrderNo(orderNo)) {
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
            Product fullyLoadedProduct = productRepository.findWithComponentsById(persistedProduct.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product", "id", persistedProduct.getId()));
            orderItem.setProduct(fullyLoadedProduct);
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
            Integer quantity = orderItem.getQuantity();
            if (quantity == null) {
                throw new APIException(HttpStatus.BAD_REQUEST, "Order item quantity is required!");
            }

            amountOfItems += quantity;
            if (orderItem.getProduct() != null && orderItem.getProduct().getUnitPrice() != null) {
                total = total.add(orderItem.getProduct().getUnitPrice().multiply(BigDecimal.valueOf(quantity)));
            }
        }

        order.setAmountOfItems(amountOfItems);
        order.setTotal(total);
    }
}
