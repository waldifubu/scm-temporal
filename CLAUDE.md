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
**Context tests run against their own schema, `scm_test`, and must say so.** Every
`@SpringBootTest` carries `@ActiveProfiles("test")`, which layers
`src/test/resources/application-test.properties` (the `scm_test` datasource, `ddl-auto=update`) on
top of the main properties. A new context test without that annotation silently runs against the
application's `scm` database again, which is what all of them used to do: they executed every startup
migration there, let `ddl-auto` work on it, and took their fixtures from whatever rows happened to be
present.
<br>Note the file is `application-test.properties`, not `application.properties`: a file of the
latter name in `src/test/resources` **shadows** the main one on the classpath and would take every
other setting with it.
<br>Deliberately the same MariaDB version as production rather than an in-memory H2 - half the
migration logic in `config/` exists because of this server's behaviour (inline column CHECKs that
`DROP CONSTRAINT` cannot reach, a `MODIFY COLUMN` that refuses to cast `uuid` to `bigint`,
`innodb_snapshot_isolation`), and H2 would pass what MariaDB fails.

**A context test creates the rows it needs** through `support/TestData` (`@Import(TestData.class)`):
`customer()`, `supplier()`, `product()`, `component()`, `storehouse()`. They used to be taken from
the database with `findAll().getFirst()`, which only worked against a populated development schema.
Everything `TestData` makes is created inside the test's transaction and rolls back with it, so two
runs see the same thing - unique columns (`users.email`, `users.username`, `products.article_no`,
both `sku`s) get a counted and randomised value so a run that did not roll back cannot collide with
the next.

`ApplicationTests` loads the full Spring context and therefore needs a reachable database and a
complete `application.properties`. `CustomQueryExecutionTest` (runs every hand-written
query and entity graph once) and `ReservationDtoTest` load the context too - add new repository
queries to `CustomQueryExecutionTest`, a unit test mocks them away. Everything else is plain
Mockito/AssertJ and runs in a couple of seconds. Controller tests use standalone MockMvc with the
project's own version resolver from `ApiVersioningTestSupport`, so they call `/api/1.0/...` like a
client; security filters are not part of them.

Run the app (dev profile, uses `application-dev.properties`):
```
mvn -Dspring-boot.run.profiles=dev spring-boot:run
```
Default port is 8080 (`server.port` in `application.properties`). Logs are written to
`logs/app-${server.port}.log` in addition to stdout — tail that file to check startup status
(look for `Started Application` vs `APPLICATION FAILED TO START`).

**There is no JavaScript project in this repository.** No `package.json`, no `vite.config.ts`, no
`src/main/frontend`, no `src/main/bundles`: they were removed on purpose, a React frontend is to live
in a project of its own next to this one. The Vaadin views are plain Java (`vaadin/views`) and need
none of it - the starter serves its prebuilt development bundle from its own jars, so the application
starts in seconds and **needs neither Node nor `npm`** (measured: startup without any of those files,
login view rendered in a browser). Vaadin does write `src/main/frontend/index.html` and
`src/main/frontend/generated/` again while it starts; both are build output and are in `.gitignore`
(`/src/main/frontend/`), never edit or commit them. `vaadin.launch-browser=true` opens a browser
automatically in dev mode.
<br>**Bootstrap reaches the Vaadin pages through `styles.css`**, which starts with
`@import url('bootstrap.min.css')` - a local copy (5.3.8) next to it. It used to come from
`import 'bootstrap/...'` in `src/main/frontend/index.tsx`, bundled by Vite; without that import the
Vaadin pages fell back to the browser's serif font. A stylesheet needs no build, so it survives the
removal of the JavaScript project and needs no network.
<br>**Why there is a `resources` folder inside `resources`:** `src/main/resources/resources/` is one of
the four places Spring Boot serves static files from by default (`classpath:/META-INF/resources/`,
`/resources/`, `/static/` and `/public/`), and the inner folder name is **not** part of the URL:
`resources/styles.css` is `/styles.css`, `resources/icons/favicon.ico` is `/icons/favicon.ico` (named
that way by `Application.configurePage` and the Thymeleaf pages). `src/main/resources/static/` does the
same for the Thymeleaf site (`/css/style.css`) - two static roots side by side, harmless as long as no
path exists in both (the first location in the list wins). Nothing configures this; it is Boot's default.
<br>**A stale `target/dev-bundle` makes Vaadin rebuild.** Removing `index.tsx` made the next start
report "Detected deleted index.tsx file" and run `npm install` plus a bundle build (about 90 seconds,
"Building front-end development bundle" in the browser), and that build wrote `package.json`,
`node_modules` and the rest back. A checkout without a `target/dev-bundle` does not do it. If it
happens: stop the application, delete `target/dev-bundle` and the regenerated files, start again.

## Architecture

### Two UIs + one API, on separate Spring Security filter chains

`SpringSecurityConfig` defines three independently-ordered `SecurityFilterChain` beans, each
scoped with `.securityMatcher(...)` so their rules never interact:
1. `apiFilterChain` (`/api/**`) — stateless, JWT-authenticated, CSRF disabled.
2. `vaadinFilterChain` (`/app/**`) — Vaadin's own `VaadinSecurityConfigurer`, form login via `LoginView`.
3. `webFilterChain` (everything else) — classic Thymeleaf pages, session-based form login, CSRF stays on.

Vaadin is deliberately mounted under `/app/*` (`vaadin.url-mapping=/app/*`) so it doesn't compete
with the Thymeleaf `/` route in `WebController`. Vaadin views live in
`service...vaadin/views` (actually `com.supplychainmanagement.vaadin.views`). A React client for the
API is not part of this repository.

**The signing key comes from the environment**: `app.jwtSecret=${JWT_SECRET:...}`. The default in
`application.properties` exists only so the application starts and can sign - it is not a production
key and says so. `JwtTokenProvider` refuses anything under 32 bytes at startup
(`requireUsableSecret`, `@PostConstruct`), because HMAC-SHA needs 256 bits: the key was 30 characters
in one working copy, `Keys.hmacShaKeyFor` threw from inside `generateToken`, and so the application
started cleanly while **every login answered 500**. Note `application.properties` is gitignored (it
holds the real datasource and secret) - the template `application.properties.dist` is what ships and
what a fresh checkout copies, and `JwtSecretTest` therefore reads **the template**, not the live
file. It also signs a token with that value rather than only measuring its length, because the
question was never the length.

