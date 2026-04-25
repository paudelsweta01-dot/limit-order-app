# Limit Order Matching App — Architecture Blueprint

**Status:** Draft v1 — pre-implementation. Source of truth for the design until code lands; the README will distil the user-facing parts of this doc once the system is built.

**Companion to:** `docs/requirnments/limit-order-requirements.md`. References to that doc are written as `§n.m`.

---

## 1. System overview

A simplified, stock-market-style **limit-order matching platform**. Multiple authenticated users place LIMIT and MARKET orders for a fixed list of symbols. A continuously-running matching engine pairs bids and asks, emits trades, and streams the order-book state to every connected client in near real-time.

The system is built to satisfy three deliberately uncomfortable constraints together:

1. **Multi-user** — many concurrent submitters.
2. **Multi-threaded** — concurrent request handlers and matching, no corruption.
3. **Multi-instance** — ≥ 2 stateless backend nodes behind a load balancer, sharing state through Postgres.

The architecture is shaped around the #1 grading criterion (§3): **correctness under concurrency**. Every design choice below is made to either (a) maintain a single, authoritative serialization point per symbol, or (b) keep the application nodes stateless so the system survives node loss without bookkeeping.

```
                            ┌──────────────────────┐
   browser  (Angular SPA)──►│   nginx reverse proxy │──► backend-1 ─┐
   browser  (Angular SPA)──►│   round-robin LB      │──► backend-2 ─┤
                            └──────────────────────┘                ▼
                                                              ┌─────────┐
                                                              │ Postgres │
                                                              └─────────┘
                                                                ▲     ▲
                                            outbox + LISTEN/NOTIFY    │
                                            ◄────────────────────────►│
                                                                      │
                                                       simulator ─────┘
                                                       (separate JVM,
                                                        hits nginx VIP)
```

---

## 2. Technology choices (locked in)

| Concern | Choice | Why |
|---|---|---|
| Editor / AI | VS Code + Copilot Chat | Required by §1. Transcripts exported to `docs/ai-chat/`. |
| Backend | Java 17, Spring Boot 3.x | Java 17 is the LTS already on the dev machine; clears the §1 "Java 17+" floor. |
| Build | Maven | User decision. |
| Persistence | Postgres 16 (only) | User decision. Also serves as the inter-node bus via `LISTEN/NOTIFY` — no Redis. |
| Migrations | Flyway | Schema is enforced from version 1 and can be reasoned about chronologically. |
| Connection pool | HikariCP | Default in Spring Boot. Plus one **dedicated, unpooled** JDBC connection per node for `LISTEN`. |
| Identifiers | UUIDv7 (time-ordered) | No shared sequence required across nodes; lexicographically sortable. |
| Real-time transport | WebSocket (raw, not STOMP) | Two channel patterns (`/ws/book/{symbol}`, `/ws/orders/mine`); STOMP is over-engineered for that. |
| Frontend | Angular latest LTS, Standalone components, Signals | User decision. Signals + a service layer is enough state — no NgRx. |
| Auth | Username + password (BCrypt-hashed seed users) → JWT (HS256) | §2.4 keeps it deliberately simple. Stateless tokens enable round-robin LB. |
| Simulator | Standalone Spring Boot CLI (`CommandLineRunner`) | §1 mandates a separate process; reusing the backend's HTTP client is ergonomic. |
| LB | nginx | Round-robin for HTTP and WS upgrade. **No sticky sessions** — design is stateless across nodes. |
| Containerisation | docker-compose | §8.2 deliverable. Postgres + backend × 2 + nginx + frontend (nginx-served). |

**Java 17 specifically**: the backend's hot path is DB-bound and synchronous, so virtual threads (a Java 21 feature) aren't a load-bearing choice; staying on the 17 LTS keeps the toolchain on what's already installed and clears the spec floor.

---

## 3. Domain model

```
User ─────────────────────────────┐
                                  │
Symbol ◄───── Order ◄──── Trade ──┴──► User (counterparty)
                │              │
                └─► OutboxEvent (book / orders / trades fan-out)
```

| Entity | Identity | Lifecycle |
|---|---|---|
| `User` | UUID `userId` | Pre-seeded (Alice, Bob, Charlie, Diana). |
| `Symbol` | string PK (`AAPL`...) | Pre-seeded from §5.1. Includes `refPrice` for the simulator. |
| `Order` | UUIDv7 `orderId` | Created → OPEN → optional PARTIAL → FILLED / CANCELLED. Terminal states are FILLED and CANCELLED. |
| `Trade` | UUIDv7 `tradeId` | Immutable once written. References both order ids and both user ids. |
| `OutboxEvent` | bigserial | Emitted in the same tx as every state change; consumed by the WS publisher; janitor-pruned after a few minutes. |

**Order status state machine**

```
            ┌────────────────► CANCELLED   (user cancel, or MARKET reject)
            │
   created ─┼──► OPEN ──► PARTIAL ──► FILLED
            │     │          │
            │     └────► FILLED  (one-shot full fill, no PARTIAL pause)
            │
            └─► CANCELLED (MARKET with no/partial fill — never PARTIAL)
```

