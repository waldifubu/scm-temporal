# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Supply Chain Management demo app: Spring Boot 4.1.0 (Java 25) backend with two parallel UIs —
a Vaadin/React admin UI and a classic Thymeleaf server-rendered site — plus a JSON REST API.
Database is MariaDB, accessed via Spring Data JPA/Hibernate.

## Commands

Build/compile:
```
mvn -q compile
```

Run tests:
```
mvn test
mvn test -Dtest=ApplicationTests
```
`ApplicationTests` loads the full Spring context and therefore needs a reachable database and a
complete `application.properties` - it runs with no active profile, so anything defined only in
`application-dev.properties` is missing there. `CustomQueryExecutionTest` (runs every hand-written
query and entity graph once) and `ReservationDtoTest` load the context too - add new repository
queries to `CustomQueryExecutionTest`, a unit test mocks them away. Everything else is plain
Mockito/AssertJ and runs in a couple of seconds. Controller tests use standalone MockMvc with the
project's own version resolver from `ApiVersioningTestSupport`, so they call `/api/1.0/...` like a
client; security filters are not part of them. Context tests run against the same database as the
app, including any active startup migration in `config`.

Run the app (dev profile, uses `application-dev.properties`):
```
mvn -Dspring-boot.run.profiles=dev spring-boot:run
```
Default port is 8080 (`server.port` in `application.properties`). Logs are written to
`logs/app-${server.port}.log` in addition to stdout — tail that file to check startup status
(look for `Started Application` vs `APPLICATION FAILED TO START`).

Frontend (Vaadin/React, `src/main/frontend`) is built automatically by the
`vaadin-spring-boot-starter` as part of the Maven build/dev server; there is normally no need to
run `npm`/`vite` directly. `vaadin.launch-browser=true` opens a browser automatically in dev mode.

## Architecture

### Two UIs + one API, on separate Spring Security filter chains

`SpringSecurityConfig` defines three independently-ordered `SecurityFilterChain` beans, each
scoped with `.securityMatcher(...)` so their rules never interact:
1. `apiFilterChain` (`/api/**`) — stateless, JWT-authenticated, CSRF disabled.
2. `vaadinFilterChain` (`/app/**`) — Vaadin's own `VaadinSecurityConfigurer`, form login via `LoginView`.
3. `webFilterChain` (everything else) — classic Thymeleaf pages, session-based form login, CSRF stays on.

Vaadin is deliberately mounted under `/app/*` (`vaadin.url-mapping=/app/*`) so it doesn't compete
with the Thymeleaf `/` route in `WebController`. Vaadin views live in
`service...vaadin/views` (actually `com.supplychainmanagement.vaadin.views`); the newer React-based
UI lives in `src/main/frontend` (Vaadin+React via `@vaadin/react-components`, routes generated into
`src/main/frontend/generated`).

Auth for the API is JWT-based: `JwtAuthenticationFilter` + `JwtTokenProvider` +
`JwtAuthenticationEntryPoint` (`security/`). Roles are `RoleEnum` (`CUSTOMER`, `MANAGER`,
`SUPPLIER`, `WAREHOUSE`, `LOGISTICS`, `DISTRIBUTOR`, `ADMIN`); controllers authorize with
`@PreAuthorize("hasAnyAuthority(...)")`. `User` (`entity/users`) is a single-table-inheritance
hierarchy (`Admin`, `Customer`, `Distributor`, `Logistics`, `Manager`, `Supplier`, `Warehouse`)
discriminated by `user_type`.

### API versioning

This project uses Spring MVC's native API versioning (`version = "1.0"` attribute on
`@GetMapping`/`@PostMapping`, etc. — see `OrderController`), configured in `WebConfig` via a custom
`useVersionResolver` that reads the version from the second URL path segment
(`/api/1.0/...` → `"1.0"`). `ApiVersionDefaultFilter` (highest filter precedence) rewrites any
unversioned `/api/...` request to `/api/{spring.mvc.apiversion.default}/...` *before* Spring MVC or
Security see it, so clients can omit the version and still hit versioned endpoints. When adding a
new endpoint, follow the existing pattern: `@RequestMapping({"/api/{version}/...")` at class level,
`version = "x.y"` per mapping.

### Domain: Order → Fulfillment → Picking → Packing → Shipment → Dispatch

This is the most involved part of the codebase, spread across several services with distinct
responsibilities — read all of them together before changing reservation/fulfillment logic:

- **`Order`** has a coarse-grained `OrderStatus` (`CREATED → ACKNOWLEDGED → REVIEW → APPROVED →
  IN_FULFILLMENT → READY_FOR_DISPATCH → IN_TRANSIT → DELIVERED → COMPLETED`, plus
  `REJECTED`/`CANCELLED`). Driven by code up to `IN_FULFILLMENT`, and from there on by the shipment
  the order's packages travel in: `READY_FOR_DISPATCH` when the shipment is reported ready - the
  warehouse is done with it, which is what that status says - then `IN_TRANSIT` and `DELIVERED` with
  the carrier's steps. A cancelled shipment takes its orders back to `IN_FULFILLMENT` - the one
  backwards step besides the fallback to `APPROVED` in `releaseItems`. `COMPLETED` and `CANCELLED`
  are still unreachable on the order.
- **`OrderItem`** has a finer-grained `FulfillmentStatus` (`WAITING → RESERVED → PICKING → PICKED →
  PACKING → PACKED → READY_FOR_DISPATCH`), tracked per line item, and implemented end to end: the
  last step comes from the shipment (`PUT /shipments/{id}/ready`), not from
  `OrderHandlingService.readyForDispatch()`, whose endpoint is commented out in `ShipmentController`
  and which is left over. Note there is no `RESERVING` — it was removed
  because nothing could ever observe it inside the synchronous reserve transaction.
- **`ShipmentPackage`** has `ShipmentPackageStatus` (`OPEN → PACKED → DISPATCHED`): filled while
  `OPEN`, closed by `completePackage`, and `DISPATCHED` when its shipment reports `in-transit`.
  **`Shipment`** has `ShipmentStatus` (`CREATED → READY → DISPATCH_REQUESTED → ACCEPTED → IN_TRANSIT
  → DELIVERED`, plus `CANCELLED`): `CREATED` on insert, `READY` through
  `PUT /shipments/{id}/ready`, then the carrier's three steps, and `CANCELLED` through
  `POST /shipments/{id}/cancel` (see below). `DISPATCH_REQUESTED` is not set by code yet - a
  shipment goes from `READY` straight to `ACCEPTED`.

The chain is split by responsibility, not by entity. Which service owns which stretch - one
controller per service, named after what it does (`PickingController`, `PackingController`,
`PackageController`, `ShipmentController`):

| Stretch | Service | Controller |
|---------|---------|------------|
| availability check, production | `ProductionService` | `ProductionController` |
| `WAITING ⇄ RESERVED` | `FulfillmentService` | `InventoryController` |
| `RESERVED → PICKED` | `OrderHandlingService` | `PickingController` |
| `PICKED → PACKED` | `PackingService` | `PackingController` (`/packing/**`, plus `/order-items`, the picked lines to pack) |
| reading packages and package items | `PackageQueryService` | `PackageController` |
| `PACKED → READY_FOR_DISPATCH` (lines and their orders) | `ShipmentService.checkShipmentReady()` | `ShipmentController` (`PUT /shipments/{id}/ready`) |
| packages → shipment, ready, cancel | `ShipmentService` | `ShipmentController` (`/shipments/**`) |
| accept → in transit → delivered | `DeliveryService` | `ShipmentController` (same paths, DISTRIBUTOR) |
| every order status change (write + audit event) | `OrderProgressService` | — |
| tracking, returns | `DeliveryService` | — (not implemented) |

- **`ProductionService`** — `checkItems(order)` reports stock availability per line without
  reserving or writing anything; `produce()` builds finished products from component stock.
- **`FulfillmentService`** (`FulfillmentServiceImpl`) drives the reservation lifecycle:
  `reserveItems`/`releaseItems` advance/revert `OrderItem.fulfillmentStatus`. `reserveItems` also
  advances `Order.status` to `IN_FULFILLMENT`, but only from a pre-fulfillment status
  (`PRE_FULFILLMENT_STATUSES`), so a second idempotent call never regresses further-along orders.
  It asks `ProductionService.checkItems` for availability rather than querying stock itself.
  `releaseItems(order, username)` releases every active reservation the order holds - there is no
  variant for a subset any more - and returns what the inventory layer reports actually released.
  An order holding nothing yields an empty list, not an error, so the endpoint answers `200 []`
  rather than 404. It takes the acting user because a release takes the order back to `APPROVED`
  once nothing is held any more, and that transition wants an audit row. "Nothing held" includes
  the lines: once any line is past `RESERVED` (a picked line holds only a `CONSUMED` reservation,
  which does not count as active) the order stays `IN_FULFILLMENT`.