**Authentication failures are 401 - one rule for a client: log in again.** An expired token, an
unparseable one, a forged one, a missing one and a wrong password all answer 401. It used to be three
codes for one class of failure: an expired token was **410** ("gone for good", which a session is not)
and both an unparseable token and a wrong password were **400** ("malformed request", which they are
not). `AuthStatusCodesTest` runs them against the real filter chain.
<br>**A failed login says only "no".** An unknown user and a wrong password give the same status *and
the same text* (`AuthServiceImpl.INVALID_CREDENTIALS`) - they used to differ ("Invalid username or
email!" against "Invalid username/email or password!"), which told anybody which user names exist, and
the test asserts the two answers equal rather than merely both 401.
<br>**A disabled or locked account stays 403**, on purpose: it is a state of an account that is known,
not a failure of the credentials, and the one signal a client can show instead of "try again". Note it
is answered *before* the password is checked, so it does say that the account exists.
<br>For a client: a 401 from any endpoint except the login call means the session is over; a 401 *from*
the login call means the credentials were wrong. The status does not tell them apart, the endpoint does.

**The CORS origin is configuration, and empty means none.** `app.cors.allowedOrigins` is a comma
separated list (`SecurityBeanConfig.parseOrigins`: trimmed, blanks dropped, a trailing slash removed -
the `Origin` header never has one, so an origin written with it would never match and fail without
saying why). It was `List.of("http://localhost:3000")` in the code, so any other origin - Vite's
default port 5173 among them - was refused at the preflight and the only fix was a rebuild. The default
in code is **empty**: a deployment that serves the frontend from the same origin needs none, and one
that forgets to say fails closed. `application.properties.dist` carries the development values
(`${CORS_ALLOWED_ORIGINS:http://localhost:5173,http://localhost:3000}`, the environment variable wins).
A wildcard is refused **at startup**: the session is a cookie, credentials are on, and a browser
rejects `*` with them - allowing every site would also defeat `SameSite`. `PATCH` is allowed next to
the other methods (`PATCH /shipments/{id}/trackingnumber` is one; a preflight for an unlisted method is
refused before the call is made). `CorsPreflightTest` uses an origin that is none of the development
ones, so it tells "configured" from "hard-coded".
<br>**A dev proxy does not make CORS disappear - it depends on the Host.** The browser sees one origin,
but the *server* still compares `Origin` with the request's own host. A proxy that keeps the original
Host (Vite's default, `changeOrigin` off) makes them equal and there is nothing to check; one that
rewrites it to the backend's (`changeOrigin: true`, common in tutorials) makes the same request look
cross-origin, and it is refused unless the origin is listed. Both are held in `CorsPreflightTest`.

**The JWT filter writes into the `SecurityContextHolderStrategy` of its own context**, not the static
holder (`setSecurityContextHolderStrategy`, the idiom Spring Security uses for its own filters). The
static `SecurityContextHolder` is JVM-wide, and the Vaadin integration installs a
`VaadinAwareSecurityContextHolderStrategy` into it **per application context** - so a second context
started in the same JVM replaces it, and the first context's `AuthorizationFilter` (which reads its own
strategy) then no longer sees what this filter wrote: a valid token ended as an unauthenticated 401
with an *empty* body (`sendError`, not the filter's own `ErrorDetails`). Production has one context and
never saw it; the tests have several. Found because `MeEndpointTest` passed or failed depending on
whether `CorsPreflightTest` - which starts another context - ran in between; measured by printing the
global strategy against the context's bean (`@636036483` against `@316882043`).
`JwtAuthenticationFilterStrategyTest` holds it without any Spring context, because a result that
depends on the order of unrelated tests is not something to rely on to catch it again.

**`GET /me` answers who is logged in, with every role** (`MeController`; `UserDto`, the same shape as
`GET /users/{id}`; roles as the enum names `@PreAuthorize` compares against). It exists for a browser
client: the token lives in an `HttpOnly` cookie that JavaScript cannot read, so after a reload asking
the server is the only way to learn whether there is a session. The login response cannot stand in for
it - its `role` is *one* authority picked with `iterator().next()`, so a user holding WAREHOUSE and
LOGISTICS got `logistics` and a menu built from it showed half their workplace. Roles are read from the
database on every request, not frozen into the token.
<br>**It is not under `/auth`, on purpose.** It started as `/auth/me` and that was the wrong place:
everything under `/auth` (register, login, logout) must work without a token, so
`JwtAuthenticationFilter.shouldNotFilter` skips the whole space and `SpringSecurityConfig` permits it -
which would have called `/me` with no principal at all. It had to be carved out of both
(an exception in the filter, and an `authenticated()` rule before the `permitAll`), and a carve-out like
that fails quietly in either direction: too wide and `/me` has no user, too narrow and login stops
working. As `/me` it is an ordinary protected resource - the filter runs, `anyRequest().authenticated()`
applies, nothing special anywhere, and the carve-out is removed. The rule that follows: **nothing that
answers for the logged-in user may live under `/auth`**. The unversioned `/api/me` works as everywhere
else (`ApiVersionDefaultFilter`).
<br>The old path is gone, not aliased: `/api/1.0/auth/me` answers 404 (`MeEndpointTest`). That test
also holds the `HttpOnly`-cookie case, that the filter really reads a token on `/me`, and that login and
logout stayed public.

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
  backwards step besides the fallback to `APPROVED` in `releaseItems`. `PARTIALLY_DELIVERED` is for
  an order of which something has arrived while the rest is still out, and `COMPLETED` comes from
  `POST /orders/{orderNo}/complete`. `WAIT_SUPPLY` is where `acknowledge` parks an order whose stock
  is not there, and `CANCELLED` comes from `POST /orders/{orderNo}/cancel` (both below).
- **From `IN_FULFILLMENT` on, the order status is computed, never pushed**
  (`OrderProgressService.recompute`). The shipment side says only *which orders were touched*; where
  each of them stands follows from the **quantities of its lines that sit in shipments**
  (`OrderItemRepository.findShippedQuantities`, one row per line and shipment status, one query per
  batch). Everything delivered → `DELIVERED`; something delivered and something not →
  `PARTIALLY_DELIVERED`; everything at least on the road → `IN_TRANSIT`; everything at least in a
  `READY` shipment → `READY_FOR_DISPATCH`; otherwise `IN_FULFILLMENT`. Arriving is asked first, so a
  line half delivered and half on the road reads as partly there, not as in transit.
  `PARTIALLY_DELIVERED` is reserved for the delivery: a shipment that has merely left while the rest
  is still being picked keeps the order where the slowest line is.
  <br>Why: pushing a fixed target let the first shipment to report call a whole order ready, in
  transit or delivered, however many other shipments it was still spread over. It also goes
  **backwards** - a cancelled shipment hands its packages back, the coverage drops, the order falls
  by itself. There is no separate way back any more.
  <br>`recompute` only ever writes within `IN_FULFILLMENT … DELIVERED` and only for an order already
  in that range (`RECOMPUTED_FROM`). Everything before belongs to the order side - `WAIT_SUPPLY` is
  about stock, not about shipments - and `REJECTED`/`CANCELLED`/`COMPLETED` are ends, not steps. An
  order outside the range is skipped deliberately, not as the side effect of a list lookup, which is
  what the old `ORDER_FLOW` did: a status missing from that list was silently never moved.
- **`OrderItem`** has a finer-grained `FulfillmentStatus` (`WAITING → RESERVED → PICKING → PICKED →
  PACKING → PACKED → READY_FOR_DISPATCH`, plus `CANCELLED`), tracked per line item, and implemented end to end: the
  last step comes from the shipment (`PUT /shipments/{id}/ready`), not from
  `OrderHandlingService.readyForDispatch()`, which has had **no caller since 2026-09-22**: its
  endpoint `POST /dispatch/{reservationId}` was commented out and then deleted outright in the same
  day's clean-up, when `checkShipmentReady` took the step over.
  <br>It is not merely redundant but wrong in this model, for two reasons worth keeping. It looks the
  line up **through its reservation** (`findByIdAndStatus(id, CONSUMED)`) - the very row
  `tryToDelete()` exists to sweep away for `READY_FOR_DISPATCH` orders, so the method depends on
  something another routine is designed to remove and answers 404 afterwards. And it would set a line
  `READY_FOR_DISPATCH` **with no shipment involved**, while the *order* status is derived from the
  shipments its lines travel in (`recompute`) - so the line would move and its order would not, with
  nothing to reconcile them. `checkShipmentReady` does all three parts at once: the shipment to
  `READY`, every `PACKED` line of it onward, and `recompute` for the orders behind them.
  <br>Note there is no `RESERVING` — it was removed
  because nothing could ever observe it inside the synchronous reserve transaction.
- **`ShipmentPackage`** has `ShipmentPackageStatus` (`OPEN → PACKED → DISPATCHED`): filled while
  `OPEN`, closed by `completePackage`, and `DISPATCHED` when its shipment reports `intransit`.
  **`Shipment`** has `ShipmentStatus` (`CREATED → READY → DISPATCH_REQUESTED → ACCEPTED → IN_TRANSIT
  → DELIVERED`, plus `CANCELLED`): `CREATED` on insert, `READY` through
  `PUT /shipments/{id}/ready`, then the carrier's three steps, and `CANCELLED` through
  `POST /shipments/{id}/cancel` (see below). `DISPATCH_REQUESTED` comes from `assignDistributor` and
  from nothing else, and `accept` requires it - so the handover to a carrier is a step that has to
  happen, not an optional one.

The chain is split by responsibility, not by entity. Which service owns which stretch - one
controller per service, named after what it does (`PickingController`, `PackingController`,
`PackageController`, `ShipmentController`, `OutboundController`, `InboundController`).
<br>**`InboundController` and `OutboundController` are the two ends of the house**, and they are
named after the direction goods travel, not after an entity: inbound is what a supplier reports on a
component request they were given, outbound is what a carrier reports on a shipment they took on.
Both are the *outside* answering - which is why each keeps its own controller and its own role, and
why neither sits in the service's own controller. Each is mapped under **the party that answers**
rather than under the resource it is about: `InboundController` under `/api/{version}/supplier`
(SUPPLIER), `OutboundController` under `/api/{version}/shipments` (DISTRIBUTOR). The inbound steps
therefore read `POST /supplier/{requestId}/approve` and no longer sit beneath `/components`:

| Stretch | Service | Controller |
|---------|---------|------------|
| availability check, production | `ProductionService` | `ProductionController` |
| `WAITING ⇄ RESERVED` | `FulfillmentService` | `InventoryController` |
| `RESERVED → PICKED` | `OrderHandlingService` | `PickingController` |
| `PICKED → PACKED` | `PackingService` | `PackingController` (`/packing/**`, plus `/order-items`, the picked lines to pack) |
| reading packages and package items | `PackageQueryService` | `PackageController` |
| `PACKED → READY_FOR_DISPATCH` (lines and their orders) | `ShipmentService.checkShipmentReady()` | `ShipmentController` (`PUT /shipments/{id}/ready`) |
| packages → shipment, ready, cancel, what may still be shipped | `ShipmentService` | `ShipmentController` (`/shipments/**`) |
| accept → in transit → delivered, the distributor's work list | `DeliveryService` | `OutboundController` (`/shipments/**`, DISTRIBUTOR) |
| what the supplier reports on a component request, and their own list | `ComponentService`, `RequestComponentService` | `InboundController` (`/components/**`, SUPPLIER) |
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
- **An order number is a permuted counter, never a random draw** (`OrderNumberScrambler`,
  `OrderNoSequenceMigration`, `OrderRepository.nextOrderNoCounter`). `OrderServiceImpl.nextOrderNo`
  draws `order_no_seq` once and permutes it into the range **90000..199999** - the numbers look
  arbitrary (106324, 182392, 173640, ...) and never repeat, because a permutation is bijective.
  Note the range spans two digit widths, so an order number is five or six digits and the ones above
  99999 begin with a 1; what holds for all of them is the value, never below 9000.
  <br>What it replaced: a loop drawing `Math.random() * 9000 + 1000` and asking
  `existsByOrderNo` whether the number was free. The check and the insert are two statements, so two
  requests could pass it with the same number; the chance of a redraw grew with every order; and once
  the numbers were used up **the loop never terminated** - it span forever holding a request thread
  and its transaction.
  <br>The permutation is a four-round Feistel network, which is a permutation whatever its round
  function does - that is what makes the guarantee cheap to hold. The block width
  (`halfBitsFor(COUNT)`) is derived from the range rather than written down, because cycle walking
  costs `BLOCK / COUNT` rounds and a block that no longer fits the range costs a thousand of them.
  `OrderNumberScramblerTest` walks the **whole** range and holds that it is a bijection onto it; a
  sample could only say "no collision found yet".
  <br>**The range is the ceiling**: 90000..199999 holds **110000 orders**, and the next one is refused
  with a clear error rather than wrapped onto a number already in use. Widening it means moving
  `FIRST`/`LAST`, and only before numbers are in use - a different range is a different permutation.
  The four round constants are load-bearing in the same way. The `existsByOrderNo` check on create is
  kept as one query against exactly that: somebody changing either.
  <br>A sequence of its own rather than `Order.id`: the id exists only after the insert, so the order
  would have to be written with `order_no` null and updated right after - two statements and the
  creation event firing on an unfinished row. The sequence is read before the insert. It is
  deliberately not transactional, so a rolled-back order keeps its counter and MariaDB's cache of
  1000 can skip a block on restart; gaps do not matter, reuse would.
- **`OrderService.acknowledge`** accepts an incoming order and confirms a delivery date for it: two
  lead times in working days (`app.order.leadDays.inStock` / `.replenishment`, defaulted inline)
  depending on whether `checkItems` covers every line, weekends skipped, and a `dueDate` the
  customer asked for later than that wins. Only from `CREATED` - confirming an order already being
  fulfilled would throw it back.
  <br>The same answer also picks the status: `ACKNOWLEDGED` when every line is covered, `WAIT_SUPPLY`
  when it is not. The order is confirmed either way - the customer has a date - and the status says
  why that date is so far out. `checkItems` is asked **once** in `acknowledge` and handed to
  `confirmDeliveryDate`; it costs a query per line.
  <br>The way out of `WAIT_SUPPLY` is a reservation: it is in `PRE_FULFILLMENT_STATUSES`, so
  `reserveItems` lifts such an order to `IN_FULFILLMENT` as soon as stock can be held, and
  `tryToReserve` looks at `WAIT_SUPPLY` next to `IN_FULFILLMENT` for exactly that reason. Without
  both halves the status is a dead end.
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
  user has to be a `Distributor`, checked on the unproxied instance - 400 otherwise. Read
  `FOR UPDATE` like every other shipment change: it checks a status and writes one
  (`DISPATCH_REQUESTED`, the only code path that sets it). The list
  (`GET /shipments`, optional `status`) is two queries like the package list, and
  `GET /shipments/packages` is the planning side's list of what it may still ship - see the note on
  the WAREHOUSE/LOGISTICS split further down. `ShipmentResponse`
  names customer and distributor by id and name only, never the `User` entities.
- **Reporting a shipment ready** (`PUT /shipments/{id}/ready`, `checkShipmentReady`): read
  `FOR UPDATE` and only from `CREATED` (409 - on a shipment already on its way the call would take
  lines packed again in the meantime back to `READY_FOR_DISPATCH`), with a shipping address (409),
  at least one package (409 - `allMatch` says true for none) and every package `PACKED` (409). It
  then takes the shipment to `READY`, the **order lines** it carries from `PACKED` to
  `READY_FOR_DISPATCH` (`OrderItemRepository.findByShipmentId`) and the **orders** behind them with
  them - by asking `OrderProgressService.recompute`, which names no target. A line still `PACKING`
  has parts in another package and is left alone. The order only reaches `READY_FOR_DISPATCH` once
  **every** line of it is in a shipment that has been handed over, so reporting one of three
  shipments ready moves the lines but not the order.
- **The carrier's side lives in `OutboundController`**, not in `ShipmentController`. Both map under
  `/shipments` - that is the resource - and what separates them is the role and the direction:
  LOGISTICS plans a shipment, the DISTRIBUTOR reports on it. Assigning a distributor
  (`PUT /shipments/{id}/distributor/{id}`) is planning and stays with LOGISTICS - the house choosing a
  carrier, not the carrier answering. `GET /shipments/distributor` is the distributor's work list (ADMIN too,
  as everywhere else): their own shipments, optional `status`, paged, read from the authenticated user rather than from a
  path variable, and two queries like every other shipment list. The literal segment wins over
  `GET /shipments/{shipmentId}` in the other controller - `OutboundControllerTest` registers both
  controllers to hold that.
- **The carrier's three steps** (ADMIN and DISTRIBUTOR, `DeliveryServiceImpl.advance` - the carrier
  side lives in `DeliveryService`, not in `ShipmentService`; `RoleEnum.LOGISTICS` is the inside role
  that plans shipments, which is why it is not called `LogisticsService`). They answer with
  `DeliveryResponse`: address, package count, weight and package numbers, **no package contents** -
  the carrier is not shown the customer's SKUs and quantities. The steps are:
  `POST /shipments/{id}/accept` (`DISPATCH_REQUESTED` → `ACCEPTED`),
  `POST /shipments/{id}/intransit` (`ACCEPTED` → `IN_TRANSIT`, stamps `shippedAt` and takes the
  packages from `PACKED` to `DISPATCHED`) and `POST /shipments/{id}/delivered` (`IN_TRANSIT` →
  `DELIVERED`, stamps `deliveredAt`); any other status is a 409, and the shipment is read
  `FOR UPDATE`.
  <br>**`intransit`, one word.** This page said `/in-transit` for a long time and the endpoint never
  did - a client following the docs got a 404. The supplier's step of the same name
  (`POST /supplier/{requestId}/intransit`) is spelled the same way, so there is one spelling to
  remember now; it used to be hyphenated on that side, which is how the mistake survived.
  <br>There is a fourth, `PATCH /shipments/{id}/trackingnumber` (ADMIN, DISTRIBUTOR): the carrier's
  own reference, body `{ "trackingNumber": "DHL-123" }` (`TrackingNumberRequest`), refused once the
  shipment is `DELIVERED` or `CANCELLED` and only from the assigned distributor. It moves no status
  and takes no order along. The body used to be a bare `@RequestBody String` - not a JSON object at
  all; the length bound now lives in one place (`TrackingNumberRequest.MAX_LENGTH`) and the service
  reads it from there, because the service is also reachable from inside, where no bean validation
  runs.
  <br>**Only the assigned distributor reports** (`requireReportingDistributor`): another carrier is a
  403, a shipment without an assignment a 409, and ADMIN is exempt as the role that has to be able to
  correct things. Checked **before** the status, so a carrier poking at a shipment that is not theirs
  learns nothing about where it stands. The ADMIN exemption reads the stored roles, not anything the
  request carried.
  <br>Why `accept` needs `DISPATCH_REQUESTED` rather than `READY`: that status is set by
  `assignDistributor` alone. Without it the handover was optional - any carrier could take on any
  ready shipment, nobody had to be assigned, and since `GET /shipments/distributor` filters on
  `distributor_id`, every work list stayed empty while shipments ran to `DELIVERED` with
  `distributor_id` still `NULL`. The whole carrier half of the chain was reachable but undiscoverable. None of the three names an order status: each one ends in
  `OrderProgressService.recompute` with the orders of the shipment, and where those really stand is
  worked out there. Each step takes **the orders of the shipment** along - `READY_FOR_DISPATCH`,
  `IN_TRANSIT`, `DELIVERED` - found through the packages (`OrderRepository.findByShipmentId`, a
  shipment may carry packages of several orders of its customer). Where they end up is
  **`OrderProgressService.recompute`**, not the shipment. It publishes the `OrderStatusChangedEvent`
  and runs `Propagation.MANDATORY` - without the caller's transaction an `AFTER_COMMIT` listener
  would drop the event, so it refuses to run outside one.
