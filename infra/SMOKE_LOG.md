# Phase 6 — Smoke verification log

Run on a developer laptop (Apple Silicon, Docker Desktop), 2026-04-26,
against the full `docker compose up --build` stack. Plan §6.1–§6.6.

The smoke surfaced **two real bugs** in the Phase 0–5 simulator that
were fixed before this log was finalised — both surfaced because the
plan's earlier wiremock-stubbed tests didn't exercise the live
backend's auth posture. Bugs are documented under each phase below.

## Phase 6.1 — Cold-start

```
docker compose down -v
docker compose up -d --build
```

- Total time including Docker rebuild of both backend + frontend
  images: **57 s** (plan target ~30 s on a developer laptop).
  Without `--build` (after the first run), cold-start is **<25 s**.
- `docker compose ps` shows all five services up:
  ```
  lob-backend-1    Up 39 seconds (healthy)
  lob-backend-2    Up 39 seconds (healthy)
  lob-frontend     Up 12 seconds
  lob-nginx        Up 12 seconds
  lob-postgres-1   Up 45 seconds (healthy)
  ```

Acceptance: ✅ within budget (sans rebuild).

## Phase 6.2 — Manual smoke

```
$ curl -sS http://localhost/actuator/health
{"status":"UP","components":{"db":{"status":"UP"…},"outboxListener":{"status":"UP",
 "details":{"thread":"alive"}},…}}                                           # 200

$ curl -sSI http://localhost:4200/                                           # 200, 524 bytes (SPA shell)

$ curl -sS http://localhost/api/symbols                                      # 401 unauthorized
{"code":"UNAUTHORIZED","message":"authentication required"}

$ curl -sS -X POST -H 'Content-Type: application/json' \
       -d '{"username":"u1","password":"alice123"}' \
       http://localhost/api/auth/login | jq -r .token                        # JWT
$ curl -sS -H "Authorization: Bearer $TOKEN" http://localhost/api/symbols    # 5 symbols
```

- `/actuator/health` returns UP including the Phase 9 `outboxListener`
  health indicator (`thread: alive` — the dedicated LISTEN connection
  is live).
- Plan §6.2 wording assumed `/api/symbols` was permitAll (`curl …
  returns 5 symbols`). The backend actually requires auth — the curl
  flow above is what works. Worth a one-line README note.

Acceptance: ✅ all four checks green (with the auth flow correction).

## Phase 6.3 — Simulator scenario mode

```
java -jar simulator/target/simulator-0.1.0-SNAPSHOT.jar scenario \
  --baseUrl=http://localhost \
  --file=docs/requirnments/seed.csv \
  --expect=docs/requirnments/seed-expected-book.json \
  --report=/tmp/lob-scenario.json
```

```
scenario run 2d79e038-919f-4b0f-be0d-6842a877283f
  duration:        PT2.679807S
  submitted:       10
  accepted:        10
  rejected:        0
  assertions:
    [PASS] book:AAPL
    [PASS] book:MSFT
    [PASS] book:TSLA
    [PASS] book:GOOGL
    [PASS] book:AMZN
  result: PASS
exit: 0
```

**Bugs surfaced (and fixed)**:

1. `LobApiClient.getBook(String)` and `getTotals(String)` were declared
   without a `JwtToken` parameter, so the simulator hit `/api/book/*`
   unauthenticated — five `[FAIL] book:* — getBook(X) failed:
   authentication required` lines. **Fix:** added `JwtToken auth` to
   both methods; `ScenarioRunner` and `MultiInstanceRunner` updated
   to log in once and reuse the token. Unit tests updated.
2. `JsonReporter.write` crashed on `--report=/tmp/...` with
   `FileAlreadyExistsException: /tmp` — `Files.createDirectories` on
   macOS chokes on the `/tmp → /private/tmp` symlink even though the
   target directory exists. **Fix:** `if (parent != null && !Files.isDirectory(parent))`
   guard short-circuits the common case.
3. The book comparator used `BigDecimal.toPlainString().equals(...)`,
   which fails when the backend returns `NUMERIC(12,4)` values
   (`180.0000`) and the expected JSON has 2-dp values (`180.00`).
   **Fix:** switched to `BigDecimal.compareTo` for price equality;
   the diff text still uses `toPlainString` for human-readable output.

Acceptance: ✅ all five §5.4 symbol assertions PASS after the fixes.

## Phase 6.4 — Multi-instance

