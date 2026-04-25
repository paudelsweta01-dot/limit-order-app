# Project Instructions

These standards are binding for all contributions, whether human- or AI-generated. Deviations require an explicit justification in the PR description. The architecture blueprint at `docs/architecture/architecture.md` is the design source of truth; these instructions encode its conventions for day-to-day work.

---

## 1. Project Overview

A simplified, stock-market-style **limit-order matching platform**. Authenticated users place LIMIT and MARKET orders for a fixed list of symbols; a continuously-running matching engine pairs bids and asks, emits trades, and streams order-book state to every connected client in near real-time. The backend runs as ≥ 2 stateless instances behind nginx, sharing state through Postgres and fanning out updates via the transactional outbox + `LISTEN/NOTIFY` pattern.

---

## 2. Tech Stack (locked in)

| Layer        | Technology                                                  |
| ------------ | ----------------------------------------------------------- |
| Backend      | Java 17, Spring Boot 3.x                                    |
| Build        | **Maven** (no Gradle)                                       |
| Persistence  | **Postgres 16 only** (no Redis); Flyway migrations; HikariCP |
| Auth         | BCrypt-hashed seed users → JWT (HS256, 12h)                 |
| Real-time    | Raw WebSocket (no STOMP); `LISTEN/NOTIFY` between nodes     |
| Frontend     | Angular (latest LTS), Signals, Standalone components        |
| Language (FE)| TypeScript (strict mode)                                    |
| Testing      | JUnit 5 + Mockito + AssertJ + Testcontainers (BE); Jasmine + Karma (FE) |
| LB / Deploy  | nginx round-robin (no sticky sessions); docker-compose      |

No alternatives (NgRx, Lombok-free POJOs, Redis, STOMP, Gradle) without a written exception.

---

## 3. Java Standards

- **Lombok mandatory.** Use `@Getter`, `@RequiredArgsConstructor`, `@Slf4j`, `@Builder`. Do not hand-write what Lombok generates.
- **DTOs are `record` types.** JPA entities remain classes.
- **Functional streams** over imperative loops for collection transforms; avoid streams for trivial single-element work.
- **Constructor-based DI only** — no field/setter `@Autowired`. Pair with `@RequiredArgsConstructor` for `private final` deps.
- **Immutability by default.** Fields `private final` unless mutation is justified.
- **Domain-specific exceptions only**, mapped to HTTP via a single `@RestControllerAdvice`. Stack traces never appear in JSON responses.
- **No `null` returns** from public APIs — use `Optional<T>` or empty collections.
- **IDs are UUIDv7** (time-ordered) generated server-side.
- **Money / prices: `BigDecimal` with `RoundingMode.HALF_EVEN`, scale 4.** Never `double`/`float`. Serialize prices as JSON strings (Jackson `WRITE_BIGDECIMAL_AS_PLAIN`).
- **Quantities: `long` / `BIGINT`.** No fractional shares.
- **Timestamps: `Instant` ↔ `TIMESTAMPTZ`.** Server is the time authority; `created_at` defaults to Postgres `now()`.

---

## 4. Angular Standards

- **Signals for state management.** `signal()`, `computed()`, `effect()` for component and shared state. No `BehaviorSubject` for app state. RxJS is reserved for HTTP and WS streams; convert at the boundary with `toSignal()`.
- **Standalone components only.** No `NgModule` for new code.
- **`ChangeDetectionStrategy.OnPush`** on every component.
- **New control flow syntax (`@if`, `@for`, `@switch`)** in all templates. `@for` requires `track`. No `*ngIf` / `*ngFor` / `*ngSwitch`.
- **`inject()`** preferred over constructor injection inside components/services.
- **Strict TypeScript:** `strict: true`, no `any`, no non-null `!` without justification.
- **Reactive forms** for both Login and Place Order (per architecture §5.5). Place Order disables the price input when `type === 'MARKET'` and generates a fresh `clientOrderId = uuidv7()` per submit attempt; the same id is reused on transient retry so server-side idempotency holds.
- **Fills arrive via WebSocket, not the POST response.** Submit shows a transient toast; the order's terminal state lands through the `orders:{userId}` channel.

---

## 5. Architecture Rules

### 5.1 Component-scoped file placement

All files related to a specific component must live inside that component's folder. Backend code under `backend/`, frontend under `frontend/`, simulator under `simulator/`, infra under `infra/`. Do not scatter component-specific configs, scripts, or assets at the project root or in unrelated folders.

```
limit-order-app/
├── backend/                 # Java / Spring Boot, Maven, Flyway migrations, BE tests
├── frontend/                # Angular / TypeScript, angular.json, FE tests
├── simulator/               # Standalone Spring Boot CLI; talks HTTP/WS only
├── infra/                   # docker-compose.yml, nginx.conf, postgres/init.sql
├── docs/                    # Requirements, architecture, ai-chat transcripts
├── .claude/                 # Claude-specific instructions and config
├── .env.example
└── README.md
```

### 5.2 Backend package layout (single Maven module, `com.sweta.limitorder`)

Concern-based packages — **not** feature-based. Match the architecture exactly:

```
com.sweta.limitorder
├── api              # REST controllers, request/response DTO records, exception handler
├── ws               # WebSocket handlers, subscription registry, payload mappers
├── auth             # Login controller, JWT signer/verifier, security filter
├── matching         # MatchingEngineService — transactional, advisory-locked core
├── orders           # OrderService — submit/cancel facade in front of matching
├── book             # BookQueryService — top-N levels + totals
├── outbox           # OutboxWriter (in-tx), OutboxListener (LISTEN), OutboxJanitor (@Scheduled)
├── persistence      # JPA entities, repositories, custom JdbcTemplate (advisory locks)
├── config           # HikariCP, Spring Security, WebSocket, Jackson
└── observability    # Micrometer metrics, MDC enrichment, structured logging
```

