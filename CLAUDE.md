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
`application-dev.properties` is missing there. Everything else is plain Mockito/AssertJ and runs
in a couple of seconds.

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

### Domain: Order → Fulfillment → Picking → Packing → Dispatch

This is the most involved part of the codebase, spread across several services with distinct
responsibilities — read all of them together before changing reservation/fulfillment logic:

- **`Order`** has a coarse-grained `OrderStatus` (`CREATED → ACKNOWLEDGED → REVIEW → APPROVED →
  IN_FULFILLMENT → READY_FOR_DISPATCH → IN_TRANSIT → DELIVERED → COMPLETED`, plus
  `REJECTED`/`CANCELLED`). Only the part up to `IN_FULFILLMENT` is driven by code today.
- **`OrderItem`** has a finer-grained `FulfillmentStatus` (`WAITING → RESERVED → PICKING → PICKED →
  PACKING → PACKED → READY_FOR_DISPATCH`), tracked per line item. This chain *is* implemented end
  to end. Note there is no `RESERVING` — it was removed because nothing could ever observe it
  inside the synchronous reserve transaction.

The chain is split by responsibility, not by entity. Which service owns which stretch:

| Stretch | Service | Controller |
|---------|---------|------------|
| availability check, production | `ProductionService` | `ProductionController` |
| `WAITING ⇄ RESERVED` | `FulfillmentService` | `InventoryController` |
| `RESERVED → PICKED` | `OrderHandlingService` | `FulfillmentController` |
| `PICKED → PACKED` | `PackingService` | `FulfillmentController` |
| `PACKED → READY_FOR_DISPATCH` | `OrderHandlingService.readyDispatch()` | `FulfillmentController` |
| dispatch/tracking | `LogisticsService` | — (declared, not implemented) |

- **`ProductionService`** — `checkItems(order)` reports stock availability per line without
  reserving or writing anything; `produce()` builds finished products from component stock.
- **`FulfillmentService`** (`FulfillmentServiceImpl`) drives the reservation lifecycle:
  `reserveItems`/`releaseItems` advance/revert `OrderItem.fulfillmentStatus`. `reserveItems` also
  advances `Order.status` to `IN_FULFILLMENT`, but only from a pre-fulfillment status
  (`PRE_FULFILLMENT_STATUSES`), so a second idempotent call never regresses further-along orders.
  It asks `ProductionService.checkItems` for availability rather than querying stock itself.
- **`InventoryService`** (`InventoryServiceImpl`) is the retry/idempotency wrapper around actual
  reservation work — `reserveWithRetry`/`releaseWithRetry`/`consumeWithRetry`, all keyed by
  `String orderId` (matches `Reservation.orderId`, which is a String, not the numeric `Order.id`).
- **`InventoryReservationTransactionService`** does the real DB work in its own `REQUIRES_NEW`
  transactions (`reserve`/`release`/`consume`), each iterating `ReserveItem`s and mutating `Stock`
  + `Reservation` together. `reserve` has an idempotency guard (re-check `findActive(orderId)`
  before inserting) plus `InventoryServiceImpl` catches `DataIntegrityViolationException` as a
  race-condition fallback — the DB's unique constraint on `(order_id, sku, storehouse_id)` is the
  last line of defense, not the primary guard.
- **`Reservation` points at its `OrderItem`** through a unidirectional, LAZY `@OneToOne` on
  `order_item_id`. `orderId`, `sku` and `quantity` are kept denormalized alongside it on purpose:
  `Stock` is keyed by `(sku, storehouse)`, so the hot reserve/release path needs no join through
  the order item. The relation is deliberately *not* bidirectional — a back reference would drag
  `Order → orderItems → reservation → orderItem` into every response serializing a reservation.
- **`Reservation.release()`** marks status `RELEASED`, but the row is then *deleted* (not kept)
  because the unique constraint would otherwise permanently block re-reserving the same
  order/sku/storehouse combination.
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

### Responses are DTOs, never entities

Controllers answer with records under `dto/`. Handing a JPA entity to the response writer breaks
twice over: `spring.jpa.open-in-view` is at its default `true`, so every LAZY reference resolves
silently during serialization (one query per row and level), and `Reservation → orderItem → order →
orderItems` closes into a cycle Jackson cannot escape.

`PickingOrderDto` is the reference: filled by a constructor projection in
`ReservationRepository.findPickingOrders()`, one query per page, nothing lazy left in the response.
The picking *actions* return the same DTO, mapped inside `OrderHandlingServiceImpl` while the
session is open. A projection query that joins needs an explicit `countQuery` — inner joins can
drop rows, so a plain `count(...)` would report a larger total than the page query delivers.

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

### Business/aspect utilities

- `service/business/AutomaticConstructionService` — builds products from component stock
  (`ProductionServiceImpl.produce`/`produceSingleProduct` picks the storehouse with enough
  components and decrements them, then adds the produced unit as stock).
- `@NoCheck` (`annotation/NoCheck.java`) + `NoCheckAspect` — a marker annotation logged via AOP
  `@After` advice; check existing usages before assuming it changes authorization/validation
  behavior (currently logging-only).
- `service/ratelimiting` — `RateLimitingFilter` + `PricingPlanService`, backed by `bucket4j`
  (`PricingPlan` enum), gates request rate by plan.