- **Closing an order** (`POST /orders/{orderNo}/complete`, ADMIN and MANAGER,
  `OrderService.complete`): the commercial end, and the only way to `COMPLETED`. Checked on
  **quantities**, not on statuses: every line has to be covered by `PackageItem`s that travelled in
  shipments reporting `DELIVERED` (`OrderItemRepository.findDeliveredQuantities`,
  `OrderProgressService.undeliveredLines`). Counting lines would not do - a line may be packed in
  several runs and travel in several shipments, so 5 of 10 delivered is not a delivered line. Every
  join of that query is an inner one, so a line nothing has arrived for is **missing from the
  result**: coverage is counted from the order's own lines, never from the query alone. Four 409s:
  already `COMPLETED`, ended in `REJECTED`/`CANCELLED` (which `recompute` leaves alone, so without
  the guard the endpoint would answer 200 for nothing), no lines at all (nothing could be missing, so it would close on the
  spot), and something still short - that last message names every line with ordered against
  delivered. `OrderStatus.DELIVERED` is deliberately **not** a precondition: it is set by whichever
  shipment arrives first and says less than the quantities do.
- **An order's history** (`GET /orders/history/{orderNo}`, ADMIN, MANAGER, WAREHOUSE, LOGISTICS and
  CUSTOMER; `OrderHistoryService`, `OrderHistoryResponse`): every status the order went through, **newest
  first** (`changedAt` descending, then `id` descending), each with the status it came from, the one it
  went to and when. **Both keys run the same way, and the second one matters**: rows written within one
  transaction carry the *same* timestamp (measured: 251 of 300 three-row histories, although the column
  is `datetime(6)`), and for them the id is the whole order. With the id ascending under a descending
  time, those rows came back in the opposite order to the rest - and the endpoint tests failed now and
  then, because most of their rows tie. `previousStatus` is `null` on the **last** row - the creation
  comes from nowhere, and that stays in the JSON as a null. The path takes the **order number**, like every other order endpoint; not paged,
  because an order has as many rows as it has had status changes.
  <br>**The access rule is not written a second time**: the order is fetched through
  `findByOrderNoForUser`, the call `GET /orders/{orderNo}` makes, so a history can never be reachable
  where its order is not (404 unknown, 403 for a customer asking about somebody else's) - a history says
  more than the order does.
  <br>**Staff see who made each change, a customer does not.** `changedById` and `changedByName` are
  left out for a caller who may not read any order: a customer reads what happened and when, and a name
  there is the name of an employee. Their lookup is not merely hidden, it is never made. For staff both
  are absent where the row has no acting user (`OrderHistory.user_id` is nullable - an automatic step has
  nobody to name). The names come from **one** query for all rows (`userId` is a plain column, not an
  association, so there is nothing to fetch-join), and use `DisplayNames`, the one rule for what a person
  is called that the supplier list and the component requests share.
- **Who may read an order that is not their own is `RoleService.canReadAnyOrder`**: ADMIN, MANAGER,
  WAREHOUSE and LOGISTICS. It is **not** `isPrivilegedUser`, which is ADMIN and MANAGER and answers other
  questions too (which product fields a caller sees, whose orders a list shows). Reading an order asked
  *that* one, so WAREHOUSE and LOGISTICS - both named in `@PreAuthorize` on `GET /orders/{orderNo}`, and
  LOGISTICS added on purpose for the due date - passed the annotation and were then refused by the
  service as "another customer" for **every** order there is. Neither `ApiAuthorizationTest` (it asks an
  order number that does not exist, so the answer is a 404 whatever the service would say) nor
  `OrderServiceAccessTest` (it mocks the role service) could see it; `OrderReadAccessTest` can, because it
  reads a real order. Measured before the fix: two cases red, five green.
- **Cancelling an order** (`POST /orders/{orderNo}/cancel`, ADMIN and MANAGER - deliberately not the
  customer, since it frees stock and ends the order; `OrderService.cancel`): only **while nothing has
  physically moved**. A line past `RESERVED` has its goods off the shelf and its reservation
  `CONSUMED`; booking them in again is an operation this application does not have, so such a line is
  a 409 naming every one of them (`FulfillmentService.linesPastReservation`). An order whose packages
  already travel in a shipment is a 409 too - that is `POST /shipments/{id}/cancel`, which hands the
  packages back and lets the orders fall with them - and `REJECTED`/`COMPLETED`/`CANCELLED` are ends.
  <br>It undoes what the order held: every active reservation goes back to stock through
  `releaseItems`, and every line ends on `FulfillmentStatus.CANCELLED`. **Order of operations**: the
  status is written *before* the release, which looks backwards and is not - `releaseItems` takes an
  `IN_FULFILLMENT` order to `APPROVED` on its way out (`revertOrderStatus`, which bails out for any
  other status), so releasing first would leave a step in the history that never happened. The lines
  are set *after* it, because the release puts them back to `WAITING`.
  <br>`linesPastReservation` and the existing `hasLineBeyondReservation` share one predicate, so the
  rule cannot drift; `CANCELLED` deliberately does not count as past reservation - the line holds
  nothing and nothing moved for it.
- **Calling a shipment off** (`POST /shipments/{id}/cancel`, ADMIN, LOGISTICS **and DISTRIBUTOR** -
  one endpoint for both sides): only up to
  `ACCEPTED` (409 afterwards - once it rolls it is a return, which the process does not model), and
  only with a reason (400), which is kept in `comment`. It undoes what the shipment had set in
  motion: the packages are loose again and stay `PACKED`, lines go from `READY_FOR_DISPATCH` back to
  `PACKED`, and the orders fall back on their own - `recompute` runs **after** the packages are
  detached, sees the coverage gone and writes what is left, unless a line of theirs travels in
  another shipment. The order *ids* are read **before** the packages are detached: they are found
  over the packages, which the shipment no longer holds afterwards.
  <br>The carrier used to have a second `PUT` on the same path, answering `DeliveryResponse` so as
  not to show it the package contents. Merged, because after the cancellation the packages are
  **detached** - the answer carries none either way, so there was nothing left to keep from a
  carrier, and two verbs on one path meant guessing and getting a 403 instead of a hint.
  `DeliveryService.cancelShipment` is gone with it.
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
  and publishes the event - `recompute` is the variant that works the target out for itself, and
  `recordCreated` is the first row of a new order. `OrderServiceImpl` (create, update, and with
  it `acknowledge` and `reject`) and `FulfillmentServiceImpl` (`IN_FULFILLMENT`, back to `APPROVED`)
  all go through it. Do not write `order.setStatus(...)` and publish by hand - every caller that did had
  its own idea of what to do when the user could not be resolved, and one of them dropped the audit
  row. `changeStatus` ignores a `null` target and one the order already has, so a CRUD update
  without a status leaves the order where it is.
- Order-status transitions publish `OrderStatusChangedEvent` via `ApplicationEventPublisher`;
  `OrderStatusChangedListener` (`@TransactionalEventListener(phase = AFTER_COMMIT)`) writes an
  `OrderHistory` audit row. Follow this event pattern for any new status-changing code path instead
  of writing history rows inline. `OrderHistory.user_id` is **nullable** - a sweep running as
  `"system"` resolves to nobody and its audit row still has to be written
  (`OrderHistoryUserIdMigration` widened the column, `OrderHistoryUserIdTest` holds it). Two things are load-bearing here: the publishing method must be
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
  two days after they were consumed. A `CONSUMED` row holds no stock but it does hold the line's one
  slot (`uk_reservation_order_item`), so the line can never be reserved again while it is there.
  <br>The age comes from **`Reservation.consumedAt`**, stamped by `consume()`, falling back to
  `expiresAt` for rows consumed before that column existed (`consumedOrExpiredAt()`); a row with
  neither is left alone, because unknown age is not old age - and the comparison used to be an NPE.
  It measured `expiresAt` alone before, which is an hour after *reserving*, so a line picked weeks
  later looked days old the moment it was picked.
  <br>`deleteReservation` is `@Transactional` (there is no session in a scheduler) and **touches no
  status**. It used to call `revertOrderStatus`, which only acts on `IN_FULFILLMENT` while this sweep
  works on `READY_FOR_DISPATCH` orders - so it never did anything, and had it fired it would have been
  wrong: the order follows the shipments its lines travel in, not a bookkeeping row. Reaching the
  order for that call, *after* the delete, was the `LazyInitializationException` the sweep died on.
  The `systemUser` parameter went with it. The `@Scheduled` is still commented out.

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
bound from JSON, an entity accepts every field it has, **`id` included**, and `save()` with an id
present is a *merge*: a `POST` overwrites whichever row already carries it. `UserController` took
`User` and with it `id`, `userType` and full `Role` objects; `ComponentController` and
`ProductController` took `Component` and `Product` the same way. All three now bind records:
`UserRequestDto` (roles as names), `ComponentRequestDto` and `ProductRequestDto`, none of which has
an id field - the hole is closed by construction, and the service builds its own entity.
`StockController` took a `Map<String, String>` for `/stock/add`, which is the same thing without even
a schema: unvalidated, and a missing `sku` ended in an NPE inside a `"new"` check that otherwise
invented a random UUID and booked stock onto a SKU no article carries. It binds `AddStockRequest`
now.

Two details of those two worth keeping:
- **A component names its product by `articleNo`**, not as a nested `{"product": {"id": 3}}`. That
  was an entity inside an entity of which exactly one field was ever read; `articleNo` is unique and
  NOT NULL and is what the rest of the API names a product by. Categories likewise arrive as
  `categoryIds` and are looked up (`ProductCategoryRepository`) - they used to arrive as whole
  `ProductCategory` objects, which carry a `Set<Product>` of their own.
- **A component line of a `ProductRequestDto` keeps an optional `id`**, because `applyComponents`
  uses it to match one of the product's existing lines. It is checked against *that product's own*
  lines (400 otherwise) and refused outright on create. Unchecked, `cascade = ALL` on
  `Product.components` reassigned a line belonging to another product to this one - rewriting a
  foreign bill of materials with nothing in the request saying so.
- `ComponentServiceImpl.apply` is the one place deciding which fields a client may set, shared by
  create and update. They used to disagree: `update` copied a smaller selection, so `description`,
  `weight`, `articleNo` and `qty` could be created but never changed.

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

**Packages are WAREHOUSE, shipments are LOGISTICS, and the two do not overlap.** Everything under
`/packages` and `/shipment-packages` is packing work (ADMIN, WAREHOUSE); everything under
`/shipments` is planning (ADMIN, LOGISTICS). LOGISTICS used to have the two package lists as well -
removed, because the split is meant to be clean. What the planning side needs from them it gets from
a view of its own: **`GET /shipments/packages`** (ADMIN, LOGISTICS,
`ShipmentService.findShippablePackages`) - the `PACKED` packages that are in no shipment yet, which
is exactly what `POST /shipments` and `POST /shipments/{id}/packages` take. Same arrangement as
`GET /shipments/distributor` for the carrier: a role gets its own view instead of read access to
another's list. It answers the `ShipmentPackageListDto` the warehouse's list shows, paged and in two
queries like every other package list, and the status is wired to `PACKED` rather than a parameter -
nothing else is shippable, and a free `OPEN` package is packing work. It also says more than the
filtered package list did, which still shows the packages already travelling in a shipment: every id
taken from there was a guess `POST /shipments` answered with a 409.

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

`CreatePackageItemsRequest`, `PackageItemIdsRequest`, `ShipmentPackageIdsRequest` and
`RequestComponentsRequest` also accept the bare JSON array (`[...]`) next to the wrapped form, through a static factory with `@JsonCreator(mode = DELEGATING)`; the
record's canonical constructor still reads the object form. Both end in the same record, so the
validation applies to either.

### Error responses

**One shape.** Everything goes through `GlobalExceptionHandler` and answers `ErrorDetails`
(`timestamp`, `message`, `path`, `errorCode`); a failed bean validation comes back as a map of field
to message. **Never catch `APIException` in a controller** - throw it and let the handler answer.

`PickingController` and `PackingController` used to catch it and answer a bare `{"message": ...}`
map, so a client had to know two error shapes and tell them apart by the path. Nothing was lost in
removing that: `ErrorDetails` carries the same `message` under the same name, which is why the change
broke no caller - checked rather than assumed, and no Vaadin view or Postman collection read the
field at all.
<br>With the catches gone the return types are the DTOs themselves rather than `ResponseEntity<?>`:
the wildcard only existed so a `Map` could share the signature with the success body. The
`packageResponse(Supplier<...>)` wrapper in `PackingController` went with them - it was the catch and
nothing else. `PickingControllerTest` is new, because that controller had no test at all and half of
this change would otherwise be unverified.

### Paged list endpoints

New list endpoints take their parameters through **`PageRequests.of`** (package-private in
`controller`): it refuses a `sort` that is not on a list the endpoint names, a negative `page` and a
`size` below one, all as a **400** that says which parameter and what is allowed. Those three used to
end as a 500 - nothing handles `PropertyReferenceException`, and `PageRequest.of` throws
`IllegalArgumentException` - for what is a typo in a query string. `/storehouses` and
`/components/suppliers` use it; the older lists still have the weakness.

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
shipment_package_id BIGINT NULL`.

**A new enum value needs the `CHECK` constraint widened.** Hibernate writes
`order_status in ('CREATED', ...)` on first creation and `update` never touches it again, so a value
added later is rejected at insert - and twice over, because `order_history` carries the same check on
`previous_status` and `new_status`, and that insert happens inside the `AFTER_COMMIT` listener, where
the error turns an operation that already succeeded into a 500. `StatusCheckConstraintMigration`
rewrites them from the enum itself, so the next added value is covered without touching it - it
covers `order_items.fulfillment_status` as well, which is where `FulfillmentStatus.CANCELLED` ran
into the same wall. Each column carries its own enum class. Note **how** it drops the old check: Hibernate writes it into the *column* definition, and a
column constraint is not reachable through `DROP CONSTRAINT` (MariaDB answers "Can't DROP CONSTRAINT;
check that it exists") - the column has to be rewritten with `MODIFY COLUMN`, keeping its type and
nullability. A constraint the migration added itself is a *table* constraint and does come off with
`DROP CONSTRAINT`, which is why that is tried first. `StatusCheckConstraintTest` writes every
enum value to all three columns; a unit test cannot see any of this.

**A changed id type is the worst case of this.** `request_components.id` was a `UUID` when the table
was created; the entity has since become a `Long` with `GenerationType.IDENTITY`, and `update` left
the column as `uuid` without `AUTO_INCREMENT`. Hibernate then leaves the column out of the insert and
MariaDB answers `Field 'id' doesn't have a default value` - the endpoint was unusable, and no unit
test could see it. `MODIFY COLUMN` does not help here either: MariaDB refuses with
`Cannot cast 'uuid' as 'bigint' in assignment`, whatever the table holds. The column has to be
**dropped and added back** (`RequestComponentIdMigration`), with `PRIMARY KEY` in the same statement -
an `AUTO_INCREMENT` has to be a key - which is only safe because the table was empty; the migration
refuses a table with rows and logs the statements instead. `RequestComponentIdTest` inserts a request
and holds it.

Data migrations that cannot wait for a real migration tool run as
an `ApplicationReadyEvent` listener in `config` (`OrderDateToCreatedMigration`), guarded so they do
nothing once done. Note that `@SpringBootTest` runs them as well, against the same database.

### Business/aspect utilities

- **The supplier's own list** (`GET /supplier/my-requests`, ADMIN and SUPPLIER,
  `InboundController`) answers
  `RequestComponentResponseDto`, mapped by `RequestComponentMapper` in `RequestComponentServiceImpl`
  **inside the transaction** - the controller used to map afterwards, which only worked because
  open-in-view kept a session around to resolve the references from. The rows arrive with their
  component fetched through an `@EntityGraph` on `findBySupplierId`, so the list is one query.
  <br>Two things in that DTO were wrong and both were silent. Its `component` field held the
  `Component` **entity**, so the response serialized the component's product along and resolved that
  graph one LAZY reference at a time - exactly what "responses are DTOs, never entities" is about; it
  is a `ComponentResponseDto` now, which carries a `ProductRefDto` and nothing deeper. And its `qty`
  was an `Integer` against the entity's `Long`: the response holds **two** quantities under that name
  - the request's (how many were ordered, a `Long` over a `bigint`) and `component.qty` (how many go
  into one product, an `Integer`) - and the outer one had been copied from the inner. MapStruct
  narrowed the ordered quantity without a word. `RequestComponentMapperTest` holds all of it,
  including that the two quantities stay apart.
  <br>It deliberately does **not** carry `assignedBy`: the supplier sees their own request, and which
  person inside the house last moved it is none of their business. The warehouse's view of the same
  rows, `GET /components/requests`, does name them - that one answers `RequestComponentResponse`,
  which every write endpoint answers too.