- **Never set a status on an entity a service will reload.** `update` compares the status it
  reloads against the one it is handed, and with `open-in-view` (default `true`) both are the same
  instance for the whole request - the change looks like none, so the status is written and the audit
  row is not. `acknowledge` and `OrderController`'s reject did exactly that and lost their history
  rows; both now go through `OrderProgressService.changeStatus`, and `reject` is a service method of
  its own (`OrderService.reject`) rather than a status the controller sets. `OrderStatusHistoryTest`
  guards it - a unit test cannot, because with mocked repositories the two loads are whatever the
  stub returns.
- **`OrderService.acknowledge`** accepts an incoming order and confirms a delivery date for it: two
  lead times in working days (`app.order.leadDays.inStock` / `.replenishment`, defaulted inline)
  depending on whether `checkItems` covers every line, weekends skipped, and a `dueDate` the
  customer asked for later than that wins. Only from `CREATED` - confirming an order already being
  fulfilled would throw it back.
- **`InventoryService`** (`InventoryServiceImpl`) is the retry/idempotency wrapper around actual
  reservation work — `reserveWithRetry`/`releaseWithRetry`/`consumeWithRetry`, all keyed by the
  numeric `Order.id` (`Long orderId`, not the order number).
- **`InventoryReservationTransactionService`** does the real DB work in its own `REQUIRES_NEW`
  transactions (`reserve`/`release`/`consume`), each iterating `ReserveItem`s and mutating `Stock`
  + `Reservation` together. `reserve` has an idempotency guard keyed by **order line**
  (`findActive(orderId)` up front, skip whatever already holds a reservation) plus
  `InventoryServiceImpl` catches `DataIntegrityViolationException` as a race-condition fallback —
  the unique constraint on `order_item_id` is the last line of defense, not the primary guard.
  Do not key any of this by SKU again. An order carries every article at most once - enforced by
  `uk_order_item_order_product` on `order_items`, and upheld by
  `OrderServiceImpl.mergeDuplicateProducts`, which folds a repeated article into the first line and
  adds up the quantities rather than rejecting the request - so SKU and line coincide. The quantity
  limit per line (`MAX_LINE_QUANTITY`, 20) is checked there, after the merge, as a 400 - not as
  `@Max` on `OrderItem`, which only fired at flush time as a 500. Keying by line is still the
  right choice: it says what a reservation belongs to instead of relying on that rule holding. The guard, the release/consume lookup and the RESERVED marking in `reserveItems` were all
  keyed by SKU and would all have failed together.
- **One line, at most one reservation.** A line is covered by a single storehouse or not at all -
  `ProductionServiceImpl.findEligibleStock` looks for one storehouse holding the *full* quantity and
  reports the line as unavailable otherwise. There is deliberately no splitting across storehouses,
  which is why uniqueness sits on `order_item_id` alone.
- **`Reservation` points at its `OrderItem`** through a unidirectional, LAZY `@OneToOne` on
  `order_item_id`. It has **no order id of its own** - the order is `orderItem.order`, and the
  reservations of an order are found through the line (`findByOrderItemOrderIdAndStatus`, a join
  over two indexed foreign keys). A copied `orderId` (a String next to the numeric `Order.id`) was
  removed: it could only ever disagree with the line. `sku` and `quantity` do stay denormalized on
  purpose: `Stock` is keyed by `(sku, storehouse)`, so the hot reserve/release path needs no join
  through the order item and product. `ReservationDto.of(reservation, orderId)` takes the order id
  from the caller - out of a closed `REQUIRES_NEW` session the order line is an uninitialized proxy.
  The relation is deliberately *not* bidirectional — a back reference would drag
  `Order → orderItems → reservation → orderItem` into every response serializing a reservation.
- **Transaction boundaries for picking sit at the line, not at the call.** The per-line work lives
  on `OrderHandlingTransactionService` (`REQUIRES_NEW`) and not as a private method of
  `OrderHandlingServiceImpl`, for two reasons: a private method called from the same bean goes
  around the proxy and is transactional in name only, and wrapping the whole loop would let a
  failure on line three roll back the line statuses of one and two while their stock has already
  been consumed by the REQUIRES_NEW `consumeWithRetry` - a reservation reading ACTIVE over stock
  that is gone. Per line, everything before the failure stays correctly and completely picked.
