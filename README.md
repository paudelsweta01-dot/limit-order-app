# Limit Order App

Multi-user, multi-threaded, multi-instance limit-order matching system.
Spring Boot 3.3 backend × 2 instances, Angular 21 frontend, Postgres 16,
nginx LB, Java CLI simulator. Runs end-to-end via `docker compose up`;
reviewer-runnable on a laptop.

> **Spec:** [`docs/requirnments/limit-order-requirements.md`](docs/requirnments/limit-order-requirements.md) ·
> **Blueprint:** [`docs/architecture/architecture.md`](docs/architecture/architecture.md) ·
> **Per-component plans:** [`docs/plans/`](docs/plans/) ·
> **AI-chat transcripts (per Deliverable §8.6):** [`docs/ai-chat/`](docs/ai-chat/)

## How to run

The full stack via docker compose:

```bash
cp .env.example .env                                        # then edit secrets
docker compose up --build                                   # ~30 s after first build
```

Once `docker compose ps` shows all five services healthy:

| URL | What |
|---|---|
| <http://localhost> | Angular SPA + API + WS, all same-origin via the LB nginx (login → market overview → place orders). **Use this URL in your browser.** |
| <http://localhost:4200> | Direct access to the frontend container (debug only — bypasses the LB; the SPA's `POST /api/auth/login` will 405 here because :4200 is just a static-file server with no `/api/` proxy) |
| <http://localhost/actuator/health> | Backend health (returns UP including outboxListener) |
| `127.0.0.1:5432` (`psql -U lob -d lob`) | Postgres for inspection (loopback-only by design) |

**Seed users** (from V3 Flyway migration): `u1/alice123`, `u2/bob123`,
`u3/charlie123`, `u4/diana123`. Open two browser windows as
different seed users to demo cross-tab consistency.

### Smoke against the running stack

```bash
# Build the simulator JAR once
(cd simulator && ./mvnw -DskipTests package)

# Replay the §5.3 seed CSV and assert the §5.4 expected book
java -jar simulator/target/simulator-0.1.0-SNAPSHOT.jar scenario \
  --baseUrl=http://localhost \
  --file=docs/requirnments/seed.csv \
  --expect=docs/requirnments/seed-expected-book.json \
  --report=/tmp/scenario.json
```

Exit code 0 = `PASS book:* x5`. The §6 smoke results from a real run
are in [`infra/SMOKE_LOG.md`](infra/SMOKE_LOG.md).

The simulator has four modes — `scenario`, `load`, `multi-instance`,
`consistency-check`. See `simulator --help` and
[`docs/plans/simulator-plan.md`](docs/plans/simulator-plan.md).

### Local-dev (no full compose)

For tight backend-test loops:

```bash
docker compose -f infra/dev/postgres-only.compose.yml up -d
# now run the backend from your IDE on :8080 (and a second on :8081 for
# multi-instance work). Angular's proxy.conf.json already points at :8080.
```

## Architecture

```
                            ┌──────────────────────┐
   browser  (Angular SPA)──►│   nginx reverse proxy│──► backend-1 ─┐
   browser  (Angular SPA)──►│   round-robin LB     │──► backend-2 ─┤
                            └──────────────────────┘               ▼
                                                              ┌─────────┐
                                                              │Postgres │
                                                              └─────────┘
                                                                ▲     ▲
                                            outbox + LISTEN/NOTIFY    │
                                            ◄────────────────────────►│
                                                                      │
                                                       simulator ─────┘
                                                       (separate JVM,
                                                        hits nginx VIP)
```

Components:
- **`backend/`** — Spring Boot 3.3.5, Java 17. JPA + JdbcTemplate (engine
  on Jdbc for SQL control), Flyway-owned schema, JWT auth (HS256, 12h),
  Micrometer + structured JSON logs.
- **`frontend/`** — Angular 21 standalone components, Vitest, signal-based
  state, raw WebSocket (no STOMP). Same-origin via the LB; built bundle
  served by nginx in the `frontend` container.
- **`simulator/`** — Spring Boot CLI, picocli, `java.net.http.HttpClient` +
  `WebSocket`. Talks to the backend over public APIs only — never imports
  backend code or touches the DB.
- **`infra/`** — `nginx/nginx.conf` (round-robin LB, WS upgrade headers,
  16 KB cap on `/api/orders`), `postgres/00-init.sql` (timezone-only;
  Flyway owns DDL), `dev/postgres-only.compose.yml` for the no-compose
  dev loop.

## Concurrency strategy

Three correctness gates, each enforced at exactly one layer:

1. **Per-symbol serialisation** via `pg_advisory_xact_lock(hashtext(symbol))`
   in the matching transaction. Two orders on the same symbol can't run
   concurrently inside the engine; orders on different symbols can. The
   lock is held only for the engine transaction's lifetime (architecture
   §4.3) — releases automatically on commit/rollback.

2. **Cross-node fan-out** via a transactional outbox + Postgres
   `LISTEN/NOTIFY`. State changes (book updates, trades, order status
   transitions) write rows to `market_event_outbox` *in the same
   transaction* as the underlying state change. Postgres' commit fires
   `pg_notify` exactly once per row; each backend node holds a dedicated
   `LISTEN` connection (NOT through Hikari — Hikari rotates) and pushes
   payloads to its own connected WebSocket clients. The result: a trade
   matched on backend-1 reaches a browser tab connected to backend-2
   in <1 s — empirically verified by the simulator's `multi-instance`
   mode (see SMOKE_LOG.md §6.4).

3. **Idempotent submission** via `UNIQUE(user_id, client_order_id)`. The
   simulator and frontend mint a UUIDv7 `clientOrderId` per submit
   attempt and **reuse it on transient retry**; the backend either
   inserts a fresh order or returns the existing one with
   `idempotentReplay: true`. Network blips don't double-book.

