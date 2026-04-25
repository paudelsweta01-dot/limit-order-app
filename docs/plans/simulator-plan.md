# Simulator — Implementation Plan

**Companion to:** [`architecture.md`](../architecture/architecture.md). Plan is *what* to build, *in what order*, with *what* signals "done."

**Scope:** Standalone Spring Boot CLI in `simulator/`. Separate JVM from the backend (mandated by §1 of the spec). Talks to the system **only over public HTTP / WebSocket APIs** — never imports backend code or touches the database.

**Definition of Done (component-level):**
- `mvn -pl simulator package` produces a runnable fat JAR.
- `--mode=scenario --file=seed.csv --expect=expected.json` exits 0 against a clean backend after producing the §5.4 snapshot.
- `--mode=load --users=4 --rate=83 --duration=60` runs the §5.5 stress profile and finishes inside the time bound.
- `--mode=multi-instance --nodeA=... --nodeB=...` confirms the books on both nodes converge within 1 s of run end.
- `--mode=consistency-check` exits non-zero if any §4.3 invariant is violated; exits 0 otherwise.
- A JSON run report is written to `--report=path/to/report.json` for any mode.

**Dependencies on other components:** Backend must be reachable for live runs. For unit tests, a stub HTTP server is enough.

---

## Phase 0 — Module bootstrap

| # | Task | Acceptance |
|---|---|---|
| 0.1 | Create `simulator/` Maven module with its own `pom.xml`, parent `<groupId>` matching backend but its own `<artifactId>`. Java 17. | `mvn -pl simulator validate` succeeds. |
| 0.2 | Dependencies: `spring-boot-starter`, `spring-boot-starter-validation`, `spring-boot-starter-test`, `picocli` (for arg parsing — clean and well-trodden), `jackson-databind`, `slf4j-api` + Logback, Java's `java.net.http.HttpClient` (no extra dep). | `mvn -pl simulator dependency:tree` clean. |
| 0.3 | `pom.xml` builds an executable fat JAR via `spring-boot-maven-plugin` with `<configuration><executable>true</executable></configuration>`. | `java -jar simulator/target/simulator-*.jar --help` prints picocli help. |

---

## Phase 1 — CLI entry & mode dispatch

| # | Task | Acceptance |
|---|---|---|
| 1.1 | `SimulatorApplication` is a `CommandLineRunner` that delegates to a picocli `@Command` root. Subcommands: `scenario`, `load`, `multi-instance`, `consistency-check`. Common options: `--baseUrl`, `--report`, `--logLevel`. | `--help` shows the four subcommands; each subcommand has its own help. |
| 1.2 | A `RunContext` value object carries common state: HTTP clients, user credentials, run id, start time. Built once per invocation. | Used by every mode. |
| 1.3 | Exit code contract: `0` = success / all assertions passed; `1` = assertion failed; `2` = configuration error; `3` = unexpected runtime error. | Documented in `--help` epilog. |

---

## Phase 2 — HTTP & WS clients

| # | Task | Acceptance |
|---|---|---|
| 2.1 | `LobApiClient` — wraps `java.net.http.HttpClient`. Methods: `login(username, password) -> JwtToken`, `submit(SubmitOrderRequest, JwtToken) -> OrderResponse`, `cancel(orderId, JwtToken)`, `getBook(symbol)`, `getTotals(symbol)`, `getMyOrders(JwtToken)`, `getMyFills(JwtToken)`. Each method handles `4xx/5xx` by throwing a typed exception with the §4.11 envelope deserialised. | Unit tests against a `WireMock` instance covering happy + error paths. |
| 2.2 | Token cache: per-user `JwtToken` cached for the run; refreshed on 401. | Test green. |
| 2.3 | Retry with backoff for transient errors (connect timeout, 502/503/504). **Same `clientOrderId` reused across retries** — verifies idempotency on the server. Cap at 3 attempts. | Test: stub returns 503 twice, then 201 — client returns the 201, only one order on the server. |
| 2.4 | `LobWsClient` — uses `java.net.http.WebSocket`. Subscribes to `book:{symbol}` or `orders:{userId}`; exposes a blocking `Iterator<Frame>` or a callback. JWT in upgrade `Authorization` header. | Smoke test against a stub WS endpoint. |