- **`pick` never writes the reservation itself.** `consume` sets `CONSUMED` and commits in its own
  `REQUIRES_NEW` transaction. The pick transaction took its snapshot before that commit, and MariaDB
  (`innodb_snapshot_isolation`, on by default since 11.6) refuses to update a row that changed after
  the snapshot: `Record has changed since last read in table 'reservation'`. Do not add a
  `reservation.setStatus(...)` in `pick` - a modified managed entity is flushed without any `save`.
- **Packing goes through `PackingServiceImpl.packLine`**, shared by `createShipmentPackage`
  (`POST /packing/{orderNo}`, controller `createShipmentPackageByOrder`: a package with its items,
  all of that order), `createPackageItems` (`POST /packing`: loose items without a package that share
  one `runNo`, lines of any order) and `createCustomShipment` (`POST /packing/shipment`: a package,
  with items only if some are sent - they are then not tied to an order number). All three set the
  package up through `newShipmentPackage`. Per line, on the row locked with `findForUpdateById` and in ascending id
  order: unknown id -> 404; line of another order -> 400; already packed = every `package_item` of
  the line (in a package or loose) plus earlier entries of the same request; full -> skipped; more
  than the room left -> 400, never clipped; status not `PICKED`/`PACKING` -> skipped; otherwise packed
  and set to `PACKING` or `PACKED`. Quantities are checked before the status, so an overflow is
  reported whatever state the line is in. `requireValidItems` rejects an empty list and `qty` < 1
  before any line is locked. When nothing at all is packed the 400 names every skipped line with its
  reason. Each entry goes through `packLine` on its own, but what it packs is folded into the item of
  the same line already in the run (`addToRun`): one item per line and run, as
  `uq_package_item_order_item_run` demands - 6 + 4 becomes one item of 10. Throw
  `APIException`/`ResourceNotFoundException` from here, never `IllegalStateException`, which
  `GlobalExceptionHandler` answers with a 500. A `PackageItem` outlives its package, so
  `ShipmentPackage.items` has neither `orphanRemoval` nor a `REMOVE` cascade - taking an item out
  goes through `ShipmentPackage.removeItem` (the item's package set to `null`); deleting it would
  drop the packed quantity while the line still reads `PACKING`/`PACKED`.
  `validateOrderItemPacking` is unused and kept on purpose - `completePackage` does not call it.
- **Changing what a package holds** (`PackingServiceImpl`): `addPackageItems`
  (`POST /packing/shipment/{id}/items`), `updateCustomShipment` (`PUT .../items`, replaces the
  contents; `[]` empties the package), `removePackageItem` (`DELETE .../items/{itemId}`) and
  `updatePackageData` (`PUT /packing/shipment/{id}`, type/dimensions/number only). Items only
  move between loose and in-a-package - no quantity check, no status change on the order line. The
  package is read with `findForUpdateById`, the items with `findAllForUpdateByIdIn` (ascending ids),
  and only an `OPEN` package may change (409). An item already in another package is a 409, never
  moved silently; a package holds the items of one order only (400); and at most one item per order
  line **and run** (409) - that is the key of `uq_package_item_order_item_run`
  `(shipment_package_id, order_item_id, run_no)`, so one line packed in two runs (5 + 5) may share a
  package. On replace, what leaves is flushed before anything is attached, so swapping two items of
  the same line and run never shows the constraint both at once.
- **Completing a package** (`PUT /packing/shipment/{id}/complete`, `PackingServiceImpl.completePackage`)
  takes it from `OPEN` to `PACKED`; from then on its contents are fixed and it can go into a
  shipment. Never an empty package (400) - it would go to no customer and could not be filled any
  more. Not `OPEN` is a 409, checked before `ShipmentPackage.complete()`, whose
  `IllegalStateException` would end as a 500.
- **Shipments group PACKED packages for one customer** (`ShipmentServiceImpl`, `/shipments`). Built
  like a package's contents: `ShipmentPackage.shipment` owns the foreign key, `Shipment.packages` has
  no setter, no cascade and no orphanRemoval, and packages move through `addPackage`/`removePackage`
  - taken out, a package is free again, never deleted. A shipment is never empty: created with at
  least one package, and neither `PUT .../packages []` nor removing the last one is allowed (400).
  The request carries **no customer** - it is read from the first package's first item
  (`customerOf`, an empty first package is a 400) and has to be a `Customer` (checked after
  `Hibernate.unproxy`); every package then has to hold items of an order of that customer -
  `findCustomersByShipmentPackageIdIn` answers that for all packages in one query. A package holds
  the items of one order only (`requireOneOrder`, 400 on every way in, completing included), so it
  always has exactly one customer. Only `PACKED` packages (409), none from another shipment (409), no
  package number twice (`uk_shipment_package_number` only bites once `shipment_id` is set - 409 up
  front). What may still change depends on the status: **packages** only while `CREATED` (409 from
  `READY` on - `findChangeableShipmentForUpdate`), the shipment's **own data** up to `ACCEPTED`
  (`DATA_CHANGEABLE_IN`, 409 from `IN_TRANSIT` on). Shipment first, then its packages in ascending id
  order, both `FOR UPDATE`. The status starts as `CREATED` in `Shipment`'s
  `@PrePersist`. `shippingAddress` is optional when creating (blank is stored as `null` and left out
  of the JSON) but required for `GET /shipments/{id}/ready` (`checkShipmentReady`, `CREATED → READY`,
  also requires every package `PACKED`). A distributor is assigned with
  `PUT /shipments/{id}/distributor/{distributorId}` only while `READY` or `DISPATCH_REQUESTED`; the
  user has to be a `Distributor`, checked on the unproxied instance - 400 otherwise. The list
  (`GET /shipments`, optional `status`) is two queries like the package list. `ShipmentResponse`
  names customer and distributor by id and name only, never the `User` entities.
- **Reporting a shipment ready** (`PUT /shipments/{id}/ready`, `checkShipmentReady`): read
  `FOR UPDATE` and only from `CREATED` (409 - on a shipment already on its way the call would take
  lines packed again in the meantime back to `READY_FOR_DISPATCH`), with a shipping address (409),
  at least one package (409 - `allMatch` says true for none) and every package `PACKED` (409). It
  then takes the shipment to `READY`, the **order lines** it carries from `PACKED` to
  `READY_FOR_DISPATCH` (`OrderItemRepository.findByShipmentId`) and the **orders** behind them with
  them (`OrderProgressService.advance`). A line still `PACKING` has parts in another package and is
  left alone; forwards only, like the orders. The order moves here and not at `accept`: the status
  is the warehouse reporting an order ready for the distributor, not the distributor answering. The
  `advance` call in `accept` stays as a catch-up for an order that was not moved here.
- **The carrier's three steps** (ADMIN and DISTRIBUTOR, `DeliveryServiceImpl.advance` - the carrier
  side lives in `DeliveryService`, not in `ShipmentService`; `RoleEnum.LOGISTICS` is the inside role
  that plans shipments, which is why it is not called `LogisticsService`). They answer with
  `DeliveryResponse`: address, package count, weight and package numbers, **no package contents** -
  the carrier is not shown the customer's SKUs and quantities. The steps are:
  `POST /shipments/{id}/accept` (`READY`/`DISPATCH_REQUESTED` → `ACCEPTED`),
  `POST /shipments/{id}/in-transit` (`ACCEPTED` → `IN_TRANSIT`, stamps `shippedAt` and takes the
  packages from `PACKED` to `DISPATCHED`) and `POST /shipments/{id}/delivered` (`IN_TRANSIT` →
  `DELIVERED`, stamps `deliveredAt`); any other status is a 409, and the shipment is read
  `FOR UPDATE`. Each step takes **the orders of the shipment** along - `READY_FOR_DISPATCH`,
  `IN_TRANSIT`, `DELIVERED` - found through the packages (`OrderRepository.findByShipmentId`, a
  shipment may carry packages of several orders of its customer). Which of them really move is
  **`OrderProgressService`**, not the shipment: `advance` takes orders forwards only (`ORDER_FLOW`
  decides, so an order already further along or one in `REJECTED`/`CANCELLED` is left alone) and
  `takeBackFromDispatch` is the way back for a cancelled dispatch. It publishes the
  `OrderStatusChangedEvent` and runs `Propagation.MANDATORY` - without the caller's transaction an
  `AFTER_COMMIT` listener would drop the event, so it refuses to run outside one.
