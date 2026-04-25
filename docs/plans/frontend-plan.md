# Frontend — Implementation Plan

**Companion to:** [`architecture.md`](../architecture/architecture.md). Plan is *what* to build, *in what order*, with *what* signals "done."

**Scope:** Angular (latest LTS) workspace in `frontend/`. Standalone components. Signals + RxJS, **no NgRx**. Native WebSocket.

**Definition of Done (component-level):**
- All four §6 screens render against a live backend through nginx.
- Login → JWT stored → guarded routes work; 401 forces logout.
- Place Order, Cancel work end-to-end with idempotent retries on transient network failure.
- WS streams the order book and own orders in near real-time; reconnect after a forced drop produces a fresh snapshot and no duplicate state.
- Two browser tabs as different users (Alice, Bob) demonstrate cross-tab consistency: a trade between them updates both tabs' "My Orders" / "My Fills" within 1s.
- `npm run build` produces a static bundle that nginx in compose serves.

**Dependencies on other components:** Backend Phases 4 (auth), 5 (submit/cancel), 6 (read API), 8 (WS) must be live for full integration. Phases 1–3 of this plan can run alongside backend Phase 5 with mocked endpoints.

---

## Phase 0 — Workspace bootstrap

| # | Task | Acceptance |
|---|---|---|
| 0.1 | `ng new frontend --standalone --routing --style=css --skip-tests=false`. Pin Angular to the latest LTS. | `ng serve` boots; default page renders. |
| 0.2 | Folder structure per architecture §5.1: `core/`, `auth/`, `market/`, `symbol/`, `me/`, `shared/`. | Empty placeholder files in each; `tsc` clean. |
| 0.3 | Strict TS: `"strict": true`, `"noImplicitAny": true`, `"strictNullChecks": true`. | Build passes. |
| 0.4 | Configure `environment.ts` / `environment.prod.ts` with `apiBaseUrl`, `wsBaseUrl`. Dev points at `http://localhost:8080`; prod points at `''` (same-origin via nginx). | `ng build --configuration=production` succeeds. |
| 0.5 | Dev proxy (`proxy.conf.json`) for `/api` and `/ws` to a local backend, so `ng serve` works without CORS. | `curl http://localhost:4200/api/symbols` proxies through. |

---

## Phase 1 — Core services

The plumbing every page depends on. Build once; consume everywhere.

| # | Task | Acceptance |
|---|---|---|
| 1.1 | `core/models.ts` — typed interfaces matching the API: `Symbol`, `Order`, `Trade`, `BookLevel`, `BookSnapshot`, `OrderEvent`, `BookEvent`. | `tsc` clean; all consumers import from here. |
| 1.2 | `core/auth.service.ts` — `login(username, password)`, `logout()`, `currentUser` signal, JWT stored in `sessionStorage` (not `localStorage` — limits exposure). Decode JWT to extract `userId`, `name`, `exp`. Auto-logout when `exp` passes. | Unit tests cover happy path + tamper + expiry. |
| 1.3 | `core/http.interceptor.ts` — attaches `Authorization: Bearer <token>`, redirects to `/login` on 401, surfaces validation messages from the §4.11 error envelope. | Tested with `HttpTestingController`. |
| 1.4 | `core/api.service.ts` — typed methods: `login`, `getSymbols`, `getBook`, `getTotals`, `submitOrder` (auto-generates `clientOrderId = uuidv7()`), `cancelOrder`, `getMyOrders`, `getMyFills`. | One test per method. |
| 1.5 | `core/auth.guard.ts` — protects authenticated routes; preserves `returnUrl`. | Visiting `/symbol/AAPL` while logged out lands on `/login?returnUrl=...`; after login, redirects back. |
| 1.6 | `app.routes.ts` — wires routes per architecture §5.2. | Manual click-through covers all routes. |

---

## Phase 2 — Login page (§6.1)

The smallest end-to-end vertical slice; validates Phases 1.x against the real backend.