- **Reading the component catalogue** (`GET /components`, `/components/{sku}`,
  `/components/article/{articleNo}`) is ADMIN, MANAGER and **WAREHOUSE** - the warehouse may order
  components, and without the catalogue it would have to get the SKU from somewhere else. Creating and
  changing a component stays ADMIN/MANAGER.
- **Ordering components from a supplier** (`POST /components/request/{supplierId}`, ADMIN, MANAGER
  and WAREHOUSE - a method-level `@PreAuthorize` widening the controller's ADMIN/MANAGER, because the
  warehouse owns the stock and already consumes components through `POST /produce`;
  `ComponentServiceImpl.requestComponents`): every entry of the body becomes its own
  `request_components` row, in `RequestStatus.OPEN` (the entity's `@PrePersist`). The lines name
  their component by **SKU** - the JSON key is `componentId`, the `UUID` type is what says which of
  the two it is. A repeated SKU stays two rows: two requests for the same part, each with its own
  quantity and reason, unlike an order line, where `mergeDuplicateProducts` adds a repeat up. The
  supplier is a 404 when there is no such user and a 400 when they are not a `Supplier`, checked
  after `Hibernate.unproxy` like the distributor of a shipment. All or nothing: one unknown SKU
  refuses the whole request with a 404 naming every one of them, so a caller never has to work out
  which half went through. One `findBySkuIn` for the whole body, not one query per line; an empty
  comment is stored as `null`. There is **no internal release step**: `RequestStatus` is the
  supplier's side throughout, so whoever may call this orders straight away.
- **The warehouse's work list** (`GET /components/requests`, paged, optional `status`, ADMIN and
  WAREHOUSE - a method-level `@PreAuthorize`; `ComponentService.findRequests`): why it exists is the
  point. The goods receipt takes a *request id*, and the only way to read requests was
  `GET /supplier/my-requests`, which is the supplier's own list (`findBySupplierId`) - so the role
  that has to call the receipt had no way to learn which request is `DELIVERED`, and an ADMIN asking
  that list got their own, empty one. Same arrangement as `GET /shipments/distributor` and
  `GET /shipments/packages`: a role gets its own view rather than read access to another's. Oldest
  first by default (`sort=requestDate`) - a pile at the dock is worked off in the order it arrived.
  One query per page: `component` and `assignedBy` come along through an `@EntityGraph`, both to-one,
  so it stays paged in SQL; `supplier` is left out because only its id is read and a proxy answers
  `getId()` unloaded. Plural next to the singular `POST /request/{supplierId}`, whose path is left as
  clients know it.