- **Calling a shipment off** (`POST /shipments/{id}/cancel`, ADMIN and LOGISTICS): only up to
  `ACCEPTED` (409 afterwards - once it rolls it is a return, which the process does not model), and
  only with a reason (400), which is kept in `comment`. It undoes what the shipment had set in
  motion: the packages are loose again and stay `PACKED`, lines go from `READY_FOR_DISPATCH` back to
  `PACKED`, and orders from `READY_FOR_DISPATCH` back to `IN_FULFILLMENT`
  (`OrderProgressService.takeBackFromDispatch` - unless a line of theirs travels in another
  shipment). Lines and orders are read **before** the packages are detached: both are found over the
  packages.
- **A package's weight is computed, never sent.** `ShipmentPackage.weight` is the weight of its
  contents (0 without items) and has no setter; no request carries a weight. The package keeps it
  itself: `@PrePersist` on insert, `addItem`/`removeItem` on every change of contents - the service's
  `attach`/`detach` go through them, and `items` has no setter. Not `@PreUpdate`: a change of contents
  only touches `package_item` rows, the package is not dirty and the callback would not run - and
  where it did, it would load the items in the middle of a flush. `getPackageWeight()` is content
  plus the tare of the type.
- **`Reservation.release()`** marks status `RELEASED`, but the row is then *deleted* (not kept)
  because the unique constraint would otherwise permanently block re-reserving the same
  order/sku/storehouse combination.