| # | Task | Acceptance |
|---|---|---|
| 2.1 | `auth/login.page.ts` — reactive form, two fields, submit calls `AuthService.login`. | Renders matching the §6.1 wireframe. |
| 2.2 | Display backend errors inline (`401 Bad credentials` → "Wrong username or password"). | Manual test with bad creds. |
| 2.3 | Successful login navigates to `returnUrl ?? '/'`. | Manual test. |
| 2.4 | Logout button in the top-right of the layout calls `AuthService.logout()` and routes to `/login`. | Manual test. |

---

## Phase 3 — WebSocket service (the central nervous system)

Built next because every subsequent page depends on it.

| # | Task | Acceptance |
|---|---|---|
| 3.1 | `core/ws.service.ts` — exposes two methods: `subscribeBook(symbol): Observable<BookSnapshot>` and `subscribeOrders(): Observable<OrderEvent>`. Internally manages: connect (with JWT), receive snapshot frame (sets initial state), receive delta frames (mutates), drop frames with `id <= snapshotCursor`. | Marble test stubs `WebSocket` with a fake; verifies snapshot-then-deltas behaviour. |
| 3.2 | Reconnect with exponential backoff (250ms → 5s, ±20% jitter, capped). On reconnect, re-issue the subscription, treat the next snapshot as fresh state — discard the old. | Test: forcibly close the underlying socket; observable continues emitting after reconnect. |
| 3.3 | Multiple components subscribing to the same symbol's book share one underlying WS — refcount; close the socket when the last subscriber unsubscribes. | Subscriber-count test passes. |
| 3.4 | Auth: on JWT expiry, the WS service triggers `AuthService.logout()` (so subsequent reconnects don't loop). | Test green. |

---

## Phase 4 — Market Overview page (§6.2)

The landing page after login.

| # | Task | Acceptance |
|---|---|---|
| 4.1 | `market/overview.page.ts` — on init, fetch `/api/symbols`, then for each symbol subscribe to `WsService.subscribeBook(symbol)` to keep `lastBid`, `lastAsk`, `last`, `demand`, `supply` live. | Visiting `/` after seed run shows the §6.2 table values exactly. |
| 4.2 | "Demand" and "Supply" computed as the sum of book qty across all five visible levels (top-5 levels suffice for the simple table view). Displays `-` when the side is empty. | Matches §6.2 wireframe (AMZN row all dashes). |
| 4.3 | Auto-refresh indicator: a small green dot when WS is connected, amber when reconnecting. | Visual test. |
| 4.4 | "Open" button per row routes to `/symbol/{symbol}`. | Click navigates correctly. |

---

## Phase 5 — Symbol Detail page (§6.3)

The interactive page where users place and watch orders.

| # | Task | Acceptance |
|---|---|---|
| 5.1 | `symbol/symbol-detail.page.ts` route param `:symbol`; subscribes to `WsService.subscribeBook(symbol)`. | Navigating to `/symbol/AAPL` after seed renders the §6.3 wireframe values (`Qty / Price / Users` columns matching §5.4). |
| 5.2 | `symbol/order-book.component.ts` — pure presentation: bids on the left, asks on the right; price-level rows with `Qty`, `Price`, `Users`. Top 5 levels per side; missing levels render blank rows. | Visual match. |
| 5.3 | `Total Demand`, `Total Supply`, `Last` shown beneath the book — wired to the same WS stream + `getTotals` for the absolute total (since the WS emits top-5; totals consider the full book server-side). | Matches §6.3 wireframe. |
| 5.4 | `symbol/place-order.form.ts` — reactive form: `side` (BUY/SELL radio), `type` (LIMIT/MARKET radio), `price` (disabled when MARKET, required when LIMIT), `quantity` (≥ 1). Submit generates a fresh `clientOrderId = uuidv7()`. On transient failure, the same `clientOrderId` is reused for retry. | Idempotency test in browser: double-click submit, only one order appears in My Orders. |
| 5.5 | Form-level validation: prevent submit when invalid; surface backend 400 errors inline. | Test green. |
| 5.6 | Toast on successful submit ("Order placed"); the eventual fill arrives via the WS stream and updates the book/My Orders without page action. | Visual test. |
| 5.7 | "Back" button to `/`. | Click navigates. |

---

## Phase 6 — My Orders & My Fills (§6.4)

The user's view of their own activity.

| # | Task | Acceptance |
|---|---|---|
| 6.1 | `me/my-account.page.ts` shell with two sections (My Orders, My Fills). | Renders both halves of §6.4. |
| 6.2 | `me/my-orders.page.ts` — initial load via `getMyOrders`, then live updates via `WsService.subscribeOrders()`. Columns per §6.4. `[X]` cancel button visible on `OPEN` / `PARTIAL` rows. | Cancel button calls `cancelOrder(id)`; row updates to CANCELLED via WS within 1s. |
| 6.3 | `me/my-fills.page.ts` — initial load via `getMyFills`. Updated by reacting to `TRADE_EXECUTED` events on the orders WS (which include trade payload references) — fetch the new fills list when one arrives. (Trade-off: an extra REST call per trade; simpler than maintaining a parallel list.) | Two-tab test: Alice and Bob trade with each other; both see the new fill within 1s. |
| 6.4 | Empty states: "No open orders" / "No fills yet" when lists are empty. | Visual test. |
| 6.5 | Sort: orders newest first, fills newest first. | Verified after seed run. |

---

## Phase 7 — Cross-cutting polish

| # | Task | Acceptance |
|---|---|---|
| 7.1 | Global toast service for transient successes/errors. | Used by login + place order + cancel. |
| 7.2 | Loading skeletons / spinners for the initial paint of each page. | Visible briefly on a slow connection. |
| 7.3 | Page layout: header with app name, current user, logout button (per §6.2 mock). | Matches mock. |
| 7.4 | Format helpers: `BigDecimal`-as-string price formatter (4dp), qty formatter (thousands separator), timestamp formatter (`yyyy-MM-dd HH:mm:ss`). All in `shared/format.ts`. | Used consistently across pages. |
| 7.5 | Accessibility: each form input has a `<label>`; tab order is sane; ESC closes toasts. | Manual a11y pass. |

---

## Phase 8 — Manual two-tab demo script (the sign-off bar)

Goal: produce a repeatable manual test that proves the whole frontend works end-to-end against a multi-instance backend.

1. `docker compose up`.
2. Open Tab A as Alice (`u1`) and Tab B as Bob (`u2`) — different browser windows so cookies/JWTs don't collide.
3. In Tab A: place AAPL SELL LIMIT 180.50 × 200.
4. Confirm Tab B's Market Overview row for AAPL updates within 1s (`Best Ask 180.50`, `Supply 200`).
5. In Tab B: navigate to `/symbol/AAPL`; place AAPL BUY LIMIT 180.50 × 120.
6. Confirm:
   - Tab A's "My Orders" row for the SELL transitions to PARTIAL with filled 120; "My Fills" gains a new row.
   - Tab B's "My Orders" shows the BUY as FILLED; "My Fills" gains a new row.
   - Both tabs' Order Book at AAPL shows the remaining 80 on the ask side.
7. In Tab B: cancel an unrelated open order (place one first if needed). Confirm row moves to CANCELLED in both tabs.

Anything that fails this script blocks ship.

---

## Risks & open items

- **WebSocket through nginx**: any misconfig of the upgrade headers manifests as silent immediate disconnect. Document the symptom and the fix once observed.
- **JWT in `sessionStorage`** loses login across browser restarts but limits XSS exposure compared to `localStorage`. Acceptable for a demo. A real product would use HttpOnly cookies.
- **uuidv7 in browsers**: most modern environments support `crypto.randomUUID()` (UUIDv4). True UUIDv7 needs a tiny library (e.g. `uuidv7` package, ~1 KB). Use the library — time-ordering helps debugging.
- **Trade events triggering REST fetch for fills (Phase 6.3)**: cheap and obviously correct. If the volume ever grows, switch to a dedicated `/ws/fills/mine` channel. Documented.
- **Reconnect storms** under nginx restart will hit the snapshot endpoint hard. At the §5.5 scale it's a non-issue. Documented.

## Suggested execution order

Phase 0 → 1 → 2 (verifies real backend auth) → 3 (WS plumbing) → 4 → 5 → 6 → 7 → 8 (sign-off). Phases 4–6 can be parallelised among teammates; for a one-person build, top-to-bottom is correct.
