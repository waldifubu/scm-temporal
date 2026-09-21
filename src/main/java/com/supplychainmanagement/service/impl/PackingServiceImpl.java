package com.supplychainmanagement.service.impl;

import com.supplychainmanagement.annotation.NoCheck;
import com.supplychainmanagement.dto.shipping.CreatePackageItemsRequest;
import com.supplychainmanagement.dto.shipping.CreatePackageRequest;
import com.supplychainmanagement.dto.shipping.PackItem;
import com.supplychainmanagement.dto.shipping.PackageItemIdsRequest;
import com.supplychainmanagement.dto.shipping.UpdatePackageRequest;
import com.supplychainmanagement.entity.OrderItem;
import com.supplychainmanagement.entity.PackageItem;
import com.supplychainmanagement.entity.ShipmentPackage;
import com.supplychainmanagement.exception.APIException;
import com.supplychainmanagement.exception.ResourceNotFoundException;
import com.supplychainmanagement.model.enums.FulfillmentStatus;
import com.supplychainmanagement.model.enums.ShipmentPackageStatus;
import com.supplychainmanagement.model.enums.ShipmentPackageType;
import com.supplychainmanagement.repository.OrderItemRepository;
import com.supplychainmanagement.repository.PackageItemRepository;
import com.supplychainmanagement.repository.ShipmentPackageRepository;
import com.supplychainmanagement.service.PackingService;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@AllArgsConstructor
public class PackingServiceImpl implements PackingService {

    private final OrderItemRepository orderItemRepository;
    private final ShipmentPackageRepository shipmentPackageRepository;
    private final PackageItemRepository packageItemRepository;

    /**
     * Rejects the whole request before a single line is locked or changed.
     * <p>
     * Up front and not in packLine, because packLine skips a line that is not ready to be packed
     * before it ever reads the quantity - a qty of 0 on such a line would pass silently. A quantity
     * below 1 is invalid whatever state its line is in. Thrown as APIException, so the client gets a
     * 400; an IllegalArgumentException would end up as a 500 in GlobalExceptionHandler.
     * <p>
     * The request DTOs carry the same rules as bean validation. This is the guard for every caller
     * that does not come through a validated controller parameter.
     */
    private static void requireValidItems(List<PackItem> items) {
        if (items == null || items.isEmpty()) {
            throw new APIException(HttpStatus.BAD_REQUEST, "At least one item is required");
        }

        for (PackItem item : items) {
            if (item.orderItemId() == null) {
                throw new APIException(HttpStatus.BAD_REQUEST, "orderItemId is required");
            }
            if (item.qty() == null || item.qty() < 1) {
                throw new APIException(HttpStatus.BAD_REQUEST,
                        "Quantity must be at least 1 for OrderItem " + item.orderItemId() + ", was: " + item.qty());
            }
        }
    }

    /**
     * The 400 for a request that packed nothing, naming every skipped line with its reason - a bare
     * "No valid items to pack" leaves the caller guessing whether the line was not picked yet,
     * already dispatched or simply full. A line listed twice in the request is named once.
     */
    private static APIException nothingToPack(String message, List<String> skipped) {
        if (skipped.isEmpty()) {
            return new APIException(HttpStatus.BAD_REQUEST, message);
        }

        return new APIException(HttpStatus.BAD_REQUEST,
                message + ": " + String.join("; ", skipped.stream().distinct().toList()));
    }