- **Who moved a request** is `RequestComponent.assignedBy` - a nullable `@ManyToOne User`, written by
  every step next to `updated`, which `@UpdateTimestamp` stamps itself. Not a history, one line of
  it: the statuses say where the request has been, this says who put it there last; a real audit
  trail would be a table like `OrderHistory`. Nullable on purpose - rows placed before the column
  existed have nobody to name, and a user the lookup cannot resolve must not fail the step being
  reported (same reasoning as `OrderHistory.user_id`). A `User` and not a `Supplier`, because the
  supplier's three steps and the warehouse's receipt both write it. `requestComponents` takes the
  acting user for this reason - it did not before. Written **after** the checks, so a refused step
  leaves the row untouched. `RequestComponentResponse` answers it as `assignedById` +
  `assignedByName`.
- **The quantity has two bounds, and they do different jobs.**
  `RequestComponentsRequest.MAX_QTY` (1,000,000, as `@Max` on the line) is the one at the request: a
  mistyped number is a 400 naming the field, and the limit reads as a business one rather than a
  type's. `receiveRequest` then refuses anything that does not fit an `int`, because the receipt books
  through `Long.intValue()` and 3,000,000,000 comes out as -1,294,967,296 - a receipt that *lowers*
  the stock it is meant to raise. The first gate can be raised, the second cannot be passed.
  <br>`request_components.qty` is a `bigint` since `RequestComponentQtyMigration`. It was an
  `int(11)` while the entity said `Long` - the entity's type was widened and `ddl-auto=update` never
  follows a type change - so with `STRICT_TRANS_TABLES` the server refused an out-of-range quantity at
  the insert and a mistyped number came back as a **500**. `int` to `bigint` is a widening, so unlike
  `request_components.id` it is a plain `MODIFY COLUMN` on a populated table; the migration reads the
  current type from `information_schema`, leaves a `bigint` alone and keeps the nullability, which
  `MODIFY COLUMN` would otherwise drop. `RequestComponentQtyTest` holds both the type and that a value
  beyond the `int` range survives a round trip.