Subtle case: a MARKET order that fills 60 of 100 lots and then runs out of opposing liquidity ends up `status=CANCELLED`, `reject_reason='INSUFFICIENT_LIQUIDITY'`, `filled_qty=60`. The 60 filled lots are real trades that stand; the order itself never *rests* with status PARTIAL because MARKET never rests on the book by definition. This is by spec (§2.1).

---

## 4. Backend

### 4.1 Module / package layout

Single Maven module — multi-module ceremony isn't worth it at this size. Logical packages inside `com.example.lob`:

```
com.example.lob
 ├── api              REST controllers, request/response DTOs, exception handlers
 ├── ws               WebSocket handlers, subscription registry, payload mappers
 ├── auth             Login controller, JWT signer/verifier, security filter
 ├── matching         MatchingEngineService — the core, transactional, advisory-locked
 ├── orders           OrderService — submit/cancel facade in front of matching
 ├── book             BookQueryService — top-N levels + totals
 ├── outbox           OutboxWriter (in-tx) + OutboxListener (LISTEN/NOTIFY) + OutboxJanitor (@Scheduled)
 ├── persistence      JPA entities, repositories, custom JdbcTemplate methods for advisory lock + index hints
 ├── config           HikariCP, Spring Security, WebSocket config, Jackson, ObjectMapper
 └── observability    Micrometer metrics, MDC enrichment, structured logging
```

Why single module: the boundary between "matching core" and "everything else" is a package boundary; promoting it to a Maven module buys nothing but build-time ceremony. If we ever extract the matching engine into a hot-replaceable artifact, that's the moment to split.

### 4.2 Request flow — `POST /api/orders`

```
HTTP POST  ─►  JwtAuthFilter  ─►  OrderController
                                       │
                                       ▼
                                OrderService.submit(req, userId)
                                       │
                                       ▼  @Transactional(REQUIRED)
                                MatchingEngineService.submit(...)
                                       │
                                       ├── pg_advisory_xact_lock(hashtext(symbol))
                                       ├── idempotency check (user_id, client_order_id)
                                       ├── INSERT order
                                       ├── match loop (SELECT … FOR UPDATE, UPDATE, INSERT trade)
                                       ├── INSERT outbox rows  (book + orders + trades)
                                       └── COMMIT  (lock auto-released; trigger fires NOTIFY)
                                       │
                                       ▼
                                  201 { orderId, status }
```

The transaction boundary **is** the controller-to-service call. A single `@Transactional` annotation at `MatchingEngineService.submit()` covers the lock acquisition, the match loop, the trade inserts, and the outbox writes — all-or-nothing.

### 4.3 Concurrency strategy (the heart of the system)

**Per-symbol Postgres advisory lock + synchronous matching inside the request handler.**

Three properties together give us correctness:

1. **Mutual exclusion per symbol.** Every match transaction begins with
   ```sql
   SELECT pg_advisory_xact_lock(hashtext(:symbol));
   ```
   Two transactions touching the same symbol from the same or different nodes serialize; transactions on different symbols proceed in parallel. The lock is automatically released when the transaction commits or rolls back — no manual unlock, no orphaned locks on crash.

2. **Atomicity of the match.** Insert-order, match-loop, trade-emit, status-update, and outbox-write are one transaction. A node crash mid-match rolls back cleanly; the order never partially exists.

3. **Database-enforced invariants.** The `orders` table has
   ```sql
   CHECK (filled_qty >= 0 AND filled_qty <= quantity)
   ```
   so even a buggy code path can't produce the failure modes called out in §3 ("no negative `filledQty`, no trade for more than the resting quantity").

**Why advisory locks instead of `SELECT FOR UPDATE` on a marker row?** Same correctness, lower cost — advisory locks are pure in-memory inside Postgres, no row I/O, no MVCC bloat, no autovacuum implications. They are the idiomatic Postgres tool for this exact pattern.

**Hash collisions.** `hashtext('AAPL')` and `hashtext('MSFT')` could in principle collide and serialize unrelated symbols. Probability is astronomically low for a 5-symbol universe; I'll document the risk and live with it. A bullet-proof alternative is a `symbol_lock_id BIGINT` column populated by a sequence at seed time — easy to add later if needed.

**Match contention worst case.** If a single MARKET order has to walk five resting asks to fill, the advisory lock is held across five `SELECT … FOR UPDATE; UPDATE; INSERT trade;` round trips. At the §5.5 stress profile (≈ 17 orders/sec/symbol, modest depth), that's well inside latency budgets. If a symbol became truly hot, we'd shard further — documented as future work.

### 4.4 Match algorithm (precise)