    //@TODO: This method is not used anywhere, so it can be removed. The validation is done in packLine and requireValidItems.
    @NoCheck
    @Override
    public void validateOrderItemPacking(OrderItem orderItem) {
        if (orderItem.getFulfillmentStatus() == FulfillmentStatus.READY_FOR_DISPATCH) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Order item " + orderItem.getId() + " is already in READY_FOR_DISPATCH status, cannot move to PACKED");
        }
        if (orderItem.getFulfillmentStatus() == FulfillmentStatus.PACKED) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Order item " + orderItem.getId() + " is already in PACKED status, cannot move to PACKED");
        }
        /*
        if (orderItem.getFulfillmentStatus() != FulfillmentStatus.PACKING) {
            throw new APIException(HttpStatus.BAD_REQUEST, "Order item " + orderItem.getId() + " is not in PACKING status, cannot move to PACKED");
        }*/
    }

    @Override
    @Transactional
    public ShipmentPackage createShipmentPackage(Long orderNo, CreatePackageRequest request) {
        requireValidItems(request.items());

        ShipmentPackage shipmentPackage = newShipmentPackage(request);

        UUID runNo = UUID.randomUUID();
        List<String> skipped = new ArrayList<>();
        // Processed in a stable order because packLine locks each line: two concurrent requests
        // touching the same two lines in opposite orders would otherwise deadlock each other.
        request.items().stream()
                .sorted(Comparator.comparing(PackItem::orderItemId))
                .forEach(packItem -> packLine(packItem, orderNo, runNo, shipmentPackage.getItems(), skipped)
                        .filter(packageItem -> addToRun(shipmentPackage.getItems(), packageItem))
                        .ifPresent(packageItem -> packageItem.setShipmentPackage(shipmentPackage)));

        // Every line was skipped, so there is nothing to ship - an empty package is not a result.
        if (shipmentPackage.getItems().isEmpty()) {
            throw nothingToPack("No valid items to pack for order " + orderNo, skipped);
        }

        return shipmentPackageRepository.save(shipmentPackage);
    }

    /**
     * Packs order lines without putting them into a package: the items only share a runNo, which is
     * how they are found again as one packing run.
     * <p>
     * Same rules as {@link #createShipmentPackage} - every line is locked, measured against its
     * ordered quantity including what this run already holds, and advanced to PACKING or PACKED.
     * Unlike there, no order number narrows the request down, so the lines may belong to different
     * orders.
     */
    @Override
    @Transactional
    public List<PackageItem> createPackageItems(CreatePackageItemsRequest request) {
        requireValidItems(request.items());

        UUID runNo = UUID.randomUUID();
        List<PackageItem> packageItems = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        // Stable order for the same reason as in createShipmentPackage: packLine locks each line.
        request.items().stream()
                .sorted(Comparator.comparing(PackItem::orderItemId))
                // No separate lookup or status validation up front - packLine covers both, on the
                // locked row: an unknown id is a 404, a line already fully packed (PACKED or beyond)
                // is skipped, more than the room left is a 400. An unlocked findById before it would
                // also have validated a state read before the lock was taken.
                .forEach(packItem -> packLine(packItem, null, runNo, packageItems, skipped)
                        .ifPresent(packageItem -> addToRun(packageItems, packageItem)));

        // Every line was skipped - a run that packed nothing is not a result.
        if (packageItems.isEmpty()) {
            throw nothingToPack("No valid items to pack", skipped);
        }

        return packageItemRepository.saveAll(packageItems);
    }

    /**
     * Builds the package item for one order line, or nothing if the line is not ready to be packed.
     * The caller decides where the item goes - into a package or into a run of loose items.
     * <p>
     * Two kinds of lines are skipped rather than rejected: one that is neither PICKED nor PACKING, and
     * one whose ordered quantity is already fully packed - by earlier package items or by this very
     * run. A caller may hand in a whole order, or repeat a line, and let whatever is still open be
     * packed. A line with some room left that is asked for more than that room is still an error.
     * The emptiness checks in the callers are what turn "nothing left to pack at all" into one.
     *
     * @param orderNo   the order every line has to belong to, or null if the caller does not narrow
     *                  the request down to one order
     * @param inThisRun the items this call has built so far - not written yet, so the database total
     *                  cannot see them
     * @param skipped   receives why a line was skipped, for the error when nothing at all is packed
     */
    private Optional<PackageItem> packLine(PackItem packItem, Long orderNo, UUID runNo,
                                           List<PackageItem> inThisRun, List<String> skipped) {
        // Locked, not just read: see OrderItemRepository.findForUpdateById. The lock is held until
        // createPackage commits, so a concurrent packer waits and then sees the updated total.
        // Every failure below is the caller's: a 404 or a 400, never an unchecked exception that
        // GlobalExceptionHandler would have to answer with a 500.
        OrderItem orderItem = orderItemRepository.findForUpdateById(packItem.orderItemId()).orElseThrow(
                () -> new ResourceNotFoundException("OrderItem", "id", packItem.orderItemId())
        );

        if (orderNo != null && !Objects.equals(orderItem.getOrder().getOrderNo(), orderNo)) {
            throw new APIException(HttpStatus.BAD_REQUEST,
                    "OrderItem " + orderItem.getId() + " does not belong to order " + orderNo);
        }

        // Quantities first, status second. Asking for more than the line has room for is an error
        // whatever state the line is in - checked after the status, a line already reading PACKED
        // (or not picked yet) would swallow the overflow as a silent skip.

        // Validated for the whole request in requireValidItems, before any line was locked.
        int requestedQuantity = packItem.qty();

        // Measured against what earlier packages of this line already hold, not against the ordered
        // quantity alone - the latter would let two half packages add up to more than was ordered.
        // The current run counts too: its items are not written yet, so the query cannot see them,
        // and the same line listed twice in one request would otherwise pass twice. The query itself
        // counts every package item of the line, with or without a package.
        int alreadyPacked = shipmentPackageRepository.sumQuantityByOrderItemId(orderItem.getId())
                + packedInThisRun(inThisRun, orderItem);

        // Nothing left to pack for this line: skipped like a line that is not ready. Decided by the
        // quantities, not by the status - PACKED normally says the same, but only if nothing ever
        // left the two out of step.
        if (alreadyPacked >= orderItem.getQuantity()) {
            skipped.add("OrderItem " + orderItem.getId() + " is already fully packed ("
                    + alreadyPacked + " of " + orderItem.getQuantity() + ")");
            return Optional.empty();
        }

        // Never clipped to the room left: packing less than was asked for would leave the caller
        // believing the full quantity is in the package.
        int newPackedTotal = alreadyPacked + requestedQuantity;
        if (newPackedTotal > orderItem.getQuantity()) {
            throw new APIException(HttpStatus.BAD_REQUEST,
                    "Cannot pack more than ordered qty for OrderItem " + orderItem.getId()
                            + ". Ordered: " + orderItem.getQuantity()
                            + ", already packed: " + alreadyPacked
                            + ", requested: " + requestedQuantity);
        }

        // Only a line with room for the request gets this far. One that is neither PICKED nor
        // PACKING is still skipped rather than rejected.
        FulfillmentStatus status = orderItem.getFulfillmentStatus();
        if (status != FulfillmentStatus.PICKED && status != FulfillmentStatus.PACKING) {
            skipped.add("OrderItem " + orderItem.getId() + " is " + status
                    + ", only PICKED or PACKING can be packed");
            return Optional.empty();
        }

        PackageItem packageItem = new PackageItem();
        packageItem.setOrderItem(orderItem);
        packageItem.setQuantity(requestedQuantity);
        packageItem.setRunNo(runNo);

        // PACKED only once every ordered unit sits in a package, PACKING while some are still open.
        // Assigned unconditionally: writing the status a line already has is a no-op, and guarding
        // against it only risked skipping whatever else the branch did.
        orderItem.setFulfillmentStatus(newPackedTotal == orderItem.getQuantity()
                ? FulfillmentStatus.PACKED
                : FulfillmentStatus.PACKING);

        return Optional.of(packageItem);
    }

    /**
     * Adds a freshly packed item to the run - or, when the run already holds an item of the same
     * order line, adds its quantity to that one instead.
     * <p>
     * One item per line and run: two entries of one line in one request belong to the same run, and
     * as two items in one package they would violate uq_package_item_order_item_run at flush time -
     * a 500. Folded only after packLine, so every entry is still measured on its own: 5 + 5 + 5 on a
     * line of 10 packs 10 and skips the third, 8 + 8 is still refused. packedInThisRun keeps counting
     * correctly, because the grown item carries the sum.
     *
     * @return true if the item was added as a new one, false if it was folded into an existing one
     */
    private static boolean addToRun(List<PackageItem> inThisRun, PackageItem packed) {
        Long lineId = packed.getOrderItem().getId();
        for (PackageItem existing : inThisRun) {
            if (Objects.equals(existing.getOrderItem().getId(), lineId)) {
                existing.setQuantity(existing.getQuantity() + packed.getQuantity());
                return false;
            }
        }
        inThisRun.add(packed);
        return true;
    }

    private int packedInThisRun(List<PackageItem> inThisRun, OrderItem orderItem) {
        return inThisRun.stream()
                .filter(item -> Objects.equals(item.getOrderItem().getId(), orderItem.getId()))
                .mapToInt(PackageItem::getQuantity)
                .sum();
    }

    private String generatePackageNumber() {
        LocalDateTime now = LocalDateTime.now();
        return "PKG-" + now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")) + "-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /**
     * A package without items, to be filled later. {@code items} may be missing from the request here
     * and is ignored if sent - see {@link CreatePackageRequest.WithItems}.
     */
    @Override
    @Transactional
    public ShipmentPackage createCustomShipment(CreatePackageRequest request) {
        var shipmentPackage = newShipmentPackage(request);

        if (request.items() != null && !request.items().isEmpty()) {
            requireValidItems(request.items());
            UUID runNo = UUID.randomUUID();
            List<String> skipped = new ArrayList<>();
            // Processed in a stable order because packLine locks each line: two concurrent requests
            // touching the same two lines in opposite orders would otherwise deadlock each other.
            request.items().stream()
                    .sorted(Comparator.comparing(PackItem::orderItemId))
                    .forEach(packItem -> packLine(packItem, null, runNo, shipmentPackage.getItems(), skipped)
                            .filter(packageItem -> addToRun(shipmentPackage.getItems(), packageItem))
                            .ifPresent(packageItem -> packageItem.setShipmentPackage(shipmentPackage)));

            // Every line was skipped, so there is nothing to ship - an empty package is not a result.
            if (shipmentPackage.getItems().isEmpty()) {
                throw nothingToPack("No valid items to pack for this shipment", skipped);
            }
        }

        return shipmentPackageRepository.save(shipmentPackage);
    }

    /**
     * Replaces the package's own data with the defaults a new package gets - see
     * {@link UpdatePackageRequest}. Its contents are changed through the item methods below.
     */
    @Override
    @Transactional
    public ShipmentPackage updatePackageData(Long shipmentPackageId, UpdatePackageRequest request) {
        ShipmentPackage shipmentPackage = findOpenPackageForUpdate(shipmentPackageId);

        applyPackageFields(shipmentPackage, request.shipmentPackageType(),
                request.length(), request.width(), request.height());
        if (request.packageNumber() != null) {
            shipmentPackage.setPackageNumber(request.packageNumber());
        }

        return shipmentPackageRepository.save(shipmentPackage);
    }

    /**
     * Puts loose package items into the package. An item already in this package is left as it is,
     * so repeating a call changes nothing.
     * <p>
     * No quantity check: the items were counted as packed when they were created, and moving them
     * into a package changes neither that count nor the status of their order lines.
     */
    @Override
    @Transactional
    public ShipmentPackage addPackageItems(Long shipmentPackageId, PackageItemIdsRequest request) {
        if (request.packageItemIds().isEmpty()) {
            throw new APIException(HttpStatus.BAD_REQUEST, "At least one package item id is required");
        }

        ShipmentPackage shipmentPackage = findOpenPackageForUpdate(shipmentPackageId);
        List<PackageItem> requested = findPackageItemsForUpdate(request.packageItemIds());
        requireLooseOrIn(shipmentPackage, requested);

        List<PackageItem> added = requested.stream()
                .filter(item -> !isIn(item, shipmentPackage))
                .toList();
        List<PackageItem> contents = Stream.concat(shipmentPackage.getItems().stream(), added.stream()).toList();
        requireOneOrder(shipmentPackageId, contents);
        requireOneItemPerLineAndRun(shipmentPackageId, contents);

        added.forEach(item -> attach(item, shipmentPackage));

        return shipmentPackageRepository.save(shipmentPackage);
    }

    /**
     * Makes the package hold exactly the given items. Those it held and that are not in the list go
     * back to being loose - never deleted, see ShipmentPackage.items. An empty list empties the
     * package.
     */
    @Override
    @Transactional
    public ShipmentPackage updateCustomShipment(Long shipmentPackageId, PackageItemIdsRequest request) {
        ShipmentPackage shipmentPackage = findOpenPackageForUpdate(shipmentPackageId);
        List<PackageItem> wanted = findPackageItemsForUpdate(request.packageItemIds());
        requireLooseOrIn(shipmentPackage, wanted);
        requireOneOrder(shipmentPackageId, wanted);
        requireOneItemPerLineAndRun(shipmentPackageId, wanted);

        Set<Long> wantedIds = wanted.stream().map(PackageItem::getId).collect(Collectors.toSet());
        List<PackageItem> leaving = shipmentPackage.getItems().stream()
                .filter(item -> !wantedIds.contains(item.getId()))
                .toList();

        if (!leaving.isEmpty()) {
            leaving.forEach(item -> detach(item, shipmentPackage));
            // Written before anything is attached: an item leaving and one arriving for the same
            // order line and run would otherwise sit in the package together until the flush, and
            // the flush order decides whether uq_package_item_order_item_run sees them both.
            packageItemRepository.flush();
        }
        wanted.stream()
                .filter(item -> !isIn(item, shipmentPackage))
                .forEach(item -> attach(item, shipmentPackage));

        return shipmentPackageRepository.save(shipmentPackage);
    }

    /** Takes one item out of the package; it goes back to being loose. */
    @Override
    @Transactional
    public ShipmentPackage removePackageItem(Long shipmentPackageId, Long packageItemId) {
        ShipmentPackage shipmentPackage = findOpenPackageForUpdate(shipmentPackageId);
        PackageItem item = findPackageItemsForUpdate(List.of(packageItemId)).getFirst();

        if (!isIn(item, shipmentPackage)) {
            throw new APIException(HttpStatus.BAD_REQUEST,
                    "PackageItem " + packageItemId + " is not in ShipmentPackage " + shipmentPackageId);
        }
        detach(item, shipmentPackage);

        return shipmentPackageRepository.save(shipmentPackage);
    }

    /**
     * Closes the package: OPEN to PACKED. From then on its contents are fixed and it can go into a
     * shipment. Never without items - an empty package goes to no customer and, once PACKED, could not
     * be filled any more.
     * <p>
     * Transactional like the content methods: the lock from findForUpdateById has to hold until the
     * status is written, or an item added at the same time could slip in after the check.
     */
    @Override
    @Transactional
    public ShipmentPackage completePackage(Long shipmentPackageId) {
        ShipmentPackage shipmentPackage = findOpenPackageForUpdate(shipmentPackageId);
        if (shipmentPackage.getItems().isEmpty()) {
            throw new APIException(HttpStatus.BAD_REQUEST,
                    "ShipmentPackage " + shipmentPackageId + " holds no items and cannot be completed");
        }
        shipmentPackage.complete();
        return shipmentPackageRepository.save(shipmentPackage);
    }

    /** Locked, and only while OPEN - a packed or dispatched package keeps what it holds. */
    private ShipmentPackage findOpenPackageForUpdate(Long shipmentPackageId) {
        ShipmentPackage shipmentPackage = shipmentPackageRepository.findForUpdateById(shipmentPackageId)
                .orElseThrow(() -> new ResourceNotFoundException("ShipmentPackage", "id", shipmentPackageId));

        if (shipmentPackage.getShipmentPackageStatus() != ShipmentPackageStatus.OPEN) {
            throw new APIException(HttpStatus.CONFLICT, "ShipmentPackage " + shipmentPackageId + " is "
                    + shipmentPackage.getShipmentPackageStatus() + ", only an OPEN package can be changed");
        }
        return shipmentPackage;
    }

    /** Locked, each id once; an id that does not exist is a 404 naming every missing one. */
    private List<PackageItem> findPackageItemsForUpdate(List<Long> packageItemIds) {
        Set<Long> ids = new TreeSet<>(packageItemIds);
        if (ids.isEmpty()) {
            return List.of();
        }

        List<PackageItem> items = packageItemRepository.findAllForUpdateByIdIn(ids);
        if (items.size() != ids.size()) {
            Set<Long> found = items.stream().map(PackageItem::getId).collect(Collectors.toSet());
            List<Long> missing = ids.stream().filter(id -> !found.contains(id)).toList();
            throw new APIException(HttpStatus.NOT_FOUND, "PackageItem not found: " + missing);
        }
        return items;
    }

    /** An item may be loose or already in this package - never taken silently out of another one. */
    private static void requireLooseOrIn(ShipmentPackage shipmentPackage, List<PackageItem> items) {
        for (PackageItem item : items) {
            if (item.getShipmentPackage() != null && !isIn(item, shipmentPackage)) {
                throw new APIException(HttpStatus.CONFLICT, "PackageItem " + item.getId()
                        + " is already in ShipmentPackage " + item.getShipmentPackage().getId());
            }
        }
    }

    /** A package goes to one customer, so it holds the items of one order only. */
    private static void requireOneOrder(Long shipmentPackageId, List<PackageItem> contents) {
        List<Long> orderIds = contents.stream()
                .map(item -> item.getOrderItem().getOrder().getId())
                .distinct()
                .toList();
        if (orderIds.size() > 1) {
            throw new APIException(HttpStatus.BAD_REQUEST, "ShipmentPackage " + shipmentPackageId
                    + " can only hold items of one order, got items of orders " + orderIds);
        }
    }

    /** One order line from one packing run - the key of uq_package_item_order_item_run. */
    private record LineRun(Long orderItemId, UUID runNo) {
    }

    /**
     * At most one item per order line and packing run in a package - uq_package_item_order_item_run
     * would otherwise fail at flush with a 500. Items of the same line from different runs may share a
     * package; the line's quantity there is the sum of its items. Two items of the same line from the
     * same run only exist when that run listed the line twice.
     * <p>
     * A record key rather than groupingBy over the runNo alone: legacy rows may carry no runNo, and
     * groupingBy refuses a null key. Two such items of one line are refused here although the database
     * would take them - NULLs are distinct in a unique index - which errs on the safe side.
     */
    private static void requireOneItemPerLineAndRun(Long shipmentPackageId, List<PackageItem> contents) {
        Map<LineRun, List<Long>> itemIdsByLineRun = contents.stream()
                .collect(Collectors.groupingBy(item -> new LineRun(item.getOrderItem().getId(), item.getRunNo()),
                        LinkedHashMap::new, Collectors.mapping(PackageItem::getId, Collectors.toList())));

        itemIdsByLineRun.forEach((lineRun, itemIds) -> {
            if (itemIds.size() > 1) {
                throw new APIException(HttpStatus.CONFLICT, "OrderItem " + lineRun.orderItemId()
                        + " from run " + lineRun.runNo() + " would be in ShipmentPackage " + shipmentPackageId
                        + " twice, as package items " + itemIds);
            }
        });
    }

    /** True if the item is already in the package, false if it is loose or in another package. */
    private static boolean isIn(PackageItem item, ShipmentPackage shipmentPackage) {
        return item.getShipmentPackage() != null
                && Objects.equals(item.getShipmentPackage().getId(), shipmentPackage.getId());
    }

    /** Through the package, which sets both sides of the relation and keeps its weight. */
    private static void attach(PackageItem item, ShipmentPackage shipmentPackage) {
        shipmentPackage.addItem(item);
    }

    private static void detach(PackageItem item, ShipmentPackage shipmentPackage) {
        shipmentPackage.removeItem(item);
    }

    /**
     * The package itself, without items - shared by {@link #createShipmentPackage} and
     * {@link #createCustomShipment}, so the two cannot drift apart in how a package is set up.
     */
    private ShipmentPackage newShipmentPackage(CreatePackageRequest request) {
        ShipmentPackage shipmentPackage = new ShipmentPackage();
        shipmentPackage.setPackageNumber(request.packageNumber() != null ? request.packageNumber() : generatePackageNumber());
        shipmentPackage.setShipmentPackageStatus(ShipmentPackageStatus.OPEN);
        applyPackageFields(shipmentPackage, request.shipmentPackageType(),
                request.length(), request.width(), request.height());

        return shipmentPackage;
    }

    /**
     * Type and dimensions, with the same defaults for a new package and an updated one: no type
     * means OTHER. The weight is not among them - the package computes it from its contents.
     */
    private static void applyPackageFields(ShipmentPackage shipmentPackage, ShipmentPackageType type,
                                           BigDecimal length, BigDecimal width, BigDecimal height) {
        shipmentPackage.setShipmentPackageType(type != null ? type : ShipmentPackageType.OTHER);
        shipmentPackage.setLength(length);
        shipmentPackage.setWidth(width);
        shipmentPackage.setHeight(height);
    }
}