package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.shipping.CancelShipmentRequest;
import com.supplychainmanagement.dto.shipping.CreateShipmentRequest;
import com.supplychainmanagement.dto.shipping.ShipmentListDto;
import com.supplychainmanagement.dto.shipping.ShipmentPackageIdsRequest;
import com.supplychainmanagement.dto.shipping.ShipmentResponse;
import com.supplychainmanagement.dto.shipping.UpdateShipmentRequest;
import com.supplychainmanagement.entity.Order;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.Shipment;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.entity.users.Customer;
import com.supplychainmanagement.entity.users.Distributor;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.OrderStatus;
import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
import com.supplychainmanagement.model.enums.ShipmentStatus;
import com.supplychainmanagement.repository.OrderItemRepository;
import com.supplychainmanagement.repository.OrderRepository;
import com.supplychainmanagement.repository.ShipmentPackageRepository;
import com.supplychainmanagement.repository.ShipmentRepository;
import com.supplychainmanagement.repository.UserRepository;
import com.supplychainmanagement.service.OrderProgressService;
import com.supplychainmanagement.service.ShipmentService;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class ShipmentServiceImpl implements ShipmentService {

    /**
     * While the shipment's own data - address, method, date, comment - may still be corrected. From
     * IN_TRANSIT on the papers are out of the house, and a cancelled shipment is not edited either.
     */
    private static final Set<ShipmentStatus> DATA_CHANGEABLE_IN = EnumSet.of(
            ShipmentStatus.CREATED, ShipmentStatus.READY,
            ShipmentStatus.DISPATCH_REQUESTED, ShipmentStatus.ACCEPTED);

    /**
     * While a shipment can still be called off: up to the handover, the goods are in the house. From
     * IN_TRANSIT on it is not a cancellation any more but a return, which the process does not model.
     */
    private static final Set<ShipmentStatus> CANCELLABLE_IN = EnumSet.of(
            ShipmentStatus.CREATED, ShipmentStatus.READY,
            ShipmentStatus.DISPATCH_REQUESTED, ShipmentStatus.ACCEPTED);

    private final ShipmentRepository shipmentRepository;
    private final ShipmentPackageRepository shipmentPackageRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final UserRepository userRepository;
    private final OrderProgressService orderProgress;

    /**
     * A shipment is created together with its packages, never empty: the customer and at least one
     * package of that customer. Every package is checked before anything is written - see
     * {@link #requireShippable}.
     */
    @Override
    @Transactional
    public ShipmentResponse createShipment(CreateShipmentRequest request) {
        requireValidRequest(request);

        List<ShipmentPackage> packages = findPackagesForUpdate(request.shipmentPackageIds());
        Long customerId = customerOf(packages.getFirst());

        Customer customer = findCustomer(customerId);
        requireShippable(null, customer.getId(), packages);
        requireDistinctPackageNumbers(null, packages);

        Shipment shipment = new Shipment();
        shipment.setCustomer(customer);
        // Optional when creating - it has to be there before the shipment is READY.
        shipment.setShippingAddress(blankToNull(request.shippingAddress()));
        shipment.setShippingMethod(request.shippingMethod());
        shipment.setRequestedDeliveryDate(request.requestedDeliveryDate());
        // Saved before the packages point at it: the shipment id is what their foreign key takes.
        Shipment saved = shipmentRepository.save(shipment);
        packages.forEach(saved::addPackage);

        return toResponse(saved);
    }

    /** Puts further packages into the shipment. A package already in it is left as it is. */
    @Override
    @Transactional
    public ShipmentResponse addShipmentPackages(Long shipmentId, ShipmentPackageIdsRequest request) {
        if (request.shipmentPackageIds().isEmpty()) {
            throw new APIException(HttpStatus.BAD_REQUEST, "At least one shipment package id is required");
        }

        Shipment shipment = findChangeableShipmentForUpdate(shipmentId);
        List<ShipmentPackage> added = findPackagesForUpdate(request.shipmentPackageIds()).stream()
                .filter(shipmentPackage -> !isIn(shipmentPackage, shipment))
                .toList();
        requireShippable(shipment, shipment.getCustomer().getId(), added);
        requireDistinctPackageNumbers(shipmentId,
                Stream.concat(shipment.getPackages().stream(), added.stream()).toList());

        added.forEach(shipment::addPackage);

        return toResponse(shipment);
    }

    /**
     * Makes the shipment hold exactly the given packages. Those it held and that are not in the list
     * are free again - never deleted. An empty list is refused: a shipment is never left empty.
     */
    @Override
    @Transactional
    public ShipmentResponse replaceShipmentPackages(Long shipmentId, ShipmentPackageIdsRequest request) {
        if (request.shipmentPackageIds().isEmpty()) {
            throw new APIException(HttpStatus.BAD_REQUEST,
                    "A shipment needs at least one package - Shipment " + shipmentId + " cannot be emptied");
        }

        Shipment shipment = findChangeableShipmentForUpdate(shipmentId);
        List<ShipmentPackage> wanted = findPackagesForUpdate(request.shipmentPackageIds());
        List<ShipmentPackage> arriving = wanted.stream()
                .filter(shipmentPackage -> !isIn(shipmentPackage, shipment))
                .toList();
        requireShippable(shipment, shipment.getCustomer().getId(), arriving);
        requireDistinctPackageNumbers(shipmentId, wanted);

        Set<Long> wantedIds = wanted.stream().map(ShipmentPackage::getId).collect(Collectors.toSet());
        List<ShipmentPackage> leaving = shipment.getPackages().stream()
                .filter(shipmentPackage -> !wantedIds.contains(shipmentPackage.getId()))
                .toList();

        if (!leaving.isEmpty()) {
            leaving.forEach(shipment::removePackage);
            // Written before anything arrives: a package leaving and one arriving with the same
            // package number would otherwise sit in the shipment together until the flush, and the
            // flush order decides whether uk_shipment_package_number sees them both.
            shipmentPackageRepository.flush();
        }
        arriving.forEach(shipment::addPackage);

        return toResponse(shipment);
    }

    /** Takes one package out; it is free again. The last package stays - the shipment is never empty. */
    @Override
    @Transactional
    public ShipmentResponse removeShipmentPackage(Long shipmentId, Long shipmentPackageId) {
        Shipment shipment = findChangeableShipmentForUpdate(shipmentId);
        ShipmentPackage shipmentPackage = findPackagesForUpdate(List.of(shipmentPackageId)).getFirst();

        if (!isIn(shipmentPackage, shipment)) {
            throw new APIException(HttpStatus.BAD_REQUEST,
                    "ShipmentPackage " + shipmentPackageId + " is not in Shipment " + shipmentId);
        }
        if (shipment.getPackages().size() == 1) {
            throw new APIException(HttpStatus.BAD_REQUEST, "ShipmentPackage " + shipmentPackageId
                    + " is the last package of Shipment " + shipmentId + " - a shipment needs at least one");
        }
        shipment.removePackage(shipmentPackage);

        return toResponse(shipment);
    }

    /** Address, method and requested delivery date - the customer is bound to the packages. */
    @Override
    @Transactional
    public ShipmentResponse updateShipmentData(Long shipmentId, UpdateShipmentRequest request) {
        if (request.shippingAddress() == null || request.shippingAddress().isBlank()) {
            throw new APIException(HttpStatus.BAD_REQUEST, "shippingAddress is required");
        }

        Shipment shipment = shipmentRepository.findForUpdateById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment", "id", shipmentId));

        if (!DATA_CHANGEABLE_IN.contains(shipment.getStatus())) {
            throw new APIException(HttpStatus.CONFLICT, "Shipment " + shipmentId + " is "
                    + shipment.getStatus() + ", its data can only be changed up to " + ShipmentStatus.ACCEPTED);
        }

        shipment.setShippingAddress(request.shippingAddress().trim());
        shipment.setShippingMethod(request.shippingMethod());
        shipment.setRequestedDeliveryDate(request.requestedDeliveryDate());
        shipment.setComment(blankToNull(request.comment()));

        return toResponse(shipment);
    }

    /**
     * One page of shipments, mapped while the transaction is open. Two queries like the package list
     * in PackageQueryServiceImpl: the page with the customers, then the packages of that page - a
     * collection fetch in the page query would make Hibernate page in memory.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<ShipmentListDto> findShipments(ShipmentStatus status, Pageable pageable) {
        Page<Shipment> page = status == null
                ? shipmentRepository.findAllWithCustomerBy(pageable)
                : shipmentRepository.findAllWithCustomerByStatus(status, pageable);
        if (page.isEmpty()) {
            return page.map(ShipmentListDto::from);
        }

        List<Long> ids = page.getContent().stream().map(Shipment::getId).toList();
        Map<Long, Shipment> withPackages = shipmentRepository.findWithPackagesByIdIn(ids).stream()
                .collect(Collectors.toMap(Shipment::getId, Function.identity(), (first, same) -> first));

        // The page decides order and totals; the second query only supplies the packages.
        return page.map(shipment -> ShipmentListDto.from(withPackages.getOrDefault(shipment.getId(), shipment)));
    }

    /**
     * Mapped here, while the transaction is open, like every other answer of this service: the
     * package contents come in one query through {@link #toResponse}, not one per package from the
     * open-in-view session.
     */
    @Override
    @Transactional(readOnly = true)
    public ShipmentResponse findShipment(Long shipmentId) {
        return toResponse(loadShipment(shipmentId));
    }

    private Shipment loadShipment(Long shipmentId) {
        return shipmentRepository.findWithPackagesById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment", "id", shipmentId));
    }

    @Override
    @Transactional
    public ShipmentResponse assignDistributor(Long shipmentId, Long distributorId) {
        var shipment = loadShipment(shipmentId);

        var allowedStatuses = Set.of(ShipmentStatus.READY, ShipmentStatus.DISPATCH_REQUESTED);

        if(!allowedStatuses.contains(shipment.getStatus())) {
            throw new APIException(HttpStatus.CONFLICT, "Shipment " + shipmentId + " is "
                    + shipment.getStatus() + ", a distributor can only be assigned to shipments in READY or DISPATCH_REQUESTED status");
        }

        // Checked before it is used as one, and on the unproxied instance: a cast up front fails any
        // other user with a ClassCastException (a 500), and a User proxy is never a Distributor.
        User user = userRepository.findById(distributorId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", distributorId));
        if (!(Hibernate.unproxy(user) instanceof Distributor distributor)) {
            throw new APIException(HttpStatus.BAD_REQUEST, "User " + distributorId + " is not a distributor");
        }
        shipment.setDistributor(distributor);
        shipment.setStatus(ShipmentStatus.DISPATCH_REQUESTED);
        shipmentRepository.save(shipment);
        return toResponse(shipment);
    }

    /** The orders this shipment carries - which of them actually move is OrderProgressService's call. */
    private List<Long> orderIdsOf(Long shipmentId) {
        return orderRepository.findByShipmentId(shipmentId).stream().map(Order::getId).toList();
    }

    /**
     * Calls the shipment off and undoes what it had already set in motion:
     * <ul>
     *     <li>its packages are free again - still PACKED, ready for another shipment;</li>
     *     <li>lines that checkShipmentReady had taken to READY_FOR_DISPATCH go back to PACKED;</li>
     *     <li>orders that accept had taken to READY_FOR_DISPATCH go back to IN_FULFILLMENT, unless a
     *     line of theirs travels in another shipment that is already further along.</li>
     * </ul>
     * Read before anything is detached: the lines and orders are found over the packages, and a
     * package taken out of the shipment is no longer part of that query.
     */
    @Override
    @Transactional
    public ShipmentResponse cancelShipment(Long shipmentId, CancelShipmentRequest request, Long userId) {
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            throw new APIException(HttpStatus.BAD_REQUEST, "reason is required");
        }

        Shipment shipment = shipmentRepository.findForUpdateById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment", "id", shipmentId));

        if (!CANCELLABLE_IN.contains(shipment.getStatus())) {
            throw new APIException(HttpStatus.CONFLICT, "Shipment " + shipmentId + " is "
                    + shipment.getStatus() + ", it can only be cancelled up to " + ShipmentStatus.ACCEPTED);
        }

        List<OrderItem> lines = orderItemRepository.findByShipmentId(shipmentId);
        List<Long> orderIds = orderIdsOf(shipmentId);

        // The packages go back to being loose - a cancelled shipment keeps nothing.
        List.copyOf(shipment.getPackages()).forEach(shipment::removePackage);

        List<OrderItem> backToPacked = lines.stream()
                .filter(line -> line.getFulfillmentStatus() == FulfillmentStatus.READY_FOR_DISPATCH)
                .toList();
        backToPacked.forEach(line -> line.setFulfillmentStatus(FulfillmentStatus.PACKED));
        orderItemRepository.saveAll(backToPacked);

        orderProgress.takeBackFromDispatch(orderIds, userId);

        shipment.setStatus(ShipmentStatus.CANCELLED);
        shipment.setComment(request.reason().trim());
        shipmentRepository.save(shipment);

        return toResponse(shipment);
    }

    @Override
    @Transactional
    public ShipmentResponse checkShipmentReady(Long shipmentId, Long userId) {
        // Locked like every other change of a shipment: the check reads the packages and then writes,
        // and a package taken out in between would leave a shipment reported ready over a picture
        // that no longer holds.
        Shipment shipment = shipmentRepository.findForUpdateById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment", "id", shipmentId));

        // Only from CREATED: on a shipment already on its way this call would take lines that were
        // packed again in the meantime back to READY_FOR_DISPATCH.
        if (shipment.getStatus() != ShipmentStatus.CREATED) {
            throw new APIException(HttpStatus.CONFLICT, "Shipment " + shipmentId + " is "
                    + shipment.getStatus() + ", only a CREATED shipment can be reported ready");
        }

        if (shipment.getShippingAddress() == null || shipment.getShippingAddress().isBlank()) {
            throw new APIException(HttpStatus.CONFLICT, "Shipment " + shipmentId + " has no shipping address");
        }

        // allMatch answers true for an empty list - a shipment without packages would be "ready".
        if (shipment.getPackages().isEmpty()) {
            throw new APIException(HttpStatus.CONFLICT, "Shipment " + shipmentId + " holds no packages");
        }

        boolean allPackagesPacked = shipment.getPackages().stream()
                .allMatch(pkg -> pkg.getShipmentPackageStatus() == ShipmentPackageStatus.PACKED);

        if (!allPackagesPacked) {
            throw new APIException(HttpStatus.CONFLICT, "Shipment " + shipmentId + " is not ready for dispatch: not all packages are packed");
        }

        // Only now, with every package closed, are the lines really on their way out: each
        // PackageItem names the line it was packed from, and PACKED means its ordered quantity is
        // fully packed. A line still PACKING belongs to a package that is not in this shipment, and a
        // line already further along travels in another shipment - forwards only, like the orders.
        List<OrderItem> lines = orderItemRepository.findByShipmentId(shipmentId).stream()
                .filter(line -> line.getFulfillmentStatus() == FulfillmentStatus.PACKED)
                .toList();
        lines.forEach(line -> line.setFulfillmentStatus(FulfillmentStatus.READY_FOR_DISPATCH));
        orderItemRepository.saveAll(lines);

        // The orders follow their lines: READY_FOR_DISPATCH is the warehouse reporting them ready
        // for the distributor, which is this step and not the one where the distributor answers.
        // Which of them really move is OrderProgressService's rule - an order whose other lines
        // travel in a shipment that is already further along is left where it is.
        orderProgress.advance(orderIdsOf(shipmentId), OrderStatus.READY_FOR_DISPATCH, userId);

        shipment.setStatus(ShipmentStatus.READY);
        shipmentRepository.save(shipment);

        return toResponse(shipment);
    }

    // ------------------------------------------------------------------ checks

    /** The rules of the DTO again, for every caller that does not come through a validated controller. */
    /**
     * The customer a package goes to, read from its first item - a package holds the items of one
     * order only, so any item names the same customer. requireShippable then checks every package.
     * An empty package goes to no customer: a 400, not the NoSuchElementException getFirst() throws.
     */
    private static Long customerOf(ShipmentPackage shipmentPackage) {
        if (shipmentPackage.getItems().isEmpty()) {
            throw new APIException(HttpStatus.BAD_REQUEST, "ShipmentPackage " + shipmentPackage.getId()
                    + " holds no items, so it goes to no customer");
        }
        return shipmentPackage.getItems().getFirst().getOrderItem().getOrder().getCustomer().getId();
    }

    /** Trimmed, and null for a blank value - so it is left out of the response instead of shown as "". */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void requireValidRequest(CreateShipmentRequest request) {
        if (request.shipmentPackageIds() == null || request.shipmentPackageIds().isEmpty()) {
            throw new APIException(HttpStatus.BAD_REQUEST, "At least one shipment package is required");
        }
        // Not contains(null): an immutable list such as List.of(...) answers that with an NPE.
        if (request.shipmentPackageIds().stream().anyMatch(Objects::isNull)) {
            throw new APIException(HttpStatus.BAD_REQUEST, "a shipment package id must not be null");
        }
    }

    /**
     * The user behind the id has to be a Customer - Shipment.customer is typed that way, while an
     * order's customer is any User. Unproxied first: a User proxy already in the persistence context
     * would never be an instance of the subclass.
     */
    private Customer findCustomer(Long customerId) {
        User user = userRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", customerId));
        if (!(Hibernate.unproxy(user) instanceof Customer customer)) {
            throw new APIException(HttpStatus.BAD_REQUEST, "User " + customerId + " is not a customer");
        }
        return customer;
    }

    /** Locked, and only while CREATED - a shipment handed on keeps what it holds. */
    private Shipment findChangeableShipmentForUpdate(Long shipmentId) {
        Shipment shipment = shipmentRepository.findForUpdateById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment", "id", shipmentId));

        if (shipment.getStatus() != ShipmentStatus.CREATED) {
            throw new APIException(HttpStatus.CONFLICT, "Shipment " + shipmentId + " is "
                    + shipment.getStatus() + ", only a CREATED shipment can be changed");
        }
        return shipment;
    }

    /** Locked, each id once; an id that does not exist is a 404 naming every missing one. */
    private List<ShipmentPackage> findPackagesForUpdate(List<Long> shipmentPackageIds) {
        Set<Long> ids = new TreeSet<>(shipmentPackageIds);
        List<ShipmentPackage> packages = shipmentPackageRepository.findAllForUpdateByIdIn(ids);
        if (packages.size() != ids.size()) {
            Set<Long> found = packages.stream().map(ShipmentPackage::getId).collect(Collectors.toSet());
            List<Long> missing = ids.stream().filter(id -> !found.contains(id)).toList();
            throw new APIException(HttpStatus.NOT_FOUND, "ShipmentPackage not found: " + missing);
        }
        return packages;
    }

    /**
     * What a package needs to go into a shipment of this customer:
     * <ul>
     *     <li>not in another shipment - 409, never moved silently;</li>
     *     <li>PACKED - an OPEN package can still change, a DISPATCHED one is gone - 409;</li>
     *     <li>not empty and for this customer - its items belong to an order of the customer - 400.</li>
     * </ul>
     * The customers are looked up for all packages in one query.
     *
     * @param shipment the shipment the packages go into, null while it is being created
     */
    private void requireShippable(Shipment shipment, Long customerId, List<ShipmentPackage> packages) {
        if (packages.isEmpty()) {
            return;
        }

        for (ShipmentPackage shipmentPackage : packages) {
            if (shipmentPackage.getShipment() != null
                    && (shipment == null || !isIn(shipmentPackage, shipment))) {
                throw new APIException(HttpStatus.CONFLICT, "ShipmentPackage " + shipmentPackage.getId()
                        + " is already in Shipment " + shipmentPackage.getShipment().getId());
            }
            if (shipmentPackage.getShipmentPackageStatus() != ShipmentPackageStatus.PACKED) {
                throw new APIException(HttpStatus.CONFLICT, "ShipmentPackage " + shipmentPackage.getId() + " is "
                        + shipmentPackage.getShipmentPackageStatus() + ", only PACKED packages can be shipped");
            }
        }

        Map<Long, Set<Long>> customersByPackage = shipmentPackageRepository
                .findCustomersByShipmentPackageIdIn(packages.stream().map(ShipmentPackage::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(ShipmentPackageRepository.PackageCustomer::getShipmentPackageId,
                        Collectors.mapping(ShipmentPackageRepository.PackageCustomer::getCustomerId,
                                Collectors.toCollection(HashSet::new))));

        for (ShipmentPackage shipmentPackage : packages) {
            Set<Long> customers = customersByPackage.get(shipmentPackage.getId());
            if (customers == null) {
                throw new APIException(HttpStatus.BAD_REQUEST, "ShipmentPackage " + shipmentPackage.getId()
                        + " holds no items, so it goes to no customer");
            }
            if (!customers.equals(Set.of(customerId))) {
                throw new APIException(HttpStatus.BAD_REQUEST, "ShipmentPackage " + shipmentPackage.getId()
                        + " holds items of customer " + customers + ", the shipment goes to customer " + customerId);
            }
        }
    }

    /**
     * uk_shipment_package_number is (shipment_id, package_number) and only bites once the packages
     * point at a shipment - two with the same number would fail the flush with a 500. Packages without
     * a number never collide: NULLs are distinct in a unique index.
     *
     * @param shipmentId null while the shipment is being created
     */
    private static void requireDistinctPackageNumbers(Long shipmentId, List<ShipmentPackage> contents) {
        Map<String, List<Long>> idsByNumber = contents.stream()
                .filter(shipmentPackage -> shipmentPackage.getPackageNumber() != null)
                .collect(Collectors.groupingBy(ShipmentPackage::getPackageNumber, LinkedHashMap::new,
                        Collectors.mapping(ShipmentPackage::getId, Collectors.toList())));

        idsByNumber.forEach((number, ids) -> {
            if (ids.size() > 1) {
                throw new APIException(HttpStatus.CONFLICT, "Package number " + number + " would be in "
                        + (shipmentId == null ? "the new shipment" : "Shipment " + shipmentId)
                        + " twice, as shipment packages " + ids);
            }
        });
    }

    /** True if the package is already in the shipment, false if it is free or in another one. */
    private static boolean isIn(ShipmentPackage shipmentPackage, Shipment shipment) {
        return shipmentPackage.getShipment() != null
                && Objects.equals(shipmentPackage.getShipment().getId(), shipment.getId());
    }

    /**
     * The response with every package's contents - fetched for all packages in one query. The query
     * also flushes what this transaction changed, and hands back the instances already loaded.
     */
    private ShipmentResponse toResponse(Shipment shipment) {
        List<Long> ids = shipment.getPackages().stream().map(ShipmentPackage::getId).toList();
        Map<Long, ShipmentPackage> withContents = shipmentPackageRepository.findWithItemsByIdIn(ids).stream()
                        .collect(Collectors.toMap(ShipmentPackage::getId, Function.identity(), (first, same) -> first));

        List<ShipmentPackage> packages = shipment.getPackages().stream()
                .sorted(Comparator.comparing(ShipmentPackage::getId))
                .map(shipmentPackage -> withContents.getOrDefault(shipmentPackage.getId(), shipmentPackage))
                .toList();
        String message = shipment.getDistributor() == null ? "No distributor assigned" : null;
        return ShipmentResponse.from(shipment, packages, message);
    }
}