```
BEGIN;
SELECT pg_advisory_xact_lock(hashtext(:symbol));

-- Idempotency
SELECT order_id, status FROM orders
 WHERE user_id = :userId AND client_order_id = :clientOrderId;
IF found:
    COMMIT and return that order.

-- Insert with status = OPEN initially
INSERT INTO orders (...) VALUES (...) RETURNING order_id;

-- Match loop
WHILE incoming.remaining > 0 LOOP

    -- "best" opposite resting order
    SELECT order_id, user_id, price, quantity, filled_qty
      FROM orders
     WHERE symbol = :symbol
       AND status IN ('OPEN','PARTIAL')
       AND side   = OPPOSITE(:side)
       AND ( :type = 'MARKET'
             OR (:side = 'BUY'  AND price <= :limitPrice)
             OR (:side = 'SELL' AND price >= :limitPrice) )
     ORDER BY (CASE WHEN side = 'SELL' THEN price END) ASC,    -- best ask: low price first
              (CASE WHEN side = 'BUY'  THEN price END) DESC,   -- best bid: high price first
              created_at ASC                                    -- time priority
     LIMIT 1
       FOR UPDATE;

    EXIT WHEN no row returned;

    trade_qty   = LEAST(incoming.remaining, resting.remaining);
    trade_price = resting.price;       -- §2.2: trade price = resting order's price

    UPDATE orders SET filled_qty = filled_qty + trade_qty,
                      status = CASE WHEN filled_qty + trade_qty = quantity
                                    THEN 'FILLED' ELSE 'PARTIAL' END,
                      updated_at = now()
     WHERE order_id IN (incoming, resting);

    INSERT INTO trades (...) VALUES (...);

    -- Outbox events (one transaction → trigger fires one NOTIFY at commit)
    INSERT INTO market_event_outbox (channel, payload) VALUES
        ('book:'||:symbol,      json_build_object(...)),
        ('trades:'||:symbol,    json_build_object(...)),
        ('orders:'||incoming.user_id, json_build_object(...)),
        ('orders:'||resting.user_id,  json_build_object(...));

END LOOP;

-- Terminal status for MARKET with leftovers
IF :type = 'MARKET' AND incoming.remaining > 0:
    UPDATE orders
       SET status = 'CANCELLED',
           reject_reason = 'INSUFFICIENT_LIQUIDITY',
           updated_at = now()
     WHERE order_id = incoming.order_id;

COMMIT;
```

**Match condition:** `bid.price >= ask.price`. The seed scenario in §5.3 (c005 buys at 180.50 against an ask at 180.50) only crosses under `>=`; the `>` in §2.2 prose is a typo, confirmed by §5.4.

### 4.5 Cancel flow

```
BEGIN;
SELECT pg_advisory_xact_lock(hashtext(symbol));   -- same lock as match
SELECT * FROM orders WHERE order_id = :id AND user_id = :userId FOR UPDATE;
ASSERT status IN ('OPEN','PARTIAL') AND user_id = :requesterId;
UPDATE orders SET status='CANCELLED', updated_at=now() WHERE order_id = :id;
INSERT INTO market_event_outbox (channel, payload) VALUES
    ('book:'||symbol,        ...),
    ('orders:'||:userId,     ...);
COMMIT;
```

Ownership is enforced server-side. Cancel takes the same advisory lock as match, so a cancel cannot race a fill in flight: either the fill commits first (cancel sees a different status and 409s) or the cancel commits first (the match never sees the order in its scan).

### 4.6 Idempotency

`UNIQUE(user_id, client_order_id)` is the source of truth.

Inside a per-symbol advisory lock, the SELECT-then-INSERT pattern is naturally race-free across nodes. As a belt-and-braces guard for the (genuinely impossible but cheap-to-handle) two-nodes-with-same-`clientOrderId`-before-lock case, the service catches `DuplicateKeyException` and falls back to "look up the existing order and return it." Same response either way; the client sees retries as no-ops.

### 4.7 Cross-node real-time fan-out

Pattern: **transactional outbox + Postgres `LISTEN/NOTIFY`**.

1. The match transaction inserts rows into `market_event_outbox`. A trigger on that table fires
   ```sql
   PERFORM pg_notify('market_event', NEW.id::text);
   ```
   The NOTIFY is buffered until commit, so subscribers only see events for *committed* transactions.
2. **Every backend node holds one dedicated `LISTEN market_event` JDBC connection** (outside HikariCP, because the pool rotates connections and `LISTEN` is per-connection). A daemon thread blocks on that connection's `getNotifications()`.
3. On notification, the node fetches the row by id, looks up the channel (`book:AAPL`, `orders:u3`, `trades:TSLA`), and pushes the payload to all WebSocket sessions registered for that channel **on this node**.
4. Because *both* nodes receive every NOTIFY, a trade executed on node-1 is delivered to a WS client connected to node-2 within the LISTEN/NOTIFY round-trip — consistently single-digit milliseconds, well inside the §3 1-second budget.

Outbox pruning: a `@Scheduled` `OutboxJanitor` deletes rows older than 5 minutes. It exists only to keep the table small; correctness doesn't depend on it.

### 4.8 WebSocket layer

Two endpoints (§7):

| Endpoint | Channel name | Payload |
|---|---|---|
| `WS /ws/book/{symbol}` | `book:{symbol}` and `trades:{symbol}` | Top-5 bids/asks + last trade. |
| `WS /ws/orders/mine` | `orders:{userId}` | Order status changes for the authenticated user. |

Connection lifecycle:

1. Client opens WS with `Authorization: Bearer <jwt>` (handled in the upgrade handshake).
2. Server validates JWT, registers the session under the requested channel, and **sends a snapshot first** — top-5 levels via the same query the REST endpoint uses, plus the last trade. The snapshot includes a sequence cursor (`max(id)` from `market_event_outbox`).
3. Subsequent NOTIFY-driven events carry their own outbox `id`. The client discards events whose `id <= snapshot.cursor`, eliminating the snapshot/event race window.

On disconnect, the node drops the session and cleans the registry. On reconnect, the client gets a fresh snapshot from whichever node nginx picks. **No sticky sessions.**

### 4.9 Authentication

- **Login**: `POST /api/auth/login { username, password }` → 200 `{ token, userId, name }`.
- **Hashing**: BCrypt with cost 10. Seed users (§5.2) get hashed at first migration via a Flyway Java callback or a one-time admin endpoint guarded by an env-var bootstrap key.
- **JWT**: HS256, 12-hour lifetime, claims `{ sub: userId, name, iat, exp }`, signed with `JWT_SIGNING_SECRET` from env.
- **Filter**: `JwtAuthFilter` extracts the bearer token, sets `SecurityContext`, and exposes `userId` on `Authentication.principal`.
- **WS handshake**: same filter on the upgrade request; the JWT travels via the `Authorization` header (works through nginx) or a query string fallback for browsers that can't set headers on `WebSocket()`.

### 4.10 Observability

| Concern | Mechanism |
|---|---|
| Health | `/actuator/health` (Spring Actuator) — DB, NOTIFY listener thread, disk. |
| Structured logging | Logback JSON encoder. Every order event is a single log line: `event=ORDER_ACCEPTED orderId=… userId=… symbol=… side=… type=… price=… qty=…`. Same for `ORDER_REJECTED`, `ORDER_FILLED`, `ORDER_CANCELLED`, `TRADE_EXECUTED`. |
| MDC enrichment | `requestId`, `userId`, `symbol` propagated on every log line. |
| Metrics | Micrometer + `/actuator/prometheus`. Counters: `orders_received_total{type,symbol}`, `orders_rejected_total{reason}`, `trades_executed_total{symbol}`, `outbox_published_total`. Timers: `match_duration_seconds{symbol}`. |

### 4.11 Error model

| Class | HTTP | Client behaviour |
|---|---|---|
| `400` validation | malformed body, MARKET with price, LIMIT without price, qty < 1 | Show inline form error. |
| `401` auth | missing/invalid JWT | Bounce to login. |
| `403` ownership | cancel of someone else's order | "You can only cancel your own orders." |
| `404` not found | unknown symbol or orderId | "No such order/symbol." |
| `409` conflict | cancel on already-FILLED/CANCELLED order | Show current status. |
| `422` business | duplicate clientOrderId returns existing order with `idempotent=true` | UI ignores second response. |
| `5xx` | unhandled | Generic banner; logs carry the trace. |

A single `@RestControllerAdvice` wraps these. Stack traces never leak in JSON responses.

### 4.12 Tests

| Layer | What | How |
|---|---|---|
| Unit (matching) | price-time priority, partial fill, MARKET insufficient liquidity, MARKET full fill with leftover bid book, cancel during partial | Testcontainers Postgres, JUnit 5. |
| Concurrency | 100 threads × 1000 orders → assert `Σ filled_qty BUY == Σ filled_qty SELL`, no `filled_qty > quantity`, every trade references valid opposite-side orders. | Same Testcontainers, threads driving the service directly. |
| Scenario | Run `docs/requirnments/seed.csv` (§5.3) and assert §5.4 snapshot exactly. | Bootstrapped from a Spring Boot test slice. |
| Auth | login, JWT validation, WS upgrade with bad token | `MockMvc` + a small WebSocketClient. |

Connection-pool sizing under tests: limit `spring.datasource.hikari.maximum-pool-size=20` so the concurrency tests actually contend.

---

## 5. Frontend

### 5.1 Workspace layout

Single Angular workspace, standalone components (no NgModules), one `app/` tree:

```
frontend/
 ├── angular.json
 ├── package.json
 └── src/
     ├── main.ts
     ├── app/
     │   ├── app.routes.ts
     │   ├── core/
     │   │   ├── auth.service.ts          login, JWT storage, current user signal
     │   │   ├── auth.guard.ts            route guard
     │   │   ├── http.interceptor.ts      attaches Authorization, handles 401
     │   │   ├── api.service.ts           typed REST methods
     │   │   ├── ws.service.ts            single WS multiplexer (book, orders)
     │   │   └── models.ts                shared types
     │   ├── auth/
     │   │   └── login.page.ts            §6.1
     │   ├── market/
     │   │   └── overview.page.ts         §6.2
     │   ├── symbol/
     │   │   ├── symbol-detail.page.ts    §6.3
     │   │   ├── order-book.component.ts
     │   │   └── place-order.form.ts
     │   ├── me/
     │   │   ├── my-orders.page.ts        §6.4 (top half)
     │   │   └── my-fills.page.ts         §6.4 (bottom half)
     │   └── shared/                      tiny UI atoms
     └── styles.css
```

