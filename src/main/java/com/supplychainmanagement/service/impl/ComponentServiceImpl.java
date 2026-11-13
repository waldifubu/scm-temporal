package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.service.StockService;
import com.supplychainmanagement.dto.component.ComponentPatchRequest;
import com.supplychainmanagement.dto.component.ComponentRequestDto;
import com.supplychainmanagement.dto.component.RequestComponentResponse;
import com.supplychainmanagement.dto.component.RequestComponentsRequest;
import com.supplychainmanagement.dto.component.SupplierResolution;
import com.supplychainmanagement.dto.component.SupplierResponse;
import com.supplychainmanagement.entity.SupplierRule;
import com.supplychainmanagement.repository.SupplierRuleRepository;
import com.supplychainmanagement.entity.Component;
import com.supplychainmanagement.entity.RequestComponent;
import com.supplychainmanagement.entity.Product;
import com.supplychainmanagement.entity.users.Supplier;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.repository.ComponentRepository;
import com.supplychainmanagement.repository.ProductRepository;
import com.supplychainmanagement.model.enums.RequestStatus;
import com.supplychainmanagement.repository.RequestComponentRepository;
import com.supplychainmanagement.repository.StorehouseRepository;
import com.supplychainmanagement.repository.SupplierRepository;
import com.supplychainmanagement.repository.UserRepository;
import com.supplychainmanagement.service.ComponentService;
import com.supplychainmanagement.service.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ComponentServiceImpl implements ComponentService {
    private final ComponentRepository componentRepository;
    private final ProductRepository productRepository;
    private final RequestComponentRepository requestComponentRepository;
    private final UserRepository userRepository;
    private final RoleService roleService;
    private final StorehouseRepository storehouseRepository;
    private final StockService stockService;
    private final SupplierRepository supplierRepository;
    private final SupplierRuleRepository supplierRuleRepository;

    /**
     * While the supplier can still call a request off: they have taken it on, but the goods are not
     * at our dock yet. From DELIVERED on it would be a return, which this process does not model -
     * the same line the shipment side draws at ACCEPTED. OPEN is not here on purpose: declining a
     * request nobody promised anything for is REJECTED, which says something else.
     */
    private static final Set<RequestStatus> CANCELLABLE_IN = EnumSet.of(
            RequestStatus.APPROVED, RequestStatus.IN_TRANSIT);

    @Override
    @Transactional
    public List<RequestComponentResponse> requestComponents(Long supplierId, RequestComponentsRequest request,
                                                            Long userId) {
        User user = userRepository.findById(supplierId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", supplierId));

        // Checked on the unproxied instance and before it is used as one: a cast up front turns any
        // other user into a ClassCastException (a 500), and a User proxy is never a Supplier. Same
        // shape as the distributor check when a shipment is handed over.
        if (!(Hibernate.unproxy(user) instanceof Supplier supplier)) {
            throw new APIException(HttpStatus.BAD_REQUEST, "User " + supplierId + " is not a supplier");
        }

        // A disabled account cannot log in, so it could never approve, send or deliver: a request placed
        // with it would sit in OPEN for good. The supplier list already hides such accounts; this is the
        // same rule at the one place that writes, for an id that was kept from before it was disabled.
        // 409 and not 400: the request is well formed, it is the supplier's state that refuses it.
        if (!Boolean.TRUE.equals(supplier.getIsActive())) {
            throw new APIException(HttpStatus.CONFLICT,
                    "Supplier " + supplierId + " is deactivated and cannot take a request");
        }

        List<RequestComponentsRequest.Item> items = request.items();
        Map<UUID, Component> bySku = componentsBySku(items.stream().map(RequestComponentsRequest.Item::componentId).toList());
        return place(items, bySku, component -> supplier, userId);
    }

    @Override
    @Transactional
    public List<RequestComponentResponse> requestComponents(RequestComponentsRequest request, Long userId) {
        List<RequestComponentsRequest.Item> items = request.items();
        Map<UUID, Component> bySku = componentsBySku(items.stream().map(RequestComponentsRequest.Item::componentId).toList());

        Map<UUID, SupplierRule> rules = rulesFor(bySku.values());
        List<UUID> uncovered = bySku.keySet().stream().filter(sku -> !rules.containsKey(sku)).toList();
        if (!uncovered.isEmpty()) {
            // Every one of them at once, and nothing placed: half a request would leave the caller to
            // work out what went through. 409 - the request is well formed, the rules refuse it.
            throw new APIException(HttpStatus.CONFLICT, "No active supplier rule for component: "
                    + uncovered.stream().map(UUID::toString).collect(Collectors.joining(", ")));
        }

        return place(items, bySku, component -> rules.get(component.getSku()).getSupplier(), userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SupplierResolution> resolveSuppliers(java.util.Collection<UUID> skus) {
        Map<UUID, Component> bySku = componentsBySku(skus);
        Map<UUID, SupplierRule> rules = rulesFor(bySku.values());
        return new LinkedHashSet<>(skus).stream().map(sku -> {
            SupplierRule rule = rules.get(sku);
            return rule == null
                    ? new SupplierResolution(sku, null, null, null)
                    : new SupplierResolution(sku, rule.getSupplier().getId(),
                            SupplierResponse.from(rule.getSupplier()).name(), rule.getId());
        }).toList();
    }

    /**
     * One query for the whole request rather than one per line. The SKUs are collapsed - a SKU asked for
     * twice stays two lines, only the lookup is shared. An unknown one is a 404 naming all of them.
     */
    private Map<UUID, Component> componentsBySku(java.util.Collection<UUID> requested) {
        Set<UUID> skus = new LinkedHashSet<>(requested);
        Map<UUID, Component> bySku = componentRepository.findBySkuIn(skus).stream()
                .collect(Collectors.toMap(Component::getSku, Function.identity()));

        List<UUID> unknown = skus.stream().filter(sku -> !bySku.containsKey(sku)).toList();
        if (!unknown.isEmpty()) {
            // Every one of them at once: placing half a list and reporting the first miss would leave
            // the caller to work out what actually went through.
            throw new APIException(HttpStatus.NOT_FOUND, "Component not found with sku: "
                    + unknown.stream().map(UUID::toString).collect(Collectors.joining(", ")));
        }
        return bySku;
    }

    /** The winning rule per SKU; a component no active rule covers is missing from the map. */
    private Map<UUID, SupplierRule> rulesFor(java.util.Collection<Component> components) {
        List<SupplierRule> rules = supplierRuleRepository.findAllWithActiveSupplier().stream()
                .sorted(SupplierRule.BEST_FIRST)
                .toList();
        Map<UUID, SupplierRule> winners = new java.util.HashMap<>();
        for (Component component : components) {
            rules.stream().filter(rule -> rule.appliesTo(component)).findFirst()
                    .ifPresent(rule -> winners.put(component.getSku(), rule));
        }
        return winners;
    }

    /** One row per line, in OPEN, placed with the supplier {@code supplierOf} gives for the line's component. */
    private List<RequestComponentResponse> place(List<RequestComponentsRequest.Item> items,
                                                 Map<UUID, Component> bySku,
                                                 Function<Component, Supplier> supplierOf, Long userId) {
        // Resolved once for the whole body rather than per line - every row of one request was
        // placed by the same person.
        User placedBy = actingUser(userId);

        List<RequestComponent> requests = items.stream().map(item -> {
            Component component = bySku.get(item.componentId());
            RequestComponent requested = new RequestComponent();
            requested.setComponent(component);
            requested.setSupplier(supplierOf.apply(component));
            requested.setQty(item.qty());
            requested.setComment(blankToNull(item.comment()));
            requested.setAssignedBy(placedBy);
            // requestStatus stays what the entity sets on insert - OPEN.
            return requested;
        }).toList();

        // Mapped here, while the transaction is open: the saved rows point at their component and
        // their supplier, and both are LAZY.
        return requestComponentRepository.saveAll(requests).stream()
                .map(RequestComponentResponse::from)
                .toList();
    }

    /**
     * Mapped here, while the transaction is open, like every other answer: the rows arrive with
     * their component and their acting user fetched, so the page costs one query and not one per row.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<RequestComponentResponse> findRequests(RequestStatus status, Pageable pageable) {
        Page<RequestComponent> page = status == null
                ? requestComponentRepository.findAllBy(pageable)
                : requestComponentRepository.findAllByRequestStatus(status, pageable);

        return page.map(RequestComponentResponse::from);
    }

    /** One query: a supplier has no lazy association the response reads. */
    @Override
    @Transactional(readOnly = true)
    public Page<SupplierResponse> findSuppliers(Pageable pageable) {
        return supplierRepository.findAllByIsActiveTrue(pageable).map(SupplierResponse::from);
    }

    @Override
    @Transactional
    public RequestComponentResponse approveRequest(Long requestId, Long userId) {
        return advanceRequest(requestId, RequestStatus.OPEN, RequestStatus.APPROVED, userId);
    }

    @Override
    @Transactional
    public RequestComponentResponse requestInTransit(Long requestId, Long userId) {
        return advanceRequest(requestId, RequestStatus.APPROVED, RequestStatus.IN_TRANSIT, userId);
    }

    @Override
    @Transactional
    public RequestComponentResponse requestDelivered(Long requestId, Long userId) {
        return advanceRequest(requestId, RequestStatus.IN_TRANSIT, RequestStatus.DELIVERED, userId);
    }

    @Override
    @Transactional
    public RequestComponentResponse rejectRequest(Long requestId, Long userId) {
        return advanceRequest(requestId, RequestStatus.OPEN, RequestStatus.REJECTED, userId);
    }

    /**
     * The one step with two possible starting points, so it does not go through
     * {@link #advanceRequest}, which allows exactly one.
     * <p>
     * Same shape otherwise: read FOR UPDATE, the answering supplier checked before the status (a
     * supplier poking at a request that is not theirs learns nothing about where it stands), the
     * acting user recorded after the checks.
     */
    @Override
    @Transactional
    public RequestComponentResponse cancelRequest(Long requestId, Long userId) {
        RequestComponent request = requestComponentRepository.findForUpdateById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("RequestComponent", "id", requestId));

        requireAnsweringSupplier(request, userId);

        if (!CANCELLABLE_IN.contains(request.getRequestStatus())) {
            throw new APIException(HttpStatus.CONFLICT, "Request " + requestId + " is "
                    + request.getRequestStatus() + ", only " + CANCELLABLE_IN
                    + " can be cancelled - a delivered request would be a return");
        }

        request.setRequestStatus(RequestStatus.CANCELLED);
        request.setAssignedBy(actingUser(userId));
        requestComponentRepository.save(request);

        return RequestComponentResponse.from(request);
    }

    /**
     * The goods receipt - the warehouse answering the supplier's DELIVERED with what it actually
     * found, and the one step the warehouse owns, and the only place component stock grows
     * other than through StockController by hand. Without it {@code assemble()} eventually finds
     * nothing left to build from.
     * <p>
     * The quantity goes to {@code Stock.onHand} for {@code (sku, storehouse)}. Deliberately not to
     * {@code Component.qty}: that is the bill-of-materials quantity - how many go into one product -
     * and adding a delivery to it would silently rewrite the recipe.
     * <p>
     * The storehouse is optional: without one, {@code StockService.suggestReceivingStorehouseForPart} picks it.
     * <p>
     * One transaction: if booking the stock fails, the status does not move either.
     */
    @Override
    @Transactional
    public RequestComponentResponse receiveRequest(Long requestId, Long storehouseId, Long userId) {
        RequestComponent request = requestComponentRepository.findForUpdateById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("RequestComponent", "id", requestId));

        // DELIVERED and not IN_TRANSIT: the supplier reports the handover first, so the receipt is
        // the answer to a claim rather than a guess. A supplier who never reports it does not block
        // the warehouse - an ADMIN may report DELIVERED for them, see requireAnsweringSupplier.
        if (request.getRequestStatus() != RequestStatus.DELIVERED) {
            throw new APIException(HttpStatus.CONFLICT, "Request " + requestId + " is "
                    + request.getRequestStatus() + ", only " + RequestStatus.DELIVERED
                    + " can be booked in");
        }

        // Nobody said where the goods arrived: the storehouse that already holds most of this part, otherwise the
        // first one. A storehouse that *was* named but does not exist stays a 404 - guessing another one would
        // book the goods somewhere the caller did not mean.
        Long targetStorehouseId = storehouseId == null ? suggestStorehouse(request) : requireStorehouse(storehouseId);

        bookIn(request, targetStorehouseId, RequestStatus.IN_STOCK, userId);
        return RequestComponentResponse.from(request, targetStorehouseId);
    }

    /** The named storehouse, or a 404: guessing another one would book the goods somewhere the caller did not mean. */
    private Long requireStorehouse(Long storehouseId) {
        if (!storehouseRepository.existsById(storehouseId)) {
            throw new ResourceNotFoundException("Storehouse", "id", storehouseId);
        }
        return storehouseId;
    }

    /**
     * Where a request is booked when nobody named a storehouse: where the rest of the part's product is kept
     * ({@code StockService.suggestReceivingStorehouseForPart}), 409 when no storehouse exists at all.
     */
    private Long suggestStorehouse(RequestComponent request) {
        Component part = request.getComponent();
        List<UUID> recipe = part.getProduct() == null || part.getProduct().getComponents() == null
                ? List.of()
                : part.getProduct().getComponents().stream().map(Component::getSku).toList();
        return stockService.suggestReceivingStorehouseForPart(part.getSku(), recipe)
                .map(com.supplychainmanagement.entity.Storehouse::getId)
                .orElseThrow(() -> new APIException(HttpStatus.CONFLICT,
                        "No storehouse exists to book request " + request.getId() + " into"));
    }

    @Override
    @Transactional
    public List<RequestComponentResponse> bookRequests(List<Long> requestIds, Long storehouseId, Long userId) {
        // Nothing to book is a mistake of the caller, not an empty success. The controller's bean validation
        // says the same for a request body; this is for whoever calls the service from inside.
        if (requestIds == null || requestIds.isEmpty()) {
            throw new APIException(HttpStatus.BAD_REQUEST, "No request given to book in");
        }

        // Ascending ids and each once: the rows are locked in the same order by every caller, so two
        // deliveries booked at the same time cannot wait on each other's rows, and a repeated id is
        // the same request, not two receipts.
        List<Long> ids = new TreeSet<>(requestIds).stream().toList();

        // A named storehouse is checked once, before any row is locked; without one, every request is booked where
        // the rest of its part's product is kept (see suggestStorehouse).
        if (storehouseId != null) {
            requireStorehouse(storehouseId);
        }

        List<RequestComponent> requests = new ArrayList<>();
        List<Long> missing = new ArrayList<>();
        for (Long id : ids) {
            requestComponentRepository.findForUpdateById(id).ifPresentOrElse(requests::add, () -> missing.add(id));
        }
        if (!missing.isEmpty()) {
            // Every one at once, and nothing booked: half a delivery in stock is worse than none.
            throw new APIException(HttpStatus.NOT_FOUND, "RequestComponent not found with id: "
                    + missing.stream().map(String::valueOf).collect(Collectors.joining(", ")));
        }

        List<String> notDelivered = requests.stream()
                .filter(request -> request.getRequestStatus() != RequestStatus.DELIVERED)
                .map(request -> request.getId() + " (" + request.getRequestStatus() + ")")
                .toList();
        if (!notDelivered.isEmpty()) {
            throw new APIException(HttpStatus.CONFLICT, "Only " + RequestStatus.DELIVERED
                    + " requests can be booked in, not: " + String.join(", ", notDelivered));
        }

        // Resolved one request at a time, in the order they are booked: a booking raises the stock of its SKU in that
        // storehouse, so a second request for the same part is suggested the same storehouse.
        List<String> booked = new java.util.ArrayList<>();
        List<RequestComponentResponse> answers = new java.util.ArrayList<>();
        for (RequestComponent request : requests) {
            Long target = storehouseId != null ? storehouseId : suggestStorehouse(request);
            bookIn(request, target, RequestStatus.IN_STOCK, userId);
            booked.add(request.getQty() + " x " + request.getComponent().getName()
                    + " [" + request.getComponent().getSku() + "] (request " + request.getId()
                    + ") -> storehouse " + target);
            answers.add(RequestComponentResponse.from(request, target));
        }

        log.info("Booked {} request(s): {}", requests.size(), String.join("; ", booked));
        return answers;
    }

    /**
     * The booking itself, shared by the single receipt and the delivery: the request's quantity onto the
     * stock of its component's SKU in the storehouse, then the request ends in {@code endStatus}, with
     * the acting user on it. The caller has checked the status and the storehouse. Runs inside the
     * caller's transaction, so a failure here leaves neither the stock nor the status changed.
     */
    private void bookIn(RequestComponent request, Long storehouseId, RequestStatus endStatus, Long userId) {
        Long requestId = request.getId();
        Component component = request.getComponent();
        if (component == null || component.getSku() == null) {
            throw new APIException(HttpStatus.CONFLICT,
                    "Request " + requestId + " has no component to book in");
        }
        if (request.getQty() == null || request.getQty() < 1) {
            throw new APIException(HttpStatus.CONFLICT,
                    "Request " + requestId + " has no usable quantity to book in");
        }
        // Stock.onHand is an int and the quantity a Long: intValue() on anything above
        // Integer.MAX_VALUE truncates, and 3_000_000_000 comes out as -1_294_967_296 - a receipt
        // that *lowers* the stock it is meant to raise. The column is a bigint since
        // RequestComponentQtyMigration, so a row can hold such a value; what keeps one from being
        // ordered is RequestComponentsRequest.MAX_QTY, and what keeps it from being booked is this.
        // Two gates on purpose - the one at the request can be raised, this one cannot be passed.
        if (request.getQty() > Integer.MAX_VALUE) {
            throw new APIException(HttpStatus.CONFLICT, "Request " + requestId + " is over "
                    + request.getQty() + " units - too much to book into stock in one go");
        }

        stockService.add(component.getSku(), storehouseId, request.getQty().intValue());

        request.setRequestStatus(endStatus);
        // Who booked it in - the one step that creates real stock, so the one where it matters most.
        request.setAssignedBy(actingUser(userId));
        requestComponentRepository.save(request);
    }

    /**
     * One step of the supplier's part: the request moves on, one status at a time. Locked with
     * findForUpdateById - two reports on the same request would otherwise both pass the status check.
     *
     * @param allowedFrom the only status the step may start from; anything else is a 409
     */
    private RequestComponentResponse advanceRequest(Long requestId, RequestStatus allowedFrom,
                                                    RequestStatus target, Long userId) {
        RequestComponent request = requestComponentRepository.findForUpdateById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("RequestComponent", "id", requestId));

        // Asked before the status: a supplier poking at a request that is not theirs learns nothing
        // about where it stands. Same shape as the carrier side of a shipment.
        requireAnsweringSupplier(request, userId);

        if (request.getRequestStatus() != allowedFrom) {
            throw new APIException(HttpStatus.CONFLICT, "Request " + requestId + " is "
                    + request.getRequestStatus() + ", only " + allowedFrom + " can be moved to " + target);
        }

        request.setRequestStatus(target);
        // Next to the status, every time: updated stamps itself through @UpdateTimestamp, this says
        // who caused the stamp. Written after the checks, so a refused step changes nothing.
        request.setAssignedBy(actingUser(userId));
        requestComponentRepository.save(request);

        // Mapped here, while the transaction is open - component and supplier are LAZY.
        return RequestComponentResponse.from(request);
    }

    /**
     * Only the supplier the request was placed with answers it - anybody else is a 403. ADMIN is
     * exempt, as the role that has to be able to correct things.
     */
    private void requireAnsweringSupplier(RequestComponent request, Long userId) {
        Supplier supplier = request.getSupplier();
        if (supplier == null) {
            throw new APIException(HttpStatus.CONFLICT,
                    "Request " + request.getId() + " has no supplier to answer it");
        }

        if (Objects.equals(supplier.getId(), userId) || roleService.isAdmin(userId)) {
            return;
        }

        throw new APIException(HttpStatus.FORBIDDEN,
                "Request " + request.getId() + " was placed with another supplier");
    }

    /**
     * The acting user as an entity, for {@code assignedBy}. Leniently: a null id, or one no user
     * answers to, leaves the column null rather than failing the step that is being reported. The
     * order side treats its audit user the same way - a sweep running as nobody still has to be able
     * to move a status.
     */
    private User actingUser(Long userId) {
        return userId == null ? null : userRepository.findById(userId).orElse(null);
    }

    /** An empty comment is no comment - stored as null, so it stays out of the JSON. */
    private static String blankToNull(String comment) {
        return comment == null || comment.isBlank() ? null : comment.trim();
    }

    @Override
    public List<Component> findAll() {
        return componentRepository.findAllBy();
    }

    @Override
    public Component findById(Long id) {
        return componentRepository.findWithProductById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Component", "id", id));
    }

    @Override
    public Component findBySku(UUID sku) {
        return componentRepository.findBySku(sku)
                .orElseThrow(() -> new APIException(HttpStatus.NOT_FOUND, "Component not found with sku: " + sku.toString()));
    }

    @Override
    public Component findByArticleNo(String articleNo) {
        return componentRepository.findByExternalId(articleNo)
                .orElseThrow(() -> new APIException(HttpStatus.NOT_FOUND, "Component not found with articleNo: " + articleNo));
    }

    @Override
    @Transactional
    public Component create(ComponentRequestDto request) {
        // A fresh entity, so there is no way for a request to carry an id and turn the save into a
        // merge over somebody else's row.
        Component component = new Component();
        apply(request, component);
        validateUniqueIdentifiers(component, null);
        return componentRepository.save(component);
    }

    /**
     * Copies a request onto a component and resolves its product. Shared by create and update, so
     * the two cannot drift apart on which fields a client may set.
     * <p>
     * {@code sku} and {@code weight} are only written when they are sent: left out on an update they
     * keep their value, and left out on create the entity fills them in itself ({@code @PrePersist}).
     */
    private void apply(ComponentRequestDto request, Component component) {
        Product product = productRepository.findByArticleNo(request.productArticleNo())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Product", "articleNo", request.productArticleNo()));

        component.setProduct(product);
        component.setName(request.name());
        component.setManufacturer(request.manufacturer());
        component.setArticleNo(request.articleNo());
        component.setDescription(request.description());
        component.setExternalId(request.externalId());
        component.setQty(request.qty());
        if (request.sku() != null) {
            component.setSku(request.sku());
        }
        if (request.weight() != null) {
            component.setWeight(request.weight());
        }
    }

    @Override
    @Transactional
    public Component update(Long id, ComponentRequestDto request) {
        Component existingComponent = componentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Component", "id", id));

        // Checked against a throwaway carrying the incoming identifiers, the way it was checked
        // against the bound entity before: the existing row is excluded from the uniqueness test, so
        // keeping your own sku is not a conflict with yourself.
        Component incoming = new Component();
        incoming.setSku(request.sku());
        incoming.setExternalId(request.externalId());
        validateUniqueIdentifiers(incoming, existingComponent);

        // One place decides which fields a client may set - update used to copy a different, smaller
        // selection than create bound, so description, weight, articleNo and qty could be created
        // but never changed.
        apply(request, existingComponent);

        return componentRepository.save(existingComponent);
    }

    @Override
    @Transactional
    public Component patch(UUID sku, ComponentPatchRequest request) {
        if (request == null || (request.name() == null && request.manufacturer() == null
                && request.description() == null && request.externalId() == null
                && request.weight() == null && request.qty() == null)) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Nothing to change: send name, manufacturer, description, externalId, weight and/or qty");
        }
        Component component = findBySku(sku);
        String externalId = request.externalId() == null ? null
                : request.externalId().isBlank() ? null : request.externalId();
        if (externalId != null) {
            Component incoming = new Component();
            incoming.setExternalId(externalId);
            validateUniqueIdentifiers(incoming, component);
        }
        if (request.name() != null) {
            component.setName(request.name());
        }
        if (request.manufacturer() != null) {
            component.setManufacturer(request.manufacturer().isBlank() ? null : request.manufacturer());
        }
        if (request.description() != null) {
            component.setDescription(request.description().isBlank() ? null : request.description());
        }
        if (request.externalId() != null) {
            component.setExternalId(externalId);
        }
        if (request.weight() != null) {
            component.setWeight(request.weight());
        }
        if (request.qty() != null) {
            component.setQty(request.qty());
        }
        return componentRepository.save(component);
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        if (!componentRepository.existsById(id)) {
            throw new ResourceNotFoundException("Component", "id", id);
        }
        componentRepository.deleteById(id);
    }

    private void validateUniqueIdentifiers(Component component, Component existingComponent) {
        if (component.getSku() != null
                && componentRepository.existsBySku(component.getSku())
                && (existingComponent == null || !component.getSku().equals(existingComponent.getSku()))) {
            throw new APIException(HttpStatus.CONFLICT, "SKU already exists!");
        }

        if (component.getExternalId() != null
                && componentRepository.existsByExternalId(component.getExternalId())
                && (existingComponent == null || !component.getExternalId().equals(existingComponent.getExternalId()))) {
            throw new APIException(HttpStatus.CONFLICT, "Article number already exists!");
        }
    }
}