NFR §3 (correctness under concurrency) is satisfied because (1) prevents
intra-node races, (2) gives cross-node consistency without sticky
sessions, and (3) closes the client-retry hole. The
`consistency-check` mode asserts the §4.3 invariants
(Σ filled BUY = Σ filled SELL, `filled_qty ≤ quantity`, trade
counterparts agree) against the live API surface.

## Trade-offs

Things explicitly chosen, with their cost:

- **Postgres advisory lock + `LISTEN/NOTIFY`** over Redis/Kafka — one
  fewer service to run and back up. Throughput ceiling per symbol is
  bounded by single-row write contention; fine at the spec's 5-symbol /
  5000-order/min scale, would need partitioning at high-frequency-trading
  volumes.
- **JdbcTemplate (not JPA) for the engine path** — explicit SQL means
  the locking + outbox semantics are obvious in code review. JPA's
  caching and dirty-tracking would obscure both. JPA is still used for
  read-only entities (User, Symbol).
- **Stateless backends, no sticky sessions on the LB** — every request
  can hit either node, which forces every code path to honour the
  multi-instance contract. The LB nginx config explicitly omits
  `ip_hash` and `sticky cookie`.
- **Raw WebSocket, not STOMP** — STOMP's pub/sub abstraction over a
  wire protocol adds plumbing we don't need; the snapshot-then-deltas
  pattern with cursor floors is straightforward over raw frames.
- **JWT in `sessionStorage` (not `localStorage` or HttpOnly cookie)** —
  cleared on tab close, limits XSS exposure. A real product would use
  HttpOnly cookies; sessionStorage is the right shape for a demo where
  reviewers open multiple tabs.
- **No prometheus scraping** — `/actuator/prometheus` registration is
  finicky in the current Spring Boot 3.3.5 + Micrometer 1.13 + Prometheus
  client 1.x combo and is documented as a deferred deployment-time fix.
  Direct `MeterRegistry` assertions cover the metrics machinery; only
  the scrape endpoint is missing.

## Hardening (infra Phase 9)

Optional production-leaning extras. Off by default — none of these are
required for the reviewer flow above.

- **TLS via mkcert** — `make tls-setup` generates a local-CA-signed
  cert, then start the stack with the overlay:
  ```bash
  docker compose -f docker-compose.yml -f infra/tls/docker-compose.tls.yml up --build
  ```
  Browser opens `https://localhost/` without warnings; `:80` 301-redirects.
- **Login rate limit** — nginx caps `/api/auth/login` at 5 req/min/IP
  (burst 2, returns 429 on excess). See `infra/nginx/nginx.conf`.
- **Compose cpu / mem caps** — each service has a `cpus:` + `mem_limit:`
  in `docker-compose.yml` so a runaway process can't drag the laptop
  into swap. `docker stats` shows enforced limits.
- **Postgres backup** — `make backup` runs `pg_dump` against the live
  stack and writes `backups/lob-<ts>.sql.gz`. See
  `infra/scripts/backup.sh`.
- **SIGTERM-graceful simulator** — `kill -TERM <pid>` on a running
  `load` or `multi-instance` mode produces a complete report (workers
  drain, JSON report still written). See `simulator/TIME_SKEW_REVIEW.md`
  for the related ordering-vs-timing review.
- **Backpressure on simulator load** — auto-throttles the submission
  rate to half if the server returns 503s or p99 latency doubles vs the
  first window's baseline; releases on recovery.

## Known deferred items

Per the per-component plans + the smoke log:

- **Self-trade prevention** — Alice's BUY can cross Alice's resting SELL.
  Documented in architecture §9.7. The simulator's `trade-counterparts`
  invariant treats self-trades as valid (the trade does reference an
  opposite-side order; both sides happen to be the same user).
- **`/actuator/prometheus`** endpoint registration — see Trade-offs.
- **Auto-restart on `docker kill`** — `restart: unless-stopped` doesn't
  reliably fire on Docker Desktop (macOS) for SIGKILL. Manual recovery
  is `docker compose start <service>`. Production deployment would
  use k8s liveness probes or a process supervisor.
- **uuidv7 in browsers** — using a small dependency (`uuidv7` package,
  ~1 KB) since browsers' `crypto.randomUUID()` produces v4. Time-ordered
  ids help debugging.

## Repository layout

```
.
├── README.md                           # this file
├── .env.example                        # POSTGRES_PASSWORD, JWT_SIGNING_SECRET, …
├── docker-compose.yml                  # full stack (5 services)
├── backend/                            # Spring Boot matching engine
├── frontend/                           # Angular SPA
├── simulator/                          # CLI driver (4 modes + JSON reporter)
├── infra/
│   ├── README.md                       # port assignments
│   ├── SMOKE_LOG.md                    # Phase 6 results from a real run
│   ├── nginx/nginx.conf                # round-robin LB + WS upgrade
│   ├── postgres/00-init.sql            # timezone only; Flyway owns DDL
│   └── dev/postgres-only.compose.yml   # no-compose dev loop
└── docs/
    ├── requirnments/                   # spec + seed.csv + expected book
    ├── architecture/                   # blueprint
    ├── plans/                          # per-component implementation plans
    └── ai-chat/                        # AI-coding-assistant transcripts (Deliverable §8.6)
```

## Tests

```bash
(cd backend   && ./mvnw test)   # 114 tests (matching, scenario replay, multi-node WS proof, BigDecimal round-trip, qty bounds)
(cd frontend  && npx ng test --watch=false)   # 118 Vitest unit tests
(cd simulator && ./mvnw test)   # 63 unit tests + 1 Testcontainers integration test (skipped if backend JAR missing)
```