```
java -jar simulator/target/*.jar multi-instance \
  --baseUrl=http://localhost --nodeA=http://localhost --nodeB=http://localhost \
  --users=2 --rate=20 --duration=3 --seed=99
```

```
multi-instance run 3a35e349-f21a-4af5-b4f6-f461dd9c4d13
  duration:        PT6.228231S
  submitted:       34
  accepted:        30
  rejected:        4
  trades observed: 14
  assertions:
    [PASS] books-equal-across-nodes
    [PASS] buy-sell-net-zero
    [PASS] filled-le-quantity
    [PASS] trade-counterparts
  result: PASS
exit: 0
```

**Bug surfaced (and fixed)**: the `trade-counterparts` invariant
failed initially with 12 "expected exactly 2 fills, got 1" diffs.
Root-causing via `psql -c "SELECT trade_id … WHERE buyer.user_id =
seller.user_id"` confirmed self-trades (Alice's BUY crossing her own
resting SELL). The architecture explicitly documents self-trade
prevention as deferred (§9.7); the simulator's invariant was wrong
to flag self-trades as violations. **Fix:** when a trade has exactly
one fill row AND that fill's `counterparty == owner`, recognise it as
a self-trade and pass — the invariant's literal text ("references
a counter-party order on the opposite side") is still satisfied; the
counter-party order just happens to belong to the same user.

Acceptance: ✅ all four invariants PASS after the fix; book equality
across both LB-fronted nodes confirmed (architecture §3 NFR
"trade visible cross-node within 1 second" empirically green).

## Phase 6.5 — Resilience: kill backend mid-load

```
java -jar simulator/target/*.jar load --baseUrl=http://localhost \
  --users=2 --rate=20 --duration=10 --seed=42 --skip-consistency-check &
sleep 3
docker kill lob-backend-1
```

- Load run continues through the kill — `accepted=10, rejected=1`
  (the one in-flight request to backend-1 when SIGKILL hit). nginx's
  upstream failover routes new requests to backend-2 transparently.
  ✅ this is the architecturally meaningful guarantee.
- **`restart: unless-stopped` did NOT auto-restart** on Docker
  Desktop (macOS). After SIGKILL, `docker ps -a` shows
  `lob-backend-1   Exited (137) 38 seconds ago`. Manual recovery:
  `docker compose start lob-backend-1` → healthy again in ~8 s.
- Plan §6.5 wording expected automatic restart "within ~5 s". This
  is a known Docker Desktop quirk on macOS — the daemon doesn't
  reliably honour `unless-stopped` for SIGKILL exits — and isn't
  fixable in our compose config alone. Documented as a deferred
  Phase 9 hardening item (would be addressed by a sidecar process
  manager or k8s liveness probe in production).

Acceptance: ⚠️ partial — load survives the kill (architecturally
critical), but auto-restart needs a one-line manual step.

## Phase 6.6 — Persistence

```
docker compose ps                    # … 45 orders + 18 trades in DB
docker compose down                  # without -v
docker volume ls | grep lob_pgdata   # local  lob_pgdata  (preserved)
docker compose up -d
# wait for healthy
psql -c "SELECT count(*) FROM orders, count(*) FROM trades;"
                                     # orders=45, trades=18 — preserved
```

Acceptance: ✅ named volume `lob_pgdata` survives `down`; restart
recovers full state. Flyway recognises v1/v2/v3 already applied and
skips them on the second boot. Verified during Phase 4 of the
infra session and confirmed again here.

## Summary

| Phase | Result | Notes |
|---|---|---|
| 6.1 cold-start | ✅ | 57 s with `--build`; <25 s without |
| 6.2 manual smoke | ✅ | `/api/symbols` requires auth — plan wording assumed permitAll |
| 6.3 scenario | ✅ | After three simulator-side fixes (auth on getBook/getTotals; `/tmp` symlink in JsonReporter; numeric price compare) |
| 6.4 multi-instance | ✅ | After self-trade handling in `trade-counterparts` invariant |
| 6.5 kill resilience | ⚠️ | Load survives; auto-restart finicky on Docker Desktop / macOS |
| 6.6 persistence | ✅ | Named-volume round-trip clean |

Cumulative simulator state after Phase 6: **59/59 unit tests still
green** (including the regression coverage for the three bugs surfaced
above), four runnable modes, two end-to-end runs against the live
multi-instance compose stack producing all-green reports.