### 5.2 Routing

```
/login                     LoginPage         (no guard)
/                          MarketOverview    (auth)
/symbol/:symbol            SymbolDetail      (auth)
/me                        MyAccount         (auth) — orders + fills tabs
**                         redirect /
```

`AuthGuard` blocks unauthenticated access; redirects with a `returnUrl` so post-login lands the user where they meant to go.

### 5.3 State management

**Angular Signals + RxJS-over-WS, no NgRx.** Four pages, three streams, no time-travel debugging needed.

Per stream:
- `BookSignal(symbol)` — driven by `WsService.subscribeBook(symbol)`. Initial value from the snapshot frame; subsequent frames mutate.
- `MyOrdersSignal()` — `WsService.subscribeOrders()`. Same snapshot-then-deltas pattern.
- `MeFillsSignal()` — REST poll on the few flows that don't justify a WS (e.g., the My Fills tab can simply re-fetch when a relevant order event arrives).

### 5.4 WebSocket service

Responsibilities of `core/ws.service.ts`:

- One physical WS connection per channel pattern (`/ws/book/{symbol}` and `/ws/orders/mine`).
- Auto-reconnect with exponential backoff (250 ms → 5 s, jitter).
- On reconnect, **discard the in-memory state for that stream and consume the new snapshot**. This is the simplest correct strategy and matches the backend's snapshot-cursor design (§4.8).
- Translate WS frames into Angular signals (or RxJS Subjects internally that components subscribe to).

### 5.5 Forms

- **Login**: trivial reactive form, two fields. Validation: required.
- **Place Order** (`§6.3`): reactive form, fields wired to side/type radios. Price input is disabled when `type === 'MARKET'`. Qty must be ≥ 1. The form generates `clientOrderId = uuidv7()` per submit attempt and reuses the same id on transient retry, so server-side idempotency does its job.
- Submit shows a transient toast on success/failure; the eventual fill arrives via the WS stream, not the POST response.

### 5.6 UI mapping to spec

| Spec section | Component(s) |
|---|---|
| §6.1 Login | `auth/login.page` |
| §6.2 Market Overview | `market/overview.page` — table reads aggregated `book` events for all symbols. |
| §6.3 Symbol Detail | `symbol/symbol-detail.page` composing `order-book` + `place-order.form`. The order-book component shows top-5 **price levels** with a `Users` count column (matching §6.3 wireframe). |
| §6.4 My Orders & Fills | `me/my-orders.page` + `me/my-fills.page`. `[X]` cancel button visible only on rows with status OPEN/PARTIAL. |

Styling: utility CSS, no UI library. The spec explicitly says "simple, not pretty."

---

## 6. Simulator

A separate Spring Boot application in `simulator/` with its own `pom.xml`. Runs as a fat JAR or `mvn spring-boot:run`. Talks to the system over HTTP/WS only — never imports backend code.

### 6.1 Modes

```
java -jar simulator.jar \
   --mode=scenario \
   --baseUrl=http://localhost:8080 \
   --file=seed.csv \
   --expect=expected.json

java -jar simulator.jar \
   --mode=load \
   --baseUrl=http://localhost:8080 \
   --users=4 --rate=83 --duration=60 \
   --limitPct=70 --buyPct=50

java -jar simulator.jar \
   --mode=multi-instance \
   --nodeA=http://backend-1:8080 \
   --nodeB=http://backend-2:8080 \
   --rate=83 --duration=60

java -jar simulator.jar \
   --mode=consistency-check \
   --baseUrl=http://localhost:8080
```

### 6.2 Module layout

```
simulator/
 └── src/main/java/com/example/lob/sim/
     ├── SimulatorApplication.java       CLI entry, mode dispatch
     ├── client/
     │   ├── LobApiClient.java           login, post order, cancel, get book
     │   └── LobWsClient.java            consumes book+order events for assertions
     ├── modes/
     │   ├── ScenarioMode.java           load CSV, post in order, assert expected.json
     │   ├── LoadMode.java               N virtual users, Poisson-spaced submissions
     │   ├── MultiInstanceMode.java      round-robins between nodeA/nodeB
     │   └── ConsistencyMode.java        runs the §4.3 invariants against current state
     ├── domain/                         lightweight DTOs mirroring the API
     └── report/
         ├── ConsoleReporter.java
         └── JsonReporter.java           writes a run summary for CI
```

### 6.3 Invariants asserted (§4.3)

For each completed run:

1. `Σ filled_qty (BUY orders) == Σ filled_qty (SELL orders)` per symbol.
2. No order has `filled_qty > quantity`.
3. Every trade references two valid orders on opposite sides; each side's user matches the trade's recorded buyer/seller.
4. (Multi-instance only) the book observed via WS on node A == the book observed on node B for every symbol within 1 s of the last submitted order.

Violations exit non-zero with a report; CI gates on this.

### 6.4 Load profile

`--rate=83 --duration=60` matches §5.5: 5000 orders in 60 s. Order generation:

- 4 virtual users, round-robin per submission.
- `symbol` ~ uniform over 5.
- `side` ~ Bernoulli(0.5).
- `type` ~ Bernoulli(0.7 LIMIT / 0.3 MARKET).
- `price` ~ `refPrice * (1 + N(0, 0.01))`, rounded to 4dp.
- `qty` ~ U[10, 500].
- inter-submission gap ~ exponential with mean `1/rate` (Poisson process) — gives realistic burstiness instead of a metronome.

### 6.5 What the simulator does *not* do

- It doesn't read the database directly. All assertions are made through public APIs, exactly the way an external auditor would. This gives the consistency check teeth: if our REST view disagrees with reality, the assertion fails.

---

## 7. Database

### 7.1 Schema (DDL skeleton)

```sql
-- V1__init.sql

CREATE TABLE users (
    user_id          UUID PRIMARY KEY,
    username         TEXT UNIQUE NOT NULL,
    display_name     TEXT NOT NULL,
    password_hash    TEXT NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE symbols (
    symbol           TEXT PRIMARY KEY,
    name             TEXT NOT NULL,
    ref_price        NUMERIC(18,4) NOT NULL
);

CREATE TYPE order_side  AS ENUM ('BUY','SELL');
CREATE TYPE order_type  AS ENUM ('LIMIT','MARKET');
CREATE TYPE order_status AS ENUM ('OPEN','PARTIAL','FILLED','CANCELLED');

CREATE TABLE orders (
    order_id         UUID PRIMARY KEY,
    client_order_id  TEXT NOT NULL,
    user_id          UUID NOT NULL REFERENCES users(user_id),
    symbol           TEXT NOT NULL REFERENCES symbols(symbol),
    side             order_side NOT NULL,
    type             order_type NOT NULL,
    price            NUMERIC(18,4),
    quantity         BIGINT NOT NULL CHECK (quantity > 0),
    filled_qty       BIGINT NOT NULL DEFAULT 0,
    status           order_status NOT NULL,
    reject_reason    TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (filled_qty >= 0 AND filled_qty <= quantity),
    CHECK ( (type = 'LIMIT'  AND price IS NOT NULL)
         OR (type = 'MARKET' AND price IS NULL) ),
    UNIQUE (user_id, client_order_id)
);

-- Hot path: best opposite-side resting order per symbol.
CREATE INDEX orders_book_idx
    ON orders (symbol, side, price, created_at)
    WHERE status IN ('OPEN','PARTIAL');

-- "My orders" page.
CREATE INDEX orders_user_idx ON orders (user_id, created_at DESC);

CREATE TABLE trades (
    trade_id         UUID PRIMARY KEY,
    symbol           TEXT NOT NULL REFERENCES symbols(symbol),
    buy_order_id     UUID NOT NULL REFERENCES orders(order_id),
    sell_order_id    UUID NOT NULL REFERENCES orders(order_id),
    buy_user_id      UUID NOT NULL REFERENCES users(user_id),
    sell_user_id     UUID NOT NULL REFERENCES users(user_id),
    price            NUMERIC(18,4) NOT NULL,
    quantity         BIGINT NOT NULL CHECK (quantity > 0),
    executed_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX trades_symbol_idx     ON trades (symbol, executed_at DESC);
CREATE INDEX trades_buy_user_idx   ON trades (buy_user_id,  executed_at DESC);
CREATE INDEX trades_sell_user_idx  ON trades (sell_user_id, executed_at DESC);

CREATE TABLE market_event_outbox (
    id               BIGSERIAL PRIMARY KEY,
    channel          TEXT NOT NULL,
    payload          JSONB NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX outbox_created_idx ON market_event_outbox (created_at);

-- Trigger: NOTIFY on every commit
CREATE OR REPLACE FUNCTION notify_market_event() RETURNS trigger AS $$
BEGIN
    PERFORM pg_notify('market_event', NEW.id::text);
    RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER market_event_outbox_notify
    AFTER INSERT ON market_event_outbox
    FOR EACH ROW EXECUTE FUNCTION notify_market_event();
```

Subsequent migrations: `V2__seed_symbols.sql`, `V3__seed_users.sql`. Never edit a migration once shipped — Flyway treats them as immutable.

### 7.2 Why these indexes

- `orders_book_idx` is a **partial index** keyed on `(symbol, side, price, created_at)` filtered to `status IN ('OPEN','PARTIAL')`. The match query is exactly an index-only seek to the first qualifying row; Postgres uses the order of index columns to satisfy `ORDER BY price …, created_at ASC LIMIT 1`. Filled/cancelled rows accumulate over time and don't pollute the index, so the matching query stays fast as history grows.
- `orders_user_idx` and the `trades_*_user_idx` cover the user-facing tabs.
- The outbox stays small (janitor) so its single index is enough.

### 7.3 Connection-pool topology per node

| Pool | Size | Purpose |
|---|---|---|
| HikariCP (`spring.datasource`) | 20 max | All request-scoped DB work. |
| Dedicated `LISTEN` connection | 1 | Held outside Hikari for the lifetime of the JVM by `OutboxListener`. |

Two nodes × 21 = 42 connections — well within Postgres' default `max_connections=100`. The simulator adds maybe two more.