- **The supplier answers** (`POST /supplier/{requestId}/approve`, `.../intransit` and
  `.../delivered`, ADMIN and SUPPLIER - in **`InboundController`** under
  `/api/{version}/supplier`; the five supplier steps and `GET /supplier/my-requests` moved out of
  `ComponentController`, and `InboundControllerTest` moved with them. Note `intransit` is one word
  here too, the same spelling the shipment side uses):
  `OPEN → APPROVED → IN_TRANSIT → DELIVERED`, one step at a time, any other status a 409. The
  request is read `FOR UPDATE` (`findForUpdateById`), because the status is checked and then written.
  `DELIVERED` is the supplier's last step and books nothing - it is what they *claim*, namely that
  the goods are at our dock. `IN_STOCK` is what we found when we unpacked it, and that one belongs to
  the warehouse, see below. Those two looking alike is the point: claim and check, and therefore two
  roles.
  <br>**Only the supplier the request was placed with answers it** (`requireAnsweringSupplier`):
  another supplier is a 403, a request without a supplier a 409, ADMIN exempt. Checked **before** the
  status, so a supplier poking at a request that is not theirs learns nothing about it. No supplier id
  in the path - it comes from the authenticated user, the same way the distributor's does.
  <br>The ADMIN exemption on both sides goes through `RoleService.isAdmin(Long userId)`, which reads
  the stored roles rather than anything a request carried. One implementation for the carrier and the
  supplier side; `DeliveryServiceImpl` had a private copy until this was added.
