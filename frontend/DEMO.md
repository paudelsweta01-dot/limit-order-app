# Frontend two-tab demo script (Phase 8 — sign-off bar)

A repeatable manual test that proves the SPA works end-to-end against the
multi-instance backend. Anything that fails this script blocks ship.

> Source: `docs/plans/frontend-plan.md` §8.

## Prerequisites

```bash
# From the repo root:
cp .env.example .env                         # then edit secrets
docker compose up --build                    # first run; subsequent: docker compose up
```

Wait for `docker compose ps` to show all five services healthy
(postgres, backend-1, backend-2, nginx, frontend). Cold start with
`--build` is ~60 s on a developer laptop; ~25 s without.

## Browser windows

- **Tab A**: <http://localhost/> — log in as `u1` / `alice123`.
- **Tab B**: <http://localhost/> in a **separate browser window** (not
  just a new tab) — log in as `u2` / `bob123`.
  Separate windows ensure the two `sessionStorage` JWT slots don't
  collide.

> The canonical browser URL is `http://localhost/` (port 80 — the LB
> nginx proxies SPA + API + WS through one origin). `http://localhost:4200`
> is the frontend container's debug-only direct port; logging in there
> 405s because that nginx has no `/api/` proxy. See
> [`infra/SMOKE_LOG.md`](../infra/SMOKE_LOG.md#phase-62--manual-smoke).

## Steps

1. **Tab A — place a SELL.**
   Navigate to `Market Overview`, click `AAPL`, then in the place-order
   form: `SELL` · `LIMIT` · price `180.50` · qty `200`. Submit.

2. **Tab B — verify cross-node propagation (≤ 1 s).**
   On `Market Overview`, the AAPL row should update within one second:
   - `Best Ask` → `180.5000`
   - `Supply` → `200`

   Acceptance: ✅ proves the outbox + `LISTEN/NOTIFY` cross-node fan-out
   reaches Tab B's connected backend (architecture §3 NFR — "trade
   visible cross-node within 1 second"). The two tabs are deliberately
   bouncing across `backend-1` and `backend-2` via the LB's round-robin.

3. **Tab B — place a BUY that partially fills Tab A's resting SELL.**
   Navigate to `/symbol/AAPL`, then: `BUY` · `LIMIT` · price `180.50` ·
   qty `120`. Submit.

4. **Confirm the matching outcomes.**
   - **Tab A — My Orders**: the `c-sell-200` row transitions to
     `PARTIAL`, `Filled` shows `120`. Within ~1 s.
   - **Tab A — My Fills**: a new fill row appears (qty 120, price
     `180.5000`).
   - **Tab B — My Orders**: the BUY shows `FILLED`. `Filled` = qty.
   - **Tab B — My Fills**: a new fill row appears (qty 120, price
     `180.5000`).
   - **Both tabs — Order Book at AAPL**: ask side now shows `80` at
     `180.5000` (the unmatched remainder).

5. **Tab B — cancel an unrelated open order.**
   Place a fresh BUY first if no open order exists (e.g. `MSFT` ·
   `LIMIT` · `400.00` · qty `10`). Then click the `Cancel` button on
   that row in `My Orders`.
   - **Tab B — My Orders**: row moves to `CANCELLED`.
   - **Tab A — Market Overview**: the corresponding `MSFT` totals row
     reflects the removed quantity within ~1 s.

## Acceptance summary

| # | Behaviour | Pass criterion |
|---|---|---|
| 2 | Cross-node fan-out (Tab A → Tab B) | Best Ask + Supply update within 1 s |
| 4a | Tab A's resting SELL → PARTIAL | `Filled = 120`, status `PARTIAL` |
| 4b | Tab A fill row | New row, qty 120, price `180.5000` |
| 4c | Tab B's BUY → FILLED | `Filled = 120`, status `FILLED` |
| 4d | Tab B fill row | New row, qty 120, price `180.5000` |
| 4e | Both books match | Ask side: `80 @ 180.5000` |
| 5 | Cancel propagates | `CANCELLED` in Tab B; totals shift in Tab A |

If any cell fails, **do not ship**. File the failure with browser
console + network tab + the affected backend's logs (instance is in the
`upstream=` field of the LB access log).

## Recovery if something looks off

- **Tab shows stale data after a network blip.** The WS service has
  reconnect-with-backoff and resubscribes from snapshot+cursor. Give it
  3 s; if still stale, refresh the tab (forces a fresh `GET /api/...`
  paint then WS replay).
- **`Failed to connect` toast.** Check the LB nginx is healthy:
  `curl http://localhost/actuator/health`. If a backend is down,
  `docker compose start lob-backend-1` (Docker Desktop on macOS
  doesn't reliably honour `restart: unless-stopped` for SIGKILL —
  see [`infra/SMOKE_LOG.md`](../infra/SMOKE_LOG.md#phase-65--resilience-kill-backend-mid-load)).
- **`405 Not Allowed` on login.** You're loading the SPA from
  `:4200`. Switch to `http://localhost/`. The frontend container's
  static-file nginx has no `/api/` proxy.
