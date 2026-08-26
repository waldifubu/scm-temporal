package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.fullfillment.AvailableOrderItemDto;
import com.supplychainmanagement.dto.fullfillment.ProductionResultDto;
import com.supplychainmanagement.dto.reservation.ReservationResult;
import com.supplychainmanagement.dto.reservation.ReserveItem;
import com.supplychainmanagement.entity.*;
import com.supplychainmanagement.event.OrderStatusChangedEvent;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.exception.UnsufficientException;
import com.supplychainmanagement.model.enums.FullfillmentStatus;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.repository.*;
import com.supplychainmanagement.service.FullfillmentService;
import com.supplychainmanagement.service.InventoryService;
import com.supplychainmanagement.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FullfillmentServiceImpl implements FullfillmentService {

    private static final Set<OrderStatus> PRE_FULFILLMENT_STATUSES = EnumSet.of(
            OrderStatus.CREATED, OrderStatus.ACKNOWLEDGED, OrderStatus.REVIEW, OrderStatus.APPROVED);

    private final StockService stockService;
    private final StorehouseRepository storehouseRepository;
    private final ProductRepository productRepository;
    private final StockRepository stockRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final InventoryService inventoryService;
    private final ReservationRepository reservationRepository;
    private final OrderItemRepository orderItemRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public Page<ProductionResultDto> produce(Pageable pageable) {
        Page<Product> productsPage = productRepository.findAll(pageable);
        List<ProductionResultDto> results = new ArrayList<>();

        for (Product product : productsPage.getContent()) {
            var productionResult = produceSingleProduct(product);
            if (productionResult.produced()) {
                results.add(productionResult);
            }
        }

        return new PageImpl<>(results, pageable, results.size());
    }

    private ProductionResultDto produceSingleProduct(Product product) {
        if (product.getSku() == null || product.getComponents() == null || product.getComponents().isEmpty()) {
            return new ProductionResultDto(
                    product.getName(),
                    product.getSku(),
                    null,
                    false,
                    "Missing SKU or no components configured"
            );
        }

        Map<UUID, Integer> requiredComponents = getRequiredComponents(product.getComponents());
        if (requiredComponents.isEmpty()) {
            return new ProductionResultDto(
                    product.getName(),
                    product.getSku(),
                    null,
                    false,
                    "No valid component requirements found"
            );
        }

        Long selectedStorehouseId = findEligibleStorehouse(requiredComponents);
        if (selectedStorehouseId == null) {
            return new ProductionResultDto(
                    product.getName(),
                    product.getSku(),
                    null,
                    false,
                    "Not enough components available in one storehouse"
            );
        }

        if (!canProduceInStorehouse(selectedStorehouseId, requiredComponents)) {
            return new ProductionResultDto(
                    product.getName(),
                    product.getSku(),
                    selectedStorehouseId,
                    false,
                    "Insufficient stock for one or more components"
            );
        }

        for (Map.Entry<UUID, Integer> required : requiredComponents.entrySet()) {
            Stock componentStock = stockRepository.findByStorehouseIdAndSku(selectedStorehouseId, required.getKey())
                    .orElseThrow(() -> new IllegalStateException("Stock missing for component sku " + required.getKey()));

            int decrementBy = required.getValue();
            if (componentStock.getAvailable() < decrementBy) {
                return new ProductionResultDto(
                        product.getName(),
                        product.getSku(),
                        selectedStorehouseId,
                        false,
                        "Component stock changed while producing"
                );
            }

            componentStock.setOnHand(componentStock.getOnHand() - decrementBy);
            stockRepository.save(componentStock);
        }

        stockService.add(product.getSku(), selectedStorehouseId, 1);

        return new ProductionResultDto(
                product.getName(),
                product.getSku(),
                selectedStorehouseId,
                true,
                "Production successful"
        );
    }

    private Map<UUID, Integer> getRequiredComponents(List<Component> components) {
        Map<UUID, Integer> requiredBySku = new LinkedHashMap<>();
        for (Component component : components) {
            UUID sku = component.getSku();
            if (sku == null) {
                return Collections.emptyMap();
            }
            requiredBySku.merge(sku, 1, Integer::sum);
        }
        return requiredBySku;
    }

    private Long findEligibleStorehouse(Map<UUID, Integer> requiredComponents) {
        for (Map.Entry<UUID, Integer> required : requiredComponents.entrySet()) {
            List<Stock> oldestFirstStocks = stockRepository.findAvailableBySkuOrderByUpdatedAtAsc(required.getKey());
            for (Stock stock : oldestFirstStocks) {
                Long storehouseId = stock.getStorehouse().getId();
                if (canProduceInStorehouse(storehouseId, requiredComponents)) {
                    return storehouseId;
                }
            }
        }
        return null;
    }

    private boolean canProduceInStorehouse(Long storehouseId, Map<UUID, Integer> requiredComponents) {
        for (Map.Entry<UUID, Integer> required : requiredComponents.entrySet()) {
            Stock stock = stockRepository.findByStorehouseIdAndSku(storehouseId, required.getKey()).orElse(null);
            if (stock == null || stock.getAvailable() < required.getValue()) {
                return false;
            }
        }
        return true;
    }

    private List<Storehouse> getAllStorehouses() {
        return storehouseRepository.findAll();
    }

    /**
     * Reports, per order line, whether a single storehouse can cover it - without reserving and
     * without writing. Callers that want to change state have to do so themselves; this method is
     * reachable as a plain query through POST /orders/{orderNo}/check.
     */
    @Override
    @Transactional(readOnly = true)
    public List<AvailableOrderItemDto> checkItems(Order order) {
        List<AvailableOrderItemDto> availableItems = new ArrayList<>();

        for (OrderItem orderItem : order.getOrderItems()) {
            UUID sku = orderItem.getProduct().getSku();
            Integer requiredQuantity = orderItem.getQuantity();

            Stock match = findEligibleStock(sku, requiredQuantity).orElse(null);

            availableItems.add(new AvailableOrderItemDto(
                    orderItem.getProduct().getArticleNo(),
                    requiredQuantity,
                    match != null ? match.getAvailable() : 0,
                    match != null,
                    match != null ? match.getStorehouse().getId() : null));
        }

        return availableItems;
    }

    /**
     * One query per order line instead of a scan over every storehouse: the database picks the
     * oldest stock row that holds enough unreserved units. Returning the {@link Stock} itself also
     * yields the available quantity, so no second lookup is needed.
     */
    private Optional<Stock> findEligibleStock(UUID sku, Integer requiredQuantity) {
        return stockRepository.findEligibleBySku(sku, requiredQuantity).stream().findFirst();
    }

    /**
     * Transactional so the order status, the line item statuses and the published
     * {@link OrderStatusChangedEvent} share one commit.
     * <p>
     * Note that the stock side does NOT roll back with it: {@code reserveWithRetry} delegates to
     * {@code InventoryReservationTransactionService.reserve}, which runs REQUIRES_NEW and therefore
     * commits on its own. That is deliberate - the idempotency guard and the retry loop depend on
     * seeing committed state - but it means a failure after the reservation leaves the stock
     * reserved while the order stays untouched. The idempotency guard makes a repeat call safe.
     */
    @Override
    @Transactional
    public ReservationResult reserveItems(Order order, String username) {
        List<AvailableOrderItemDto> availableItems = checkItems(order);

        // Only lines that a single storehouse can cover are handed to the reservation. The rest are
        // left out entirely: passing them on would mean sending storehouseId == null into
        // InventoryReservationTransactionService, which cannot match a stock row for it. They stay
        // WAITING and are retried on the next call.
        List<AvailableOrderItemDto> unavailableItems = new ArrayList<>();
        List<ReserveItem> reserveItems = new ArrayList<>();
        for (AvailableOrderItemDto checkItem : availableItems) {
            if (!checkItem.available()) {
                unavailableItems.add(checkItem);
                continue;
            }

            var productOptional = productRepository.findByArticleNo(checkItem.articleNo());
            if (productOptional.isEmpty()) {
                throw new IllegalArgumentException("Product not found: " + checkItem.articleNo());
            }

            var product = productOptional.get();
            if (product.getSku() == null) {
                throw new IllegalArgumentException("Product SKU is null for: " + checkItem.articleNo());
            }

            reserveItems.add(new ReserveItem(product.getSku(), checkItem.orderQuantity(), checkItem.storehouseId()));
        }

        // Nothing to reserve and nothing reserved earlier: that is a genuine failure, not a partial
        // result. A line already held by this order reads as unavailable in checkItems - its stock
        // is booked to this very order - so the existing reservations have to be consulted before
        // giving up.
        if (reserveItems.isEmpty() && findActiveReservations(order).isEmpty()) {
            throw new UnsufficientException(describeUnavailable(unavailableItems));
        }

        ReservationResult result;
        try {
            // Reserve items in inventory with retry logic
            result = inventoryService.reserveWithRetry(String.valueOf(order.getId()), reserveItems);
        } catch (Exception e) {
            // Handle reservation failure
            throw new APIException(HttpStatus.BAD_REQUEST, "Failed to reserve items for order " + order.getId() + ": " + e.getMessage());
        }

        Set<UUID> reservedSkus = result.reservations().stream()
                .map(Reservation::getSku)
                .collect(Collectors.toSet());

        // Advance the order status only AFTER a successful reservation, otherwise the order would
        // read as "in progress" even though reserveWithRetry failed. Only move forward (out of a
        // pre-fulfillment status) so that a second, idempotent call never sets a further-along order
        // back. A partial reservation counts: fulfillment has started for at least one line.
        if (!reservedSkus.isEmpty() && PRE_FULFILLMENT_STATUSES.contains(order.getStatus())) {
            OrderStatus previousStatus = order.getStatus();
            order.setStatus(OrderStatus.IN_FULFILLMENT);
            orderRepository.save(order);
            userRepository.findByUsernameOrEmail(username, username).ifPresent(user -> {
                eventPublisher.publishEvent(new OrderStatusChangedEvent(order.getId(), user.getId(), previousStatus, OrderStatus.IN_FULFILLMENT));
            });
        }

        // Only the lines actually covered by a reservation move to RESERVED. The others keep the
        // status they had - normally WAITING - and are picked up again by the next call. Their
        // status is deliberately not reset here, so a line that is already further along in
        // fulfillment is not thrown back.
        List<OrderItem> reservedOrderItems = order.getOrderItems().stream()
                .filter(orderItem -> reservedSkus.contains(orderItem.getProduct().getSku()))
                .toList();

        reservedOrderItems.forEach(orderItem -> orderItem.setFullfillmentStatus(FullfillmentStatus.RESERVED));
        orderItemRepository.saveAll(reservedOrderItems);

        return result;
    }

    /**
     * Names every line that cannot be covered, together with the quantity asked for. The available
     * quantity is deliberately left out: for an uncovered line it is always 0 by construction, which
     * would read as "no stock at all" rather than "not enough in any one storehouse".
     */
    private String describeUnavailable(List<AvailableOrderItemDto> unavailableItems) {
        String detail = unavailableItems.stream()
                .map(checkItem -> "article " + checkItem.articleNo() + " (requested " + checkItem.orderQuantity() + ")")
                .collect(Collectors.joining(", "));

        return "No single storehouse holds the requested quantity for: " + detail;
    }

    // Helper along the lines of checkItems: returns the currently active reservations for the
    // order instead of checking stock levels the way checkItems does.
    private List<Reservation> findActiveReservations(Order order) {
        return reservationRepository.findActive(String.valueOf(order.getId()));
    }

    /**
     * Transactional for the same reason as {@link #reserveItems}: the line item statuses must be
     * written as one unit. The stock release itself again runs REQUIRES_NEW and commits separately.
     */
    @Override
    @Transactional
    public void releaseItems(Order order) {
        List<Reservation> activeReservations = findActiveReservations(order);
        if (activeReservations.isEmpty()) {
            throw new ResourceNotFoundException("Reservation", "orderId", order.getOrderNo());
        }

        List<ReserveItem> items = activeReservations.stream()
                .map(reservation -> new ReserveItem(
                        reservation.getSku(),
                        reservation.getQuantity(),
                        reservation.getStorehouse().getId()))
                .toList();

        inventoryService.releaseWithRetry(String.valueOf(order.getId()), items);

        // Reset the line items to WAITING only AFTER the release actually succeeded. Writing the
        // status first would leave them as WAITING even when releaseWithRetry threw - the items
        // would read as unreserved while their stock is still held by an active reservation.
        List<OrderItem> releasedOrderItems = order.getOrderItems().stream()
                .filter(orderItem -> activeReservations.stream()
                        .anyMatch(reservation -> reservation.getSku().equals(orderItem.getProduct().getSku())))
                .toList();

        releasedOrderItems.forEach(orderItem -> orderItem.setFullfillmentStatus(FullfillmentStatus.WAITING));
        orderItemRepository.saveAll(releasedOrderItems);
    }

    private Storehouse getAvailableStorehouse(UUID sku, Integer requiredQuantity) {
        for (Storehouse storehouse : getAllStorehouses()) {
            if (stockService.isAvailable(sku, requiredQuantity, storehouse.getId())) {
                return storehouse;
            }
        }
        return null;
    }
}