- **The supplier says no** (`POST /supplier/{requestId}/reject` and `.../cancel`, ADMIN
  and SUPPLIER, `InboundController`): `OPEN → REJECTED` is declining a request before anything was promised, so there is
  nothing to undo; `APPROVED`/`IN_TRANSIT` → `CANCELLED` is calling off one that had been taken on.
  Two statuses rather than one because they say different things. **Not from `DELIVERED`** - the
  pallet is at our dock then and calling it off would be a return, the same line the shipment side
  draws at `ACCEPTED`. `requireAnsweringSupplier` before the status like the other steps, and
  `assignedBy` recorded. No body: there is no column for a reason, and `comment` belongs to whoever
  ordered the part - overwriting it would throw away why it was needed. Note the ordering side cannot
  withdraw a request; only the supplier and ADMIN can end one.
- **The goods receipt** (`POST /components/warehouse/{requestId}/in-stock/{storehouseId}`, ADMIN and
  WAREHOUSE, `ComponentServiceImpl.receiveRequest`): `DELIVERED → IN_STOCK`, and the requested
  quantity is added to `Stock.onHand` for that component's SKU in the receiving storehouse through
  `StockService.add`. A physical receipt at the dock, which the supplier cannot report and which
  belongs to the role that owns the stock. Any other status is a 409, an unknown storehouse a 404 -
  checked here rather than left to `StockService`, whose `IllegalArgumentException` would end as a
  500 for a wrong path variable. One transaction: if booking the stock fails, the status does not move.
  <br>**`DELIVERED` and not `IN_TRANSIT`** on purpose: the receipt answers a handover the supplier
  reported, rather than guessing that the pallet has arrived. That gate hangs an internal step on an
  external party, which the ADMIN exemption on the supplier steps already covers - an ADMIN may report
  `DELIVERED` for a silent supplier, so the gate is strict without being a dead end.
  <br>Not LOGISTICS, although inbound freight might sound like it: in this codebase that role owns
  shipments and nothing else, while writing stock is `ADMIN`/`WAREHOUSE` everywhere
  (`StockController`, `/produce`). The receipt writes stock.
  <br>**The quantity goes to `Stock`, never to `Component.qty`** - that one is the bill of materials,
  how many go into *one* product, and adding a delivery to it would silently rewrite the recipe of
  every product using the part. `ComponentServiceReceiveTest` holds both halves.
  <br>The storehouse comes from the path because the request has no such field: booked in is where
  the goods actually arrived. All or nothing per row - a request of 12 arriving as 8 + 4 cannot be
  expressed by one status, which would need a received quantity on the row.
  <br>**This is the only place component stock grows** other than `StockController` by hand. Without
  it `assemble()` runs every 150 s and eventually finds nothing left to build from.
  <br>`RequestStatus` ends here. There used to be an `ASSEMBLED` after it, which could never be set
  correctly: `Stock` is keyed by `(storehouse_id, sku)` and holds a quantity with no batch and no
  reference back to the request, and `produce()` only decrements it - nothing records whose screws
  went into which product, and one status per row could not express "4 of 12 built in" either.