---

## Phase 3 — Scenario mode (the canary)

This is the first mode to build because it doubles as the system's smoke test.

| # | Task | Acceptance |
|---|---|---|
| 3.1 | `ScenarioMode` reads a CSV with header `clientOrderId,userId,symbol,side,type,price,quantity` (the §5.3 fixture). For each row, look up the user's JWT (logging in lazily), POST the order, capture the response. | Run against §5.3 seed CSV against a freshly-booted backend → all 10 orders accepted; three trades produced. |
| 3.2 | After the loop, fetch `getBook(symbol)` for every symbol in the file and compare to an `expected.json` describing the §5.4 snapshot. Diffs are reported as: `AAPL.asks[0]: expected price=180.50 qty=80 userCount=1, got price=180.50 qty=200 userCount=1`. | Assertion green for happy path; intentionally corrupted seed produces a clear, useful diff. |
| 3.3 | Optional `--expect-trades=expected-trades.json` asserts the set of trades produced matches an expected list (price, quantity, buy/sell user pair) — order-independent compare. | Test green for §5.3 → three expected trades. |
| 3.4 | Capture the run as a JSON report with: each order submitted (request + response), each trade observed via `getMyFills`, pass/fail per assertion. | `--report=run.json` produces well-formed JSON. |

---

## Phase 4 — Consistency check mode

The §4.3 invariants asserted via public APIs. Useful both as a standalone verification and as a building block for other modes.

| # | Task | Acceptance |
|---|---|---|
| 4.1 | Walk every user's `getMyOrders` and `getMyFills` (logging in as each seed user, since there's no admin endpoint) to assemble the universe of orders and trades. | Returns the same data the DB has, accessed through the API surface. |
| 4.2 | Invariant 1: `Σ filled_qty(BUY) == Σ filled_qty(SELL)` per symbol. | Checked. |
| 4.3 | Invariant 2: no order has `filled_qty > quantity`. (Already enforced by DB CHECK; assertion is belt-and-braces against an API misreporting.) | Checked. |
| 4.4 | Invariant 3: every trade observed in any user's fills references a counter-party order on the opposite side, and the trade's `buy_user_id` / `sell_user_id` agree with the orders' owners. | Checked. |
| 4.5 | Reports each invariant as PASS/FAIL with a list of offending rows on FAIL. Non-zero exit on any FAIL. | Manual fault-injection (delete a row directly in DB during a held tx) produces a clear report. |

---

## Phase 5 — Load mode (the §5.5 stress profile)

| # | Task | Acceptance |
|---|---|---|
| 5.1 | `LoadMode` spawns N virtual users (default 4) on a thread-per-user model (or virtual threads — Java 21 makes this easy). | Threads start, log in, and stop on `--duration` elapse. |
| 5.2 | **Submission timing**: per-virtual-user inter-arrival ~ exponential with mean `users/rate` so aggregate is `rate` orders/sec. (Poisson process — burstier than a metronome, more realistic.) | Time-series histogram of submissions matches expected mean within ±5% over the run. |
| 5.3 | **Order generation**: `symbol ~ U{5}`, `side ~ Bernoulli(0.5)`, `type ~ Bernoulli(0.7 LIMIT, 0.3 MARKET)`, `price = refPrice * (1 + N(0, 0.01))` rounded to 4dp, `qty ~ U[10, 500]`. Driven by a seedable RNG (`--seed=N`) so runs are reproducible. | Same `--seed` produces the same order sequence. |
| 5.4 | After run end, runs the consistency-check (Phase 4) automatically. | One green or red line at the end of the run. |
| 5.5 | Run-time metrics emitted at INFO every 5s: orders submitted, accepted, rejected, in-flight latency p50/p95/p99. | Visible in stdout during long runs. |