**Layer rules:**

- Controllers (`api`) depend on services (`orders`, `book`, `matching`); services depend on `persistence`. Never the reverse.
- Controllers contain validation, DTO mapping, and delegation only — no business logic.
- `@Transactional` belongs on service methods, never controllers or repositories. The matching transaction boundary is `MatchingEngineService.submit()`.
- The `outbox` package is the only writer to `market_event_outbox`, and writes happen in the same transaction as the state change that produced them.

### 5.3 Frontend folder structure (`frontend/src/app/`)

```
app/
├── app.routes.ts
├── core/             # auth.service, auth.guard, http.interceptor, api.service, ws.service, models
├── auth/             # login.page (§6.1)
├── market/           # overview.page (§6.2)
├── symbol/           # symbol-detail.page, order-book.component, place-order.form (§6.3)
├── me/               # my-orders.page, my-fills.page (§6.4)
└── shared/           # tiny UI atoms
```

Cross-feature imports go through `core/` or `shared/` only — feature folders do not import from sibling features. The WS multiplexer in `core/ws.service.ts` is the single owner of WebSocket connections; on reconnect it discards in-memory stream state and consumes a fresh snapshot.

---

## 6. Persistence & Concurrency

- **Flyway migrations are immutable once shipped.** Never edit `V*__*.sql`; add a new `V{n+1}__*.sql` instead.
- **Schema invariants are DB-enforced.** Keep CHECK constraints (`filled_qty >= 0 AND filled_qty <= quantity`, LIMIT-has-price / MARKET-no-price, `UNIQUE(user_id, client_order_id)`) intact — they are the last line of defence against bugs.
- **Per-symbol Postgres advisory lock** (`pg_advisory_xact_lock(hashtext(:symbol))`) is the serialization point for all state-changing flows on a symbol (submit, match, cancel). Do not introduce alternative locking schemes without updating the architecture doc.
- **Synchronous matching in the request path.** No queue/worker indirection.
- **Transactional outbox + `LISTEN/NOTIFY`** is the only cross-node fan-out mechanism. Every state change inserts outbox rows in the same transaction; the dedicated `LISTEN` connection lives outside HikariCP.
- **Idempotency** is `UNIQUE(user_id, client_order_id)` plus a SELECT-then-INSERT inside the advisory lock, with a `DuplicateKeyException` fallback returning the existing order.
- **Connection pools per node:** Hikari max 20 + 1 dedicated unpooled `LISTEN` connection. Don't grow these without checking total against Postgres `max_connections`.

---

## 7. Logging & Observability

- **Logback JSON encoder.** One structured `INFO` line per order-lifecycle event.
- **Standard event names** (do not invent new ones): `ORDER_ACCEPTED`, `ORDER_REJECTED`, `ORDER_FILLED`, `ORDER_CANCELLED`, `TRADE_EXECUTED`.
- **MDC fields** propagated on every log line: `requestId`, `userId`, `symbol`, `instance`. Passwords and JWT tokens are never logged.
- **Metrics via Micrometer + `/actuator/prometheus`.** Counters: `orders_received_total{type,symbol}`, `orders_rejected_total{reason}`, `trades_executed_total{symbol}`, `outbox_published_total`. Timer: `match_duration_seconds{symbol}`.
- **Health:** `/actuator/health` includes the NOTIFY listener thread.

---

## 8. Testing & Validation

- **Backend:** JUnit 5 + Mockito + AssertJ + **Testcontainers Postgres** for matching, outbox, and concurrency tests. Slice tests (`@WebMvcTest`, `@DataJpaTest`) where they suffice; `@SpringBootTest` only for full-stack integration.
- **Required test coverage:** the §5.3 scenario CSV must replay end-to-end and produce exactly the §5.4 snapshot. The 100-thread × 1000-order concurrency soak must hold the consistency invariants (`Σ filled_qty BUY == Σ filled_qty SELL`, no `filled_qty > quantity`, every trade references valid opposite-side orders).
- **Frontend:** Jasmine + Karma. Test signals via `TestBed` and signal assertions; mock HTTP via `HttpTestingController`.
- **Tests written alongside implementation**, not after. A PR that adds a service without its test will be sent back.
- **Simulator** (in `simulator/`) talks HTTP/WS only — never imports backend code, never reads the database directly. CI gates on its consistency-check assertions.
- **Naming:** `methodName_givenCondition_expectedOutcome` (Java), `should ... when ...` (TypeScript).

---

## 9. Workflow

- **Plan Mode (Shift+Tab) is required when a task touches more than three files.** Enter plan mode, surface the plan for approval, then implement.
- **Always propose a plan before implementation** for non-trivial changes — even single-file changes that introduce new patterns, dependencies, or schema changes.
- **Architecture changes go through `docs/architecture/architecture.md` first.** Code that contradicts the blueprint requires updating the blueprint in the same PR.
- **One task, one plan.** Do not bundle unrelated changes; refactors and features go in separate PRs.
- **Commit messages:** imperative mood, scoped (`feat(matching): ...`, `fix(outbox): ...`, `test(book): ...`).
- **No silent behaviour changes.** If implementation diverges from the approved plan, stop and re-confirm.
- **Read before writing.** When editing existing code, read the surrounding file and at least one consumer before changing public signatures.