- **An order status is written in one place**: `OrderProgressService.changeStatus` sets it, saves
  and publishes the event - `advance`/`takeBackFromDispatch` are the variants with a direction rule,
  and `recordCreated` is the first row of a new order. `OrderServiceImpl` (create, update, and with
  it `acknowledge` and `reject`) and `FulfillmentServiceImpl` (`IN_FULFILLMENT`, back to `APPROVED`)
  all go through it. Do not write `order.setStatus(...)` and publish by hand - every caller that did had
  its own idea of what to do when the user could not be resolved, and one of them dropped the audit
  row. `changeStatus` ignores a `null` target and one the order already has, so a CRUD update
  without a status leaves the order where it is.
- Order-status transitions publish `OrderStatusChangedEvent` via `ApplicationEventPublisher`;
  `OrderStatusChangedListener` (`@TransactionalEventListener(phase = AFTER_COMMIT)`) writes an
  `OrderHistory` audit row. Follow this event pattern for any new status-changing code path instead
  of writing history rows inline. Two things are load-bearing here: the publishing method must be
  `@Transactional` (an `AFTER_COMMIT` listener silently discards events published without one), and
  the listener must be `@Transactional(REQUIRES_NEW)` (with the default propagation the repository
  call joins the already-committed transaction and the row is dropped without a flush).

#### Reserve answers with what *this* call created

`reserveItems` reports only the reservations it created itself, never the ones an earlier call
already made — a repeat is a no-op and has nothing to show for itself. Because an empty array then
has two meanings, `ReservationOutcome` distinguishes them and `InventoryController` maps it to the
status code: `CREATED` → **201**, `COMPLETE` → **200** (order fully reserved, nothing left to do),
`PENDING` → **202** (lines still `WAITING`, worth calling again). `ReservationResult` carries both
`created` and `active` for that reason: the response needs the former, the order/line status
reconciliation the latter.

### Scheduled routines

`AutomaticReservationService` holds what runs without a request; `@EnableScheduling` comes from
`AutomaticProductionService`. During development the `@Scheduled` annotations of all four routines
are commented out; the intervals below are the ones they carry. Each works the first 100 orders of
its status. `AutomaticProductionService.assemble()` on the other hand is active and runs `produce()`
every 150 s.

- `checkCreatedOrders()` (every 300 s) checks coverage per `CREATED` order and acknowledges the ones
  whose every line is coverable (`orderService.acknowledge(order, null)`). It writes.
- `tryToReserve()` (every 150 s) calls `reserveItems` for every `IN_FULFILLMENT` order.
- `tryToRelease()` (every 150 s) takes its orders from
  `FulfillmentService.findOrdersWithExpiredReservations()` - every order holding at least one
  reservation past `expiresAt`, each once - and calls `releaseItems` for each. `expiresAt` only
  decides which orders are picked: the release then covers *all* active reservations of the order,
  a fresh one next to an expired one included. A full release takes the order back to `APPROVED`.
- `tryToDelete()` (every 150 s) deletes the `CONSUMED` reservations of `READY_FOR_DISPATCH` orders
  two days after their `expiresAt`. No code sets an *order* to `READY_FOR_DISPATCH` yet, so today it
  finds nothing; see `issues.txt` before switching it on.

