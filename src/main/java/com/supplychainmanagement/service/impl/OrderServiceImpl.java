package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.fullfillment.AvailableOrderItemDto;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.event.OrderStatusChangedEvent;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.model.enums.RoleEnum;
import com.supplychainmanagement.repository.OrderRepository;
import com.supplychainmanagement.repository.ProductRepository;
import com.supplychainmanagement.repository.UserRepository;
import com.supplychainmanagement.service.OrderService;
import com.supplychainmanagement.service.ProductionService;
import com.supplychainmanagement.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderServiceImpl implements OrderService {
    private static final LocalTime END_OF_WORKING_DAY = LocalTime.of(17, 0);
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final RoleService roleService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ProductionService productionService;
    /**
     * Working days from acknowledgement to delivery when every line is covered by stock today.
     */
    @Value("${app.order.leadDays.inStock:2}")
    private int inStockLeadDays;

    /**
     * Working days when at least one line has to be replenished or produced first.
     */
    @Value("${app.order.leadDays.replenishment:10}")
    private int replenishmentLeadDays;

    @Override
    @Deprecated
    public List<Order> findAll() {
        return orderRepository.findAllBy();
    }

    @Override
    public Page<Order> findAllByUser(org.springframework.security.core.userdetails.User authUser, Pageable pageable) {
        if (roleService.isAdmin(authUser)) {
            return findAll(pageable);
        }

        var user = userRepository.findByUsernameOrEmail(authUser.getUsername(), authUser.getUsername())
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", 0L));
        return orderRepository.findAllByCustomer(user, pageable);
    }

    @Override
    public Page<Order> findAll(Pageable pageable) {
        return orderRepository.findAllBy(pageable);
    }

    @Override
    public Page<Order> findAllByStatus(OrderStatus orderStatus, Pageable pageable) {
        return orderRepository.findAllByStatus(orderStatus, pageable);
    }

    @Override
    public Order findById(Long id) {
        return orderRepository.findWithDetailsById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", id));
    }

    @Override
    public Order findByOrderNo(Long orderNo) {
        return orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "orderNo", orderNo));
    }

    /**
     * Mirrors the branch in {@link #findAllByUser}: a privileged caller sees every order, everyone
     * else only their own.
     * <p>
     * Answered with 403 and not 404 on purpose - inside this application an order number is not a
     * secret, and "you may not see this one" is a more useful answer than pretending it does not
     * exist. Flip it to ResourceNotFoundException if order numbers should stop being enumerable.
     */
    @Override
    public Order findByOrderNoForUser(Long orderNo, org.springframework.security.core.userdetails.User authUser) {
        Order order = findByOrderNo(orderNo);

        if (roleService.isPrivilegedUser(authUser)) {
            return order;
        }

        var caller = userRepository.findByUsernameOrEmail(authUser.getUsername(), authUser.getUsername())
                .orElseThrow(() -> new ResourceNotFoundException("User", "username", 0L));

        if (order.getCustomer() == null || !Objects.equals(order.getCustomer().getId(), caller.getId())) {
            throw new APIException(HttpStatus.FORBIDDEN, "This order belongs to another customer");
        }

        return order;
    }

    @Override
    @Transactional
    public Order create(Order order, org.springframework.security.core.userdetails.User user) {
        if (roleService.isPrivilegedUser(user)) {
            // @TODO: Check if the customer exists in the database, if not throw an exception
            if (order.getCustomer() == null || order.getCustomer().getId() == null || userRepository.findByUsername(user.getUsername()).isEmpty()) {
                throw new APIException(HttpStatus.BAD_REQUEST, "Customer is required for privileged users!");
            }

            order.setCustomer(order.getCustomer());
        } else {
            user.getAuthorities().stream()
                    .filter(auth -> Objects.equals(auth.getAuthority(), RoleEnum.CUSTOMER.name()))
                    .findFirst()
                    .orElseThrow(() -> new APIException(HttpStatus.FORBIDDEN, "You are not allowed to create orders!"));

            var dbUser = userRepository.findByUsernameOrEmail(user.getUsername(), user.getUsername())
                    .orElseThrow(() -> new ResourceNotFoundException("User", user.getUsername(), 0L));

            order.setCustomer(dbUser);
        }

        order.setOrderNo(randomOrderNo());
        validateOrderNo(order.getOrderNo(), null);
        bindCustomer(order);
        bindOrderItems(order);
        recalculateOrder(order);

        Order savedOrder = orderRepository.save(order);
        if (savedOrder.getStatus() != null) {
            applicationEventPublisher.publishEvent(new OrderStatusChangedEvent(savedOrder.getId(), savedOrder.getCustomer() != null ? savedOrder.getCustomer().getId() : null, null, savedOrder.getStatus()));
        }
        Long createdId = savedOrder.getId();
        return orderRepository.findWithDetailsById(createdId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", createdId));
    }

    private Long randomOrderNo() {
        long candidate = 0L;
        while (candidate <= 1000 || orderRepository.existsByOrderNo(candidate)) {
            candidate = (long) (Math.random() * 9000) + 1000;
        }

        return candidate;
    }

    public void statusCheck(Order existingOrder, Order order) {
        OrderStatus previousStatus = existingOrder.getStatus();

    }

    /**
     * The guard is {@code != CREATED} rather than "not already acknowledged": unlike a rejection,
     * which stays possible right up to fulfillment, a confirmation only makes sense from the
     * incoming state. Acknowledging an order that is already reserved or picked would throw it back.
     */
    @Override
    @Transactional
    public Order acknowledge(Order order, Long userId) {
        if (order.getStatus() != OrderStatus.CREATED) {
            throw new APIException(HttpStatus.BAD_REQUEST,
                    "Only an order in CREATED can be acknowledged, this one is " + order.getStatus());
        }

        order.setStatus(OrderStatus.ACKNOWLEDGED);
        // Order.deliveryDate is a timestamp while the promise is a day: pinned to the end of the
        // working day, so "delivered on the 15th" does not read as midnight of the 15th.
        order.setDeliveryDate(confirmDeliveryDate(order).atTime(END_OF_WORKING_DAY));

        return update(order.getId(), order, userId);
    }

    /**
     * What the confirmation promises. Two lead times rather than one: an order every storehouse can
     * cover today ships within days, one waiting on replenishment cannot. checkItems answers that
     * question without reserving anything, so asking it here costs a query per line and no state.
     * <p>
     * A delivery date the customer asked for later than we can manage wins - shipping earlier than
     * requested is not a favour. Asking for it earlier does not, because the promise has to be one
     * that can be kept.
     */
    private LocalDate confirmDeliveryDate(Order order) {
        boolean everyLineCovered = productionService.checkItems(order).stream()
                .allMatch(AvailableOrderItemDto::available);

        LocalDate earliest = addWorkingDays(LocalDate.now(), everyLineCovered ? inStockLeadDays : replenishmentLeadDays);

        LocalDate requested = order.getDueDate();
        return requested != null && requested.isAfter(earliest) ? requested : earliest;
    }

    /**
     * Weekends are not shipping days - a promise counted in calendar days would be missed.
     */
    private LocalDate addWorkingDays(LocalDate from, int workingDays) {
        LocalDate date = from;
        for (int added = 0; added < workingDays; ) {
            date = date.plusDays(1);
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                added++;
            }
        }
        return date;
    }

    @Override
    @Transactional
    public Order update(Long id, Order order) {
        return update(id, order, null);
    }

    @Override
    @Transactional
    public Order update(Long id, Order order, Long userId) {
        Order existingOrder = orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", id));

        OrderStatus previousStatus = existingOrder.getStatus();
        Long nextOrderNo = order.getOrderNo() != null ? order.getOrderNo() : existingOrder.getOrderNo();
        validateOrderNo(nextOrderNo, existingOrder);
        existingOrder.setOrderNo(nextOrderNo);
        existingOrder.setDueDate(order.getDueDate());
        existingOrder.setStatus(order.getStatus());
        existingOrder.setDeliveryDate(order.getDeliveryDate());
        existingOrder.setCustomer(order.getCustomer());
        bindCustomer(existingOrder);

        applyOrderItems(existingOrder, order.getOrderItems());
        recalculateOrder(existingOrder);

        Order savedOrder = orderRepository.save(existingOrder);
        if (previousStatus != null && order.getStatus() != null && !Objects.equals(previousStatus, order.getStatus())) {
            applicationEventPublisher.publishEvent(new OrderStatusChangedEvent(savedOrder.getId(), userId, previousStatus, order.getStatus()));
        }
        return orderRepository.findWithDetailsById(savedOrder.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", savedOrder.getId()));
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        if (!orderRepository.existsById(id)) {
            throw new ResourceNotFoundException("Order", "id", id);
        }
        orderRepository.deleteById(id);
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
        Set<OrderItem> orderItems = order.getOrderItems();
        if (orderItems == null) {
            return;
        }

        order.setOrderItems(mergeDuplicateProducts(orderItems));

        for (OrderItem orderItem : orderItems) {
            orderItem.setOrder(order);
            orderItem.setProduct(resolveProduct(orderItem.getProduct()));
        }
    }

    /**
     * Applies the incoming line items to the existing order.
     * <p>
     * Deliberately NOT via {@code existingOrder.setOrderItems(...)}: {@code Order.orderItems} is
     * mapped with {@code orphanRemoval = true}. Replacing the Hibernate-managed collection instance
     * of a managed entity with a different one makes the flush fail with
     * "A collection with cascade=all-delete-orphan was no longer referenced". While this method ran
     * outside a transaction that never surfaced - the entity was detached.
     * <p>
     * Instead the collection is synchronised in place: existing items are reused and updated by
     * their id, vanished ones drop out as orphans, new ones are added. The collection instance
     * itself stays the same.
     * <p>
     * That the collection is a Set changes nothing about the merge: {@link OrderItem} inherits
     * identity equality, so two distinct line items never collapse into one, not even when they
     * carry the same product and qty. The merge is keyed by id, not by equality.
     */
    private void applyOrderItems(Order existingOrder, Set<OrderItem> incomingItems) {
        if (incomingItems == null) {
            return;
        }

        incomingItems = mergeDuplicateProducts(incomingItems);

        // LinkedHashSet rather than HashSet: only relevant for an order that has no collection yet,
        // but it keeps the line items in the order they came in instead of an arbitrary one.
        if (existingOrder.getOrderItems() == null) {
            existingOrder.setOrderItems(new LinkedHashSet<>());
        }
        Set<OrderItem> currentItems = existingOrder.getOrderItems();

        Map<Long, OrderItem> currentById = new HashMap<>();
        for (OrderItem currentItem : currentItems) {
            if (currentItem.getId() != null) {
                currentById.put(currentItem.getId(), currentItem);
            }
        }

        List<OrderItem> mergedItems = new ArrayList<>(incomingItems.size());
        for (OrderItem incomingItem : incomingItems) {
            OrderItem target = incomingItem.getId() != null ? currentById.get(incomingItem.getId()) : null;
            if (target == null) {
                target = new OrderItem();
            }

            target.setOrder(existingOrder);
            target.setQuantity(incomingItem.getQuantity());
            if (incomingItem.getFulfillmentStatus() != null) {
                target.setFulfillmentStatus(incomingItem.getFulfillmentStatus());
            }
            target.setProduct(resolveProduct(incomingItem.getProduct()));
            mergedItems.add(target);
        }

        currentItems.clear();
        currentItems.addAll(mergedItems);
    }

    /**
     * Folds repeated articles into one line, adding up their quantities: three of an article on one
     * line and two on another become a single line of five.
     * <p>
     * An article may appear on one line only - a second line of the same product would make
     * (order, product) ambiguous, and that pair is what a reservation is keyed to. The unique
     * constraint on order_items states the rule; this is what keeps callers from running into it.
     * <p>
     * The first occurrence survives and keeps its identity - on an update that means the existing
     * line is the one that is kept and grown, while the repeated ones drop out and are removed as
     * orphans. Lines without a resolvable product are passed through untouched; resolveProduct
     * reports those properly a moment later.
     */
    private Set<OrderItem> mergeDuplicateProducts(Collection<OrderItem> orderItems) {
        Map<Long, OrderItem> byArticleNo = new LinkedHashMap<>();
        Set<OrderItem> merged = new LinkedHashSet<>();

        for (OrderItem orderItem : orderItems) {
            Long articleNo = orderItem.getProduct() != null ? orderItem.getProduct().getArticleNo() : null;
            if (articleNo == null) {
                merged.add(orderItem);
                continue;
            }

            OrderItem alreadySeen = byArticleNo.putIfAbsent(articleNo, orderItem);
            if (alreadySeen == null) {
                merged.add(orderItem);
                continue;
            }

            alreadySeen.setQuantity(quantityOf(alreadySeen) + quantityOf(orderItem));
        }

        return merged;
    }

    /** A missing quantity counts as nothing here; recalculateOrder is what rejects it. */
    private int quantityOf(OrderItem orderItem) {
        return orderItem.getQuantity() != null ? orderItem.getQuantity() : 0;
    }

    private Product resolveProduct(Product product) {
        if (product == null || product.getArticleNo() == null) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Product is required for each order item!");
        }

        return productRepository.findWithComponentsByArticleNo(product.getArticleNo())
                .orElseThrow(() -> new ResourceNotFoundException("Product", "articleNo", product.getArticleNo()));
    }

    private void recalculateOrder(Order order) {
        Set<OrderItem> orderItems = order.getOrderItems();
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
                throw new APIException(HttpStatus.BAD_REQUEST, "Order item qty is required!");
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
