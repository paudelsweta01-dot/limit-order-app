# Backend — Implementation Plan

**Companion to:** [`architecture.md`](../architecture/architecture.md). This plan is *what* to build, in *what* order, with *what* signals "done." The architecture doc explains *why*.

**Scope:** Java 17 / Spring Boot 3.x backend in `backend/`. Single Maven module. Postgres-only persistence and fan-out.

**Definition of Done (component-level):**
- `mvn verify` is green: unit + integration + concurrency tests.
- The §5.3 seed scenario is replayable in a JUnit test and produces exactly the §5.4 snapshot.
- Two instances on different ports against the same Postgres, both visible to a WS client within 1s of any state change on the other.
- All endpoints in §7 (API sketch) live; idempotent submit verified by retry.
- `/actuator/health` green; structured JSON logs for every order event; Prometheus metrics exposed.

**Dependencies on other components:** none. Backend is the entry point everything else builds against.

---

## Phase 0 — Repository hygiene

Pre-work that doesn't ship code but unblocks everything else.

| # | Task | Acceptance |
|---|---|---|
| 0.1 | Create `backend/` with Maven layout (`pom.xml`, `src/main/java`, `src/main/resources`, `src/test/java`). | `mvn -pl backend validate` succeeds. |
| 0.2 | Add `.gitignore` entries for `target/`, IDE files. | Commit doesn't include build artefacts. |
| 0.3 | `pom.xml` declares Java 17, Spring Boot 3.x BOM, dependencies: web, validation, actuator, security, data-jpa, jdbc, postgresql, flyway-core, flyway-database-postgresql, micrometer-prometheus, jjwt, bcrypt (in spring-security-crypto), testcontainers (postgresql), spring-boot-starter-test, awaitility. | `mvn -pl backend dependency:tree` resolves; no version warnings. |

---

## Phase 1 — Skeleton & infrastructure plumbing

Get a Spring Boot app booting against a real Postgres with Flyway and JSON logs.

| # | Task | Acceptance |
|---|---|---|
| 1.1 | `LobApplication.java` with `@SpringBootApplication`. Default `application.yml` with placeholders for DB URL, JWT secret, instance id, outbox retention. | `mvn spring-boot:run` boots and exits cleanly when DB is reachable. |
| 1.2 | Logback JSON encoder configured (e.g. `logstash-logback-encoder`). MDC filter that puts `instance`, `requestId`, `userId` on every line. | Sample request emits a single JSON log line with the three fields populated. |
| 1.3 | Hikari config: `maximum-pool-size=20`, sensible timeouts. | Visible in startup log; no warnings. |
| 1.4 | `/actuator/health` exposes DB health; `/actuator/prometheus` exposes Micrometer metrics. | `curl /actuator/health` returns `UP`. |
| 1.5 | Global `@RestControllerAdvice` translates `MethodArgumentNotValidException`, `DataIntegrityViolationException`, `IllegalStateException`, `AccessDeniedException` to the §4.11 error envelope `{ code, message }`. Stack traces never leak. | Unit test on the advice produces the expected JSON for each branch. |

---

## Phase 2 — Schema & seed data

Flyway migrations matching §7 of the blueprint.

| # | Task | Acceptance |
|---|---|---|
| 2.1 | `V1__init.sql`: types, `users`, `symbols`, `orders` (with `CHECK (filled_qty <= quantity)`, conditional price/type check, `UNIQUE(user_id, client_order_id)`), `trades`, `market_event_outbox`, partial index `orders_book_idx`, the `notify_market_event` trigger. | Booting against a fresh DB applies the migration cleanly. `\d+ orders` shows all constraints and the partial index. |
| 2.2 | `V2__seed_symbols.sql`: AAPL/MSFT/GOOGL/TSLA/AMZN with refPrices from §5.1. | `SELECT * FROM symbols` returns the five rows. |
| 2.3 | `V3__seed_users.sql`: u1–u4 (Alice, Bob, Charlie, Diana) with BCrypt-hashed passwords from §5.2. **Hashes generated at migration build time, not runtime** (so the migration is deterministic and idempotent — no `gen_random_uuid()` for hashes). Alternative: a Flyway Java callback that hashes if the row is missing — cleaner; prefer this. | Login with `alice / alice123` succeeds (verified once Phase 4 lands). |
| 2.4 | Testcontainers config (`@Testcontainers` + `PostgreSQLContainer`) wired into a `@TestConfiguration` so every integration test gets a fresh, migrated DB. | A trivial repository test passes. |