There is no open-in-view session out here, so every order reaching these routines has to arrive with
its `orderItems` already fetched, or it turns into a `LazyInitializationException`.
`checkCreatedOrders` and `tryToReserve` get theirs from `findAllByStatus`, `tryToRelease` from
`findWithOrderItemsByIdIn` - one query for all affected orders, both through an `@EntityGraph`. For
the release this is not cosmetic: `releaseItems` walks `orderItems` only after the stock has been
released in its own `REQUIRES_NEW` transaction, so a lazy failure there would leave the lines on
`RESERVED` over reservations that are already gone.

### Timestamps maintain themselves

`updatedAt` on `Stock`, `Product` and `OrderItem` carries `@UpdateTimestamp`. Do not write it by
hand - Hibernate overwrites it on flush anyway, so a manual assignment is a no-op that reads like it
does something. `OrderItem` was the exception until recently, and one branch that forgot the manual
call left the timestamp stale.

### Enum values in a request body

`@JsonFormat(with = ACCEPT_CASE_INSENSITIVE_VALUES, READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)`
has to sit on the **property** - see `CreatePackageRequest.shipmentPackageType` (the JSON key is
`shipmentPackageType` since the rename from `packageType`). On the enum declaration it
is not consulted, and on the entity it does nothing at all, because entities are never deserialized:
the request DTO is the only thing bound from a body. Note also that a property arriving as `null`
means the JSON key did not match the record component name; a value the enum does not know produces
a 400 through `GlobalExceptionHandler`, not a null.

### Responses are DTOs, never entities

The same holds for request bodies: a controller binds a record under `dto/`, never an entity -
bound from JSON, an entity accepts every field it has (`UserController` took `User` and with it
`id`, `userType` and full `Role` objects; it now takes `UserRequestDto`, roles as names).

Controllers answer with records under `dto/`. Handing a JPA entity to the response writer breaks
twice over: `spring.jpa.open-in-view` is at its default `true`, so every LAZY reference resolves
silently during serialization (one query per row and level), and `Reservation → orderItem → order →
orderItems` closes into a cycle Jackson cannot escape.

`PickingOrderDto` is the reference: filled by a constructor projection in
`ReservationRepository.findPickingOrders()`, one query per page, nothing lazy left in the response.
The picking *actions* return the same DTO, mapped inside `OrderHandlingServiceImpl` while the
session is open. A projection query that joins needs an explicit `countQuery` — inner joins can
drop rows, so a plain `count(...)` would report a larger total than the page query delivers.

A third way it breaks: anything returned from a `REQUIRES_NEW` transaction - the whole inventory
layer - comes out of a session that is already closed, so its LAZY references fail with
`Could not initialize proxy - no session` when Jackson touches them. Open-in-view does not cover
this; its session is a different one. `ReservationDto` is the answer for that case: related entities
as ids only, because Hibernate serves `getId()` on a proxy without initializing it. Reserve and
release answer with it - reserve maps inside `FulfillmentServiceImpl.reserveItems`, release in the
controller, because `releaseItems` also feeds `tryToRelease`, which wants the entities. `ReservationDtoTest` reproduces the
closed-session state with `getReference`.

`OrderItemListDto` (`GET /order-items`) is the projection for order lines by fulfillment status. It
carries the line's `reservationId` through `left join Reservation r on r.orderItem = oi` - an entity
join with ON, because `OrderItem` has no reference to its reservation, and a left one, because a
`WAITING` line has none. The count query leaves that join out; it cannot drop or duplicate lines as
long as `uk_reservation_order_item` holds.

The package read side lives in `PackageQueryService` (`PackageController`): `GET /shipment-packages`
(by `ShipmentPackageStatus`, optional `packageNumber`), `GET /packages` (all package items),
`GET /lonely-packages` (items without a package) and the single-entry variants `/{id}`. A package
list page is two queries - the page, then `findWithItemsByIdIn` with the items, lines, products and
orders - because a collection fetch in a paged query makes Hibernate page in memory. An empty
`packageNumber` must not reach the `...Containing` finder: `LIKE '%%'` never matches a `NULL` number.
The items inside a package row are `ShipmentPackageItemDto` (no package id - the row is the package);
everywhere else a package item is a `PackageItemResponse`. Its `siblings` are computed, not stored:
build it through `PackageItemResponseAssembler`, which looks the ids up for all items of a response
in one query - `PackageItemResponse.from` and `ShipmentPackageResponse.from` take the computed parts
as arguments for that reason. Never put the `PackageItem` entity into a response: it serializes its
package, whose items serialize their package again.