- **A product's recipe is `Component.qty`.** A `Component` row belongs to exactly one product
  (`product_id` is `NOT NULL`), so it is a bill-of-materials line and not a shared catalogue part -
  which is why the quantity sits on it and not on a join table. `NOT NULL`, `@ColumnDefault("1")`,
  and `@PrePersist` lifts a missing or non-positive value to 1, because a line that is part of the
  recipe is needed at least once. `ProductionServiceImpl.getRequiredComponents` sums it per SKU;
  it used to count every line as one, so a product needing four screws consumed one and was built
  out of stock that was never there. A line without a SKU or without a usable quantity makes the
  whole recipe invalid (empty map, "no valid component requirements") rather than being guessed at -
  `assemble()` runs this unattended every 150 s and a wrong recipe consumes real stock.
  `ComponentQtyMigration` gives existing rows a 1; it is idempotent and stays in place.
- `service/business/AutomaticProductionService` — `assemble()` builds products from component
  stock every 150 s (`ProductionServiceImpl.produce`/`produceSingleProduct` picks the storehouse
  with enough components and decrements them, then adds the produced unit as stock). `POST /produce`
  runs the same by hand and answers `ProductionPageResponse` (the page plus `produced`, the number
  actually built).
- `@NoCheck` (`annotation/NoCheck.java`) + `NoCheckAspect` — a marker annotation logged via AOP
  `@After` advice; check existing usages before assuming it changes authorization/validation
  behavior (currently logging-only).
- **The suppliers a request can be placed with** (`GET /components/suppliers`, ADMIN, MANAGER and
  WAREHOUSE - the roles that may place one; `SupplierResponse`, `SupplierRepository`): the warehouse
  may order components but cannot read `/users`, so it had no way to learn a supplier's id. **Id and
  name only** - the e-mail address, the login and the roles are the business of whoever reads
  `/users`. **Active suppliers only**: a disabled account cannot log in, so it could never approve,
  send or deliver, and a request placed with it would sit in `OPEN` for good. The repository is typed
  `Supplier`, so the `user_type` discriminator is added by Hibernate and cannot be forgotten; the name
  is the one `RequestComponentResponse` uses for the same person (first and last name, the login only
  when there is neither). Paged, sorted by `lastName` by default. Note `POST /components/request/...`
  does **not** itself refuse a disabled supplier - the list hides them, the endpoint does not check.
- **Storehouses are listed, read-only** (`GET /storehouses`, ADMIN and WAREHOUSE,
  `StorehouseController`/`StorehouseService`, `StorehouseResponse`): two warehouse operations take a
  `storehouseId` - the goods receipt and `POST /stock/add` - and nothing said which ids exist, so a
  client had to be told them out of band. Paged like every list, sorted by `name` by default; a
  dropdown is `?size=100`. `sort` is checked against `id`, `name`, `city`, `country` and anything else
  is a **400** naming it: it is applied as a property path, and an unknown one ended in a
  `PropertyReferenceException` that nothing handles - a 500 for a typo in a query parameter. (The other
  list endpoints still have that weakness.) The entity's `stocks` collection is never read, so the list
  is one query; what a storehouse holds is `GET /stock/storehouse/{id}`. No create or change: they are
  set up with the system.
- **Booking stock is `@Transactional` and reads `FOR UPDATE`** (`StockService.add`,
  `findForUpdateByStorehouseIdAndSku`) - like everywhere else here that reads a value, checks it and
  writes it back. It was neither: from `POST /stock/add` no transaction came along at all, so the read
  and the write were two of them and two concurrent bookings computed from the same quantity. What
  saved the data was `@Version`, and what the caller got was a 500 for a request that was fine. The
  goods receipt and `produce()` book into the same rows, and `assemble()` runs every 150 s. It joins
  the caller's transaction rather than `REQUIRES_NEW` on purpose: the goods receipt is all or nothing.
  <br>One race is left where it cannot be closed - with no row yet there is nothing to lock, so two
  first-ever bookings of a SKU can both insert. That is flushed on the spot and answered as a 409 that
  says so, instead of a 500 at an outer commit; a retry would need its own transaction, which the
  receipt must not have.
- `service/ratelimiting` — `RateLimitingFilter` + `PricingPlanService`, backed by `bucket4j`
  (`PricingPlan` enum), gates request rate by plan.