---

## Phase 3 — Matching engine + scenario test (the keystone)

This phase is the project's load-bearing wall. It must be done before anything else interesting.

| # | Task | Acceptance |
|---|---|---|
| 3.1 | `OrderRepository` with two custom JdbcTemplate methods: (a) `selectBestOpposite(symbol, side, type, limitPrice)` returning the resting order to match against (uses `orders_book_idx`), (b) `applyFill(orderId, addQty, newStatus)` returning rows affected. | Repository unit tests against Testcontainers verify the queries use the partial index (`EXPLAIN`). |
| 3.2 | `AdvisoryLockSupport` thin wrapper that calls `pg_advisory_xact_lock(hashtext(:symbol))` via JdbcTemplate. Asserts the surrounding `@Transactional` is active. | Throws if no transaction; happy-path passes a smoke test. |
| 3.3 | `MatchingEngineService.submit(SubmitOrderCommand)` — `@Transactional`, takes the lock, runs the §4.4 algorithm, writes outbox events. Returns `OrderResult { orderId, status, filledQty, rejectReason }`. | All-or-nothing: a forced exception mid-loop rolls back the entire submit (verified by integration test). |
| 3.4 | `MatchingEngineService.cancel(CancelOrderCommand)` — same lock, ownership check, status check, write outbox. | Returns 404/403/409 conditions; partial-fill cancel preserves `filled_qty`. |
| 3.5 | **Scenario test**: load `docs/requirnments/seed.csv` (extract from §5.3 into a fixture file), submit each order through `MatchingEngineService.submit` in order, assert the §5.4 book snapshot exactly (book read via repository, top-5 levels per symbol, verified field-by-field) and the four expected trades (c005/c002, c007/c006, c009/c008 — three trades, since c004/c001/c003/c010 don't cross). | Test green. **This test is the project's "yes it works" signal.** |
| 3.6 | Engine unit tests: price-time priority, partial fill cascade across multiple resting orders, MARKET full-fill, MARKET partial+reject (`INSUFFICIENT_LIQUIDITY`), MARKET against empty book, LIMIT that doesn't cross. | All green. |
| 3.7 | Concurrency soak test: 100 threads × 1000 orders submitting random BUY/SELL/MARKET at 5 symbols against the engine directly (no HTTP). At end, assert: (a) `Σ filled_qty BUY == Σ filled_qty SELL` per symbol, (b) no `filled_qty > quantity`, (c) every trade references valid opposite-side orders with matching user ids. | All green. Run completes in <60s. |

---

## Phase 4 — Authentication

Stateless JWT in front of everything else.

| # | Task | Acceptance |
|---|---|---|
| 4.1 | `AuthController.login(LoginRequest)` validates against BCrypt-hashed `users.password_hash`, returns `{ token, userId, name }`. | `curl -d '{"username":"alice","password":"alice123"}' .../api/auth/login` returns a JWT. |
| 4.2 | `JwtService` — sign HS256 (12h TTL), verify, extract claims. Secret from `JWT_SIGNING_SECRET` env. | Unit tests cover sign/verify/expiry/tamper. |
| 4.3 | `JwtAuthFilter` (Spring Security `OncePerRequestFilter`) — bearer token → `SecurityContext` with `userId` as principal. WS upgrade also passes through this filter. | Protected endpoint returns 401 without token, 200 with valid token. |
| 4.4 | Security config: `/api/auth/login` and `/actuator/health` open; everything else authenticated. CSRF disabled (stateless API). | `mvn test` covering both paths. |

---

## Phase 5 — Order REST API

Wires the engine to HTTP per §7.

| # | Task | Acceptance |
|---|---|---|
| 5.1 | `OrderController.submit(@Valid SubmitOrderRequest)` → calls `MatchingEngineService.submit`. Validation: `clientOrderId` non-blank, `symbol` known, `side`/`type` enums, `qty>=1`, price required iff LIMIT. | 201 on happy path, 400 on validation failures. |
| 5.2 | Idempotent retry test: submit the same body twice, get the same `orderId` and `status`, no second order in DB. | Integration test green. |
| 5.3 | `OrderController.cancel(@PathVariable id)` → engine cancel; ownership enforced. | 200/403/404/409 covered by integration test. |
| 5.4 | `OrderController.mine()` returns the authenticated user's orders (uses `orders_user_idx`). | Returns the §6.4 fields; sorted newest first. |

---

## Phase 6 — Read API & book aggregation

| # | Task | Acceptance |
|---|---|---|
| 6.1 | `BookQueryService.snapshot(symbol)` — top-5 bid + top-5 ask price levels from `SELECT price, SUM(qty - filled_qty), COUNT(DISTINCT user_id) FROM orders WHERE symbol=? AND status IN ('OPEN','PARTIAL') GROUP BY price ORDER BY price [ASC|DESC] LIMIT 5`, plus `SELECT price FROM trades WHERE symbol=? ORDER BY executed_at DESC LIMIT 1` for `last`. Includes the current outbox cursor (`SELECT max(id) FROM market_event_outbox`). | Snapshot for AAPL after the seed scenario matches §5.4 exactly. |
| 6.2 | `BookController.snapshot(symbol)` → `BookQueryService`. | `GET /api/book/AAPL` returns the §5.4 shape. |
| 6.3 | `BookController.totals(symbol)` → `{ demand, supply }`. | Numbers match §6.2 Market Overview row for the symbol. |
| 6.4 | `SymbolController.list()` → all symbols with refPrice. | Five rows. |
| 6.5 | `FillsController.mine()` returns trades joined to symbol, with `counterparty` userId computed as `CASE side WHEN 'BUY' THEN sell_user_id ELSE buy_user_id END`. | Matches §6.4 "My Fills" columns. |

---

## Phase 7 — Outbox listener & cross-node fan-out

Where the multi-instance NFR comes alive.

| # | Task | Acceptance |
|---|---|---|
| 7.1 | `OutboxWriter` — small JdbcTemplate helper called from inside the match transaction with `(channel, payload)`. | Used by Phase 3 already; this task is to formalize it as a reusable component. |
| 7.2 | `OutboxListener` — `@Component` with `@PostConstruct` that opens a dedicated, unpooled `Connection` (via `DataSource.getConnection()` then `unwrap(PgConnection)`), runs `LISTEN market_event`, and starts a daemon thread polling `getNotifications(timeoutMs)`. | Two backend instances running; an INSERT into the outbox on either fires `pg_notify`; both nodes log "received notification id=…". |
| 7.3 | `OutboxFanout` — for each notification, fetch the row, look up the channel (`book:`, `trades:`, `orders:`), forward to local `WsBroker`. | Unit test stubs the JDBC + broker; verifies routing. |
| 7.4 | `OutboxJanitor` — `@Scheduled(fixedDelay=60s)` deletes rows older than `OUTBOX_RETENTION_MINUTES` (default 5). | Integration test: insert old row → run janitor → row gone. |
| 7.5 | Graceful shutdown: `@PreDestroy` on `OutboxListener` closes the connection and joins the listener thread within 5s. | Spring context shutdown emits no warnings. |

---

## Phase 8 — WebSocket layer

Per §4.8 of the blueprint.

| # | Task | Acceptance |
|---|---|---|
| 8.1 | Spring WebSocket config registers two raw `WebSocketHandler`s at `/ws/book/{symbol}` and `/ws/orders/mine`. JWT validation in the handshake interceptor (rejects 401 before upgrade). | A WS client without a token fails to upgrade. |
| 8.2 | `WsBroker` — in-memory `Map<channel, Set<WebSocketSession>>` with thread-safe register/unregister/broadcast. | Concurrent register/unregister test passes (no `ConcurrentModificationException`). |
| 8.3 | `BookWsHandler` — on connect, send `{ type:'snapshot', cursor, payload: BookSnapshot }`, register session under `book:{symbol}` and `trades:{symbol}`. On disconnect, unregister. | Connect with `wscat`; receive snapshot frame within 200ms. |
| 8.4 | `OrdersWsHandler` — on connect, send `{ type:'snapshot', cursor, payload: MyOrders }`, register under `orders:{userId}`. | Same as 8.3. |
| 8.5 | Snapshot-cursor race fix: the snapshot read and the `SELECT max(id)` happen in the *same* read-only transaction. Forwarded events whose `id <= snapshotCursor` are dropped on the client side; server forwards everything but tags each with its outbox `id`. | Test: rapid book change between snapshot read and first event → client never sees a duplicate. |
| 8.6 | Two-instance E2E: bring up two backend processes on different ports against the same DB. WS-connect to instance A; submit an order via instance B; assert the WS client receives the trade frame within 1s. | Test green (Awaitility-based). |

---

## Phase 9 — Observability polish

Per §4.10 of the blueprint.

| # | Task | Acceptance |
|---|---|---|
| 9.1 | One JSON log line per `ORDER_ACCEPTED` / `ORDER_REJECTED` / `ORDER_FILLED` / `ORDER_CANCELLED` / `TRADE_EXECUTED`. Common fields: `event`, `instance`, `requestId`, `userId`, `symbol`, plus event-specific. | Grep-test: `grep '"event":"ORDER_ACCEPTED"'` on a captured run shows one line per accepted order. |
| 9.2 | Counters: `orders_received_total{type,symbol}`, `orders_rejected_total{reason}`, `trades_executed_total{symbol}`. Timer: `match_duration_seconds{symbol}`. | `/actuator/prometheus` exposes the metrics with non-zero values after a load run. |
| 9.3 | `requestId` MDC filter: generate UUID per request, propagate into engine, include in trade event payloads. | Trace a single submit end-to-end via a single `requestId` grep. |

---

## Phase 10 — Hardening

Things that are easy to forget but the deep-dive Q&A will catch.

| # | Task | Acceptance |
|---|---|---|
| 10.1 | Self-trade allowed (no prevention) — documented in code with a `// design choice — see architecture §9.7` comment? **No** — comments rot; instead, capture in the README's trade-offs section. | README updated when written. |
| 10.2 | `BigDecimal` everywhere on the wire and in service layer; Jackson configured with `WRITE_BIGDECIMAL_AS_PLAIN`. | Round-trip test: post `180.5000`, read back `180.5000` (not scientific notation). |
| 10.3 | Validate that `created_at` (used for time priority) is set to `now()` by Postgres and *not* by the JVM clock — multi-instance clock skew should not affect priority. | Integration test: submit an order with the JVM clock skewed via mock; verify `created_at` came from the DB. |
| 10.4 | Boundary tests: `qty=0` rejected (400); `qty=Long.MAX_VALUE` rejected (400, sane upper bound, e.g. 10^9); MARKET with `price` set is silently ignored OR rejected — pick one and document. **Recommendation:** reject (400) — strictness > permissiveness. | Test green. |
| 10.5 | Listener thread death is detectable: `OutboxListener` exposes a `@HealthIndicator` returning `DOWN` if the thread isn't alive. | Kill the thread in a test; `/actuator/health` returns `DOWN`. |

---

## Risks & open items

- **Hash collision on `pg_advisory_xact_lock(hashtext(symbol))`** — documented in architecture §4.3. Mitigation path (per-symbol numeric lock id) noted, not implemented.
- **Outbox under load** — at the §5.5 stress (≈4 events per match × ~20 matches/s = 80 inserts/s), trivial. If we ever batched events into a single row, the trigger semantics change — keep one-row-per-event.
- **Long match chains under MARKET orders** — bounded by visible book depth; in pathological cases the advisory lock holds longer. Documented; no test forces it.
- **Java callback for password hashing** vs static hashes in V3 migration: prefer the callback so plaintext never lives in the repo's migration files.

## Suggested execution order (top-to-bottom)

Phases 0 → 1 → 2 → **3 (scenario test green = first milestone)** → 4 → 5 → 6 → 7 → 8 → 9 → 10. Parallelisation possible from Phase 5 onward (frontend can start as soon as 5+6 stabilise the API contract).