### Validation groups on request bodies

`CreatePackageRequest.items` is required for `POST /packing/{orderNo}` and optional for
`POST /packing/shipment`. One DTO covers both through a validation group: `@NotEmpty` on `items` belongs
to `CreatePackageRequest.WithItems`, the `@Valid` on each `PackItem` to `Default`. An endpoint that
requires items declares `@Validated({Default.class, CreatePackageRequest.WithItems.class})`; a plain
`@Valid` lets `items` be missing. `WithItems` alone would skip the per-item rules - always pair it
with `Default`.

`CreatePackageItemsRequest`, `PackageItemIdsRequest` and `ShipmentPackageIdsRequest` also accept the
bare JSON array (`[...]`) next to the wrapped form, through a static factory with `@JsonCreator(mode = DELEGATING)`; the
record's canonical constructor still reads the object form. Both end in the same record, so the
validation applies to either.

### Error responses

Two shapes are in use. `PickingController` and `PackingController` catch `APIException` themselves
and answer `{"message": ...}` at its status; everything else - `ResourceNotFoundException` there too,
and all of `PackageController`, `ShipmentController` and `UserController` - goes through
`GlobalExceptionHandler` and answers `ErrorDetails`, bean validation as a map of field to message.
New endpoints use the global handler; a controller-local try/catch is legacy, not the pattern.

### Paged list endpoints

Every list endpoint follows `OrderController.list`: request parameters `page` / `size` / `sort` /
`order`, assembled into a `PageRequest`, answered with `PageResponse.of(page)` —
`{content, total, page, size}`. Defaults are `page=0`, `size=25`, `order=ASC`; the `sort` default
differs per endpoint. `sort` is applied as a JPQL path, so on a projection query a sort over a
joined column is not resolvable and will fail at runtime.

### Persistence caveat: `ddl-auto=update`

`spring.jpa.hibernate.ddl-auto=update` (see `application.properties`) will add missing
tables/columns but will **not** fix an existing column's type or drop/alter constraints. If an
entity's `@Enumerated`/column mapping changes, the DB schema can silently drift out of sync with
the entity (this has happened before: a `tinyint` + `CHECK` column left over from an
`EnumType.ORDINAL` mapping after the entity switched to `EnumType.STRING`). When changing an
enum's storage mapping or a column's type on an existing entity, migrate the DB manually — check
`information_schema`/`SHOW CREATE TABLE` for the actual column type/constraints rather than
assuming the entity mapping matches. A `MariaDBLegacyDialect` community dialect is in use, and
`DROP CHECK`/`DROP CONSTRAINT` on an inline Hibernate-auto-named column CHECK may not work directly
on this MariaDB version — a direct `MODIFY COLUMN` to the correct type has been the reliable fix
instead.

The same applies to new foreign keys. Adding `Reservation.orderItem` was the recent example:
`update` created `reservation.order_item_id` and then failed to add its FK, because a `NOT NULL`
column added to a populated table gets MariaDB's default `0` — and `0` is no valid `order_items.id`.
A new FK column has to be added nullable, backfilled, and only then constrained. Check what is
actually in the column (`SELECT ... GROUP BY`) before assuming the rows are `NULL`.

The reverse needs a manual step too: `package_item.shipment_package_id` became optional for loose
items, but `update` leaves an existing `NOT NULL` in place - `ALTER TABLE package_item MODIFY
shipment_package_id BIGINT NULL`. Data migrations that cannot wait for a real migration tool run as
an `ApplicationReadyEvent` listener in `config` (`OrderDateToCreatedMigration`), guarded so they do
nothing once done. Note that `@SpringBootTest` runs them as well, against the same database.

### Business/aspect utilities

- `service/business/AutomaticProductionService` — `assemble()` builds products from component
  stock every 150 s (`ProductionServiceImpl.produce`/`produceSingleProduct` picks the storehouse
  with enough components and decrements them, then adds the produced unit as stock). `POST /produce`
  runs the same by hand and answers `ProductionPageResponse` (the page plus `produced`, the number
  actually built).
- `@NoCheck` (`annotation/NoCheck.java`) + `NoCheckAspect` — a marker annotation logged via AOP
  `@After` advice; check existing usages before assuming it changes authorization/validation
  behavior (currently logging-only).
- `service/ratelimiting` — `RateLimitingFilter` + `PricingPlanService`, backed by `bucket4j`
  (`PricingPlan` enum), gates request rate by plan.
