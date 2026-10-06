package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.component.ComponentRequestDto;
import com.supplychainmanagement.dto.component.RequestComponentResponse;
import com.supplychainmanagement.dto.component.RequestComponentsRequest;
import com.supplychainmanagement.dto.component.SupplierResponse;
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
import com.supplychainmanagement.service.impl.StockService;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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

        List<RequestComponentsRequest.Item> items = request.items();

        // One query for the whole request rather than one per line. The set collapses a SKU asked for
        // twice - the lines stay two, only the lookup is shared.
        Set<UUID> skus = items.stream()
                .map(RequestComponentsRequest.Item::componentId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, Component> bySku = componentRepository.findBySkuIn(skus).stream()
                .collect(Collectors.toMap(Component::getSku, Function.identity()));

        List<UUID> unknown = skus.stream().filter(sku -> !bySku.containsKey(sku)).toList();
        if (!unknown.isEmpty()) {
            // Every one of them at once: placing half a list and reporting the first miss would leave
            // the caller to work out what actually went through.
            throw new APIException(HttpStatus.NOT_FOUND, "Component not found with sku: "
                    + unknown.stream().map(UUID::toString).collect(Collectors.joining(", ")));
        }

        // Resolved once for the whole body rather than per line - every row of one request was
        // placed by the same person.
        User placedBy = actingUser(userId);

        List<RequestComponent> requests = items.stream().map(item -> {
            RequestComponent requested = new RequestComponent();
            requested.setComponent(bySku.get(item.componentId()));
            requested.setSupplier(supplier);
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

        // Checked here rather than left to StockService, whose IllegalArgumentException would end as
        // a 500 for what is simply a wrong path variable.
        if (!storehouseRepository.existsById(storehouseId)) {
            throw new ResourceNotFoundException("Storehouse", "id", storehouseId);
        }

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

        request.setRequestStatus(RequestStatus.IN_STOCK);
        // Who booked it in - the one step that creates real stock, so the one where it matters most.
        request.setAssignedBy(actingUser(userId));
        requestComponentRepository.save(request);

        return RequestComponentResponse.from(request);
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