### 7.4 Numerics

- Prices: `NUMERIC(18,4)`. Never `double`.
- Quantities: `BIGINT` (whole shares; the spec doesn't mention fractional shares).

### 7.5 Backups and disaster recovery

Out of scope for this exercise. Documented as: "in production we'd run streaming replication + WAL archiving + a daily logical dump." For the exercise the dockerised Postgres is ephemeral.

---

## 8. Infrastructure

### 8.1 Compose topology (`infra/docker-compose.yml`)

```
services:
  postgres:
    image: postgres:16
    environment: POSTGRES_USER, POSTGRES_PASSWORD, POSTGRES_DB
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./postgres/init.sql:/docker-entrypoint-initdb.d/00-init.sql
    healthcheck: pg_isready

  backend-1: &backend
    build: ../backend
    environment:
      SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/lob
      JWT_SIGNING_SECRET=...
      SERVER_PORT=8080
      INSTANCE_ID=backend-1
    depends_on: { postgres: { condition: service_healthy } }
    healthcheck: curl -f http://localhost:8080/actuator/health

  backend-2:
    <<: *backend
    environment:
      SERVER_PORT=8080
      INSTANCE_ID=backend-2

  nginx:
    image: nginx:alpine
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf:ro
    ports: [ "80:80" ]
    depends_on: [ backend-1, backend-2 ]

  frontend:
    build: ../frontend                # multi-stage: angular build + nginx static
    ports: [ "4200:80" ]
    depends_on: [ nginx ]

volumes:
  pgdata:
```

`docker compose up` from the repo root brings the entire system up: Postgres seeds itself, both backends run migrations and start, nginx routes traffic, the Angular static bundle is served at `localhost:4200` and points its API base at `localhost`.

### 8.2 nginx config sketch

```
upstream backend {
    server backend-1:8080;
    server backend-2:8080;
    # round-robin (default). NO ip_hash — the design is stateless.
}

server {
    listen 80;

    location /api/ {
        proxy_pass http://backend;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    location /ws/ {
        proxy_pass http://backend;
        proxy_http_version 1.1;
        proxy_set_header Upgrade    $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;       # long-lived connections
    }

    location /actuator/health {
        proxy_pass http://backend;
    }
}
```

The WS upgrade headers are the only special-casing — they make WebSocket *work*, not stickiness.

### 8.3 Configuration / environment

| Variable | Where | Notes |
|---|---|---|
| `SPRING_DATASOURCE_URL` | backend | Points at the compose `postgres` service. |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | backend | From `.env`. |
| `JWT_SIGNING_SECRET` | backend | 256-bit random. Same for both backends so JWTs validate on either. |
| `INSTANCE_ID` | backend | Ends up in MDC + metrics labels — lets us tell which node logged what. |
| `OUTBOX_RETENTION_MINUTES` | backend | Janitor window. Default 5. |
| `SERVER_PORT` | backend | 8080 inside the container. |

A top-level `.env.example` documents these; CI / local dev copy it to `.env`.

### 8.4 Local dev outside compose

- Postgres via `docker run postgres:16` or local install.
- Backend × 2 from IntelliJ run configs (different `SERVER_PORT`).
- Frontend with `ng serve` proxying `/api` and `/ws` to a local nginx, or directly to one backend for fast iteration.
- Simulator with `mvn -pl simulator spring-boot:run -Dspring-boot.run.arguments="--mode=scenario ..."`.

### 8.5 CI

GitHub Actions: build backend + frontend + simulator on push, run unit + integration tests, run the §5.3 scenario via simulator against a freshly composed stack, fail on any consistency-check assertion. (Mentioned for completeness; the exercise doesn't strictly require CI.)

---

## 9. Cross-cutting concerns

### 9.1 Time

`TIMESTAMPTZ` everywhere. Server is the authority. `created_at` from Postgres' `now()` (transaction start) — important: time priority must be the **insert** time, not the per-statement clock, otherwise two orders inserted in one match transaction would tie. Acceptable here because each request inserts exactly one order.

### 9.2 Numeric handling on the wire

Java side: `BigDecimal` with `RoundingMode.HALF_EVEN` for any arithmetic, set to scale 4 before persist. JSON: prices serialized as strings to avoid floating-point drift on the wire (Jackson's `WRITE_BIGDECIMAL_AS_PLAIN`).

### 9.3 Logging contract

Every order-related event prints exactly one structured log line at INFO. Engineers grepping a node's logs can reconstruct the lifecycle of any `orderId` end-to-end. Sample fields:

```
ts="..." level=INFO instance=backend-1 requestId=... userId=u1
event=ORDER_ACCEPTED orderId=... clientOrderId=... symbol=AAPL side=BUY type=LIMIT price=180.50 qty=120
```

### 9.4 Security posture (deliberately limited)

- Passwords never logged. BCrypt hashing on store.
- JWT signing secret only via env var.
- CORS: locked to the frontend origin in compose; permissive only in dev.
- No HTTPS in compose (out of scope; trivially added with a self-signed cert if needed).
- No rate limiting (deliberate — out of scope, would be the next thing to add).

### 9.5 Failure modes and how the design copes

| Failure | Behaviour |
|---|---|
| One backend node dies mid-match | Tx rolls back; advisory lock auto-released; idempotent client retry on the other node succeeds. |
| Both backend nodes die after commit but before fan-out | On restart, NOTIFY is gone, but `market_event_outbox` still has the row. Listeners replay from the highest delivered id (held in memory; on restart, snapshot rebuild covers the gap). Trade-off: events older than the snapshot cursor are silently absorbed by the snapshot — correct, but "lost" as discrete events. Documented. |
| Postgres dies | Whole system unavailable. Acceptable for this exercise; in production, replicated Postgres. |
| Hash collision on advisory lock | Two unrelated symbols serialize. Throughput, not correctness, is affected. Mitigation path: dedicated `symbol_lock_id BIGINT` column. |
| Stale WS event after reconnect | Sequence cursor in snapshot frame discards already-seen events. |
| Client retries `POST /api/orders` with same `clientOrderId` | UNIQUE constraint + advisory lock + try/catch falls through to "return existing order." |
| MARKET order on empty book | Inserted, immediately CANCELLED with `INSUFFICIENT_LIQUIDITY`, `filled_qty=0`. |
| User cancels an order that just FILLED | Cancel transaction sees status FILLED, returns 409. |

### 9.6 Trade-offs explicitly chosen

- **Synchronous matching in the request path** (vs queue + worker). Better UX, simpler, throughput is plenty for spec; would not survive 10× the order rate without a redesign — accepted.
- **Postgres-only for fan-out** (vs Redis pub/sub). One fewer moving part, one slightly less idiomatic transport; meets the 1-second NFR comfortably.
- **Advisory locks** (vs row-level marker + SELECT FOR UPDATE). Cheaper, less I/O, micro-risk of hash collisions documented.
- **Stateless nodes, no sticky sessions.** Simpler operationally; pays a tiny snapshot-on-reconnect tax.
- **Single Maven module** (vs multi-module). Faster builds, simpler IDE setup; package boundaries enforce architecture.
- **Plain WebSocket, not STOMP.** Two channel patterns don't justify STOMP's framing.

### 9.7 What we'd do with more time (README will mirror this)

- Per-symbol single-writer threads fed from a `SKIP LOCKED` queue table to break the advisory-lock-per-match ceiling.
- Symbol shard via dedicated `symbol_lock_id` to eliminate the hash-collision micro-risk.
- Order book L2 history table for charts.
- HTTPS termination at nginx with a real cert.
- Rate limiting per user.
- A real broker for fan-out (Redis Streams or Kafka) so the snapshot cursor survives full-cluster restarts.
- Self-trade prevention (Alice's BUY shouldn't cross her own SELL — currently allowed; cheap to add as a `WHERE` predicate in the match scan).

---

## 10. Repository layout

```
limit-order-app/
 ├── backend/
 │   ├── pom.xml
 │   ├── src/main/java/com/example/lob/...
 │   ├── src/main/resources/
 │   │   ├── application.yml
 │   │   └── db/migration/
 │   │       ├── V1__init.sql
 │   │       ├── V2__seed_symbols.sql
 │   │       └── V3__seed_users.sql
 │   └── src/test/java/...
 ├── frontend/
 │   ├── package.json
 │   ├── angular.json
 │   └── src/...
 ├── simulator/
 │   ├── pom.xml
 │   └── src/main/java/com/example/lob/sim/...
 ├── infra/
 │   ├── docker-compose.yml
 │   ├── nginx.conf
 │   └── postgres/init.sql
 ├── docs/
 │   ├── requirnments/
 │   │   └── limit-order-requirements.md
 │   ├── architecture/
 │   │   └── architecture.md            ← this file
 │   └── ai-chat/                        deliverable §8.6
 ├── .env.example
 └── README.md
```

---

## 11. Build sequence (the implementation plan this blueprint sets up)

1. **Backend skeleton + schema**: Maven module, Spring Boot, Flyway migrations V1–V3, Hikari, security stub, actuator, JSON logging.
2. **Matching engine + tests**: `MatchingEngineService` with the advisory-lock + match-loop; the §5.3 scenario test as the first green light.
3. **REST API**: `/auth/login`, `/orders`, `/orders/mine`, `/book/{symbol}`, `/book/{symbol}/totals`, `/fills/mine`, `/symbols`. Idempotency via `clientOrderId`.
4. **Outbox + LISTEN/NOTIFY**: trigger, listener thread, in-process registry, WS handlers, snapshot-then-deltas frame.
5. **Concurrency tests**: 100-thread soak; consistency assertions.
6. **Frontend**: Angular workspace, login, market overview, symbol detail, my orders/fills, WS service.
7. **Simulator**: scenario mode against §5.3 first; load and multi-instance modes after.
8. **Infrastructure**: docker-compose with two backends + nginx + frontend; verify scenario passes against the composed stack.
9. **Docs**: README distilled from this blueprint; AI chat transcripts exported into `docs/ai-chat/`.

Each step ends with a green test or a working `curl` against the running system. Nothing merges in a half-built state.
