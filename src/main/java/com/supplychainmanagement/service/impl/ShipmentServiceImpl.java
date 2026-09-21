package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.dto.shipping.CreateShipmentRequest;
import com.supplychainmanagement.dto.shipping.ShipmentListDto;
import com.supplychainmanagement.dto.shipping.ShipmentPackageIdsRequest;
import com.supplychainmanagement.dto.shipping.ShipmentResponse;
import com.supplychainmanagement.dto.shipping.UpdateShipmentRequest;
import com.supplychainmanagement.entity.Shipment;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.entity.users.Customer;
import com.supplychainmanagement.entity.users.Distributor;
import com.supplychainmanagement.entity.users.User;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
import com.supplychainmanagement.model.enums.ShipmentStatus;
import com.supplychainmanagement.repository.ShipmentPackageRepository;
import com.supplychainmanagement.repository.ShipmentRepository;
import com.supplychainmanagement.repository.UserRepository;
import com.supplychainmanagement.service.ShipmentService;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class ShipmentServiceImpl implements ShipmentService {

    private final ShipmentRepository shipmentRepository;
    private final ShipmentPackageRepository shipmentPackageRepository;
    private final UserRepository userRepository;

    /**
     * A shipment is created together with its packages, never empty: the customer and at least one
     * package of that customer. Every package is checked before anything is written - see
     * {@link #requireShippable}.
     */
    @Override
    @Transactional
    public ShipmentResponse createShipment(CreateShipmentRequest request) {
        requireValidRequest(request);
        Customer customer = findCustomer(request.customerId());

        List<ShipmentPackage> packages = findPackagesForUpdate(request.shipmentPackageIds());
        requireShippable(null, customer.getId(), packages);
        requireDistinctPackageNumbers(null, packages);

        Shipment shipment = new Shipment();
        shipment.setCustomer(customer);
        shipment.setStatus(ShipmentStatus.CREATED);
        shipment.setShippingAddress(request.shippingAddress().trim());
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

        Shipment shipment = findChangeableShipmentForUpdate(shipmentId);
        shipment.setShippingAddress(request.shippingAddress().trim());
        shipment.setShippingMethod(request.shippingMethod());
        shipment.setRequestedDeliveryDate(request.requestedDeliveryDate());

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

        var allowedStatuses = Set.of(ShipmentStatus.CREATED, ShipmentStatus.READY, ShipmentStatus.DISPATCH_REQUESTED);

        if(!allowedStatuses.contains(shipment.getStatus())) {
            throw new APIException(HttpStatus.CONFLICT, "Shipment " + shipmentId + " is "
                    + shipment.getStatus() + ", a distributor can only be assigned to shipments in CREATED, READY, or DISPATCH_REQUESTED status");
        }

        // Checked before it is used as one, and on the unproxied instance: a cast up front fails any
        // other user with a ClassCastException (a 500), and a User proxy is never a Distributor.
        User user = userRepository.findById(distributorId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", distributorId));
        if (!(Hibernate.unproxy(user) instanceof Distributor distributor)) {
            throw new APIException(HttpStatus.BAD_REQUEST, "User " + distributorId + " is not a distributor");
        }
        shipment.setDistributor(distributor);
        return toResponse(shipment);
    }

    // ------------------------------------------------------------------ checks

    /** The rules of the DTO again, for every caller that does not come through a validated controller. */
    private static void requireValidRequest(CreateShipmentRequest request) {
        if (request == null || request.customerId() == null) {
            throw new APIException(HttpStatus.BAD_REQUEST, "customerId is required");
        }
        if (request.shipmentPackageIds() == null || request.shipmentPackageIds().isEmpty()) {
            throw new APIException(HttpStatus.BAD_REQUEST, "At least one shipment package is required");
        }
        // Not contains(null): an immutable list such as List.of(...) answers that with an NPE.
        if (request.shipmentPackageIds().stream().anyMatch(Objects::isNull)) {
            throw new APIException(HttpStatus.BAD_REQUEST, "a shipment package id must not be null");
        }
        if (request.shippingAddress() == null || request.shippingAddress().isBlank()) {
            throw new APIException(HttpStatus.BAD_REQUEST, "shippingAddress is required");
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
        Map<Long, ShipmentPackage> withContents = ids.isEmpty() ? Map.of()
                : shipmentPackageRepository.findWithItemsByIdIn(ids).stream()
                        .collect(Collectors.toMap(ShipmentPackage::getId, Function.identity(), (first, same) -> first));

        List<ShipmentPackage> packages = shipment.getPackages().stream()
                .sorted(Comparator.comparing(ShipmentPackage::getId))
                .map(shipmentPackage -> withContents.getOrDefault(shipmentPackage.getId(), shipmentPackage))
                .toList();
        return ShipmentResponse.from(shipment, packages);
    }
}