---

## Phase 6 — Multi-instance mode

| # | Task | Acceptance |
|---|---|---|
| 6.1 | `MultiInstanceMode` accepts `--nodeA=` and `--nodeB=` instead of a single `--baseUrl`. | Both URLs reachable on startup; fail fast otherwise. |
| 6.2 | Round-robin submissions across the two nodes (alternating, or hashed-by-clientOrderId for determinism). | Inspect logs: half of submissions logged to each node. |
| 6.3 | Two `LobWsClient` connections — one to each node — each subscribed to the book of every symbol. After the load phase, **wait up to 1s** then snapshot both nodes' books and assert byte-identical equality (after canonical ordering) per symbol. | Test green for clean runs; intentionally killing one node mid-run reports a clear divergence. |
| 6.4 | Same end-of-run consistency check as Phase 4. | Green or clear FAIL. |

---

## Phase 7 — Reporting

| # | Task | Acceptance |
|---|---|---|
| 7.1 | `ConsoleReporter` prints a colored summary: mode, duration, orders submitted/accepted/rejected, trades observed, invariants pass/fail. | Visual: clean and parseable. |
| 7.2 | `JsonReporter` writes the same data structured. Schema in `simulator/REPORT_SCHEMA.md` (small file). | Schema documented; example file in `simulator/src/test/resources/`. |
| 7.3 | Non-zero exit codes per Phase 1.3 contract. | `echo $?` confirms after intentional failures. |

---

## Phase 8 — Tests

| # | Task | Acceptance |
|---|---|---|
| 8.1 | Unit tests for the order generator: distribution shape (mean, variance) on 10k samples, type/side ratios within tolerance, price within ±3σ. | Green. |
| 8.2 | Unit tests for `LobApiClient` against WireMock: 200 / 400 / 401 / 409 / 5xx all handled. | Green. |
| 8.3 | Integration test: spin up Testcontainers Postgres + the backend (via Maven dep on `backend`'s built JAR, executed as a child process) + the simulator's `scenario` mode pointed at it. Assert exit 0. | Test green; runs in CI. |

> **Note**: the backend dep here is for *running* the JAR as a child process, not for importing classes. The simulator does not link against backend code.

---

## Phase 9 — Hardening

| # | Task | Acceptance |
|---|---|---|
| 9.1 | Backpressure: if the server starts returning 503 or latency p99 doubles, throttle the submission rate to half until recovered. (Optional but realistic.) | Manual stress test. |
| 9.2 | Graceful shutdown on `SIGTERM`: stop new submissions, drain in-flight, write report. | `kill -TERM` produces a complete report. |
| 9.3 | Time skew tolerance: don't rely on JVM clock for ordering — use server-issued `created_at` and `executed_at` timestamps. | Code review pass. |

---

## Risks & open items

- **No admin endpoint** to enumerate all orders/trades. Phase 4 walks per-seed-user; if the spec ever adds non-seed users, the simulator would need to know about them. Document the assumption: simulator works against the seeded universe only.
- **Race window in scenario mode** between submission and post-run snapshot reads. Wait up to 1s (the §3 NFR) for events to settle before asserting. Polling with Awaitility-style retry is cleaner than a fixed sleep.
- **WebSocket vs REST for snapshots in multi-instance mode**: WS is closer to what real clients see; REST is simpler and deterministic. Prefer WS for the convergence check (it's the real test of the fan-out path).
- **Determinism**: Poisson timing + Gaussian prices mean two runs of `--seed=42` should produce the same sequence even though sampled distributions are continuous. Document the dependence on `--seed`.

## Suggested execution order

Phase 0 → 1 → 2 → **3 (scenario mode green = the simulator's first milestone)** → 4 → 5 → 6 → 7 → 8 → 9. Phase 3 uses the §5.3 fixture and is the simulator's smoke test for the rest of development.
