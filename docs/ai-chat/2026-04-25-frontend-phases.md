# AI Coding Assistant — Frontend Implementation Session (Phases 0–7)

**Dates:** 2026-04-25 (Phases 0–3) / 2026-04-26 (Phases 4–7)
**Participants:** Sweta Paudel (engineer) and Claude (AI coding assistant)
**Session purpose:** Implement frontend Phases 0 through 7 from [`docs/plans/frontend-plan.md`](../plans/frontend-plan.md), per the spec in [`docs/requirnments/limit-order-requirements.md`](../requirnments/limit-order-requirements.md), the architecture in [`docs/architecture/architecture.md`](../architecture/architecture.md), and the project standards in [`.claude/instructions.md`](../../.claude/instructions.md).
**Outcome:** **118/118 unit tests green** at the end of the session. All four §6.x screens are wired against the backend: login (§6.1), Market Overview (§6.2), Symbol Detail with order book + place-order form (§6.3), and My Orders + My Fills (§6.4). The WebSocket multiplexer feeds snapshot+delta updates to every page; refcount keeps one socket per channel; cursor floor closes the architecture §4.8 race; toast service + ESC-to-dismiss + loading states + format helpers + the §6.2 layout chrome ship the cross-cutting polish. 8 atomic frontend commits + 2 docs commits.

> **File-history note.** This transcript was first produced (covering Phases 0–3) at the end of session day 1 (commit `f8dea9e`); Turns 5–8 were appended on day 2 as Phases 4, 5, 6, and 7 landed. The file was renamed from `2026-04-25-frontend-phases-0-to-3.md` → `2026-04-25-frontend-phases.md` once content stabilised across all seven code-producing phases — same pattern the backend transcript followed. Phase 8 of the plan is a manual two-tab demo script (procedure, not code), and remains pending against the docker-compose stack from `infra-plan.md`.

This transcript exists per Deliverable §8.6 of the requirements ("Agent chat history export — committed to the repo under `docs/ai-chat/`"). It is a faithful record of how each phase was produced, the decisions made, and the friction points encountered.

---

## Turn 1 — Frontend Phase 0 (Angular workspace bootstrap)

### User

> Start implementing frontend phase 0.

### Assistant

#### Tooling discovery (one blocker, raised before code)

A quick environment audit before scaffolding:

- **Node 16.13.1** on the system (EOL since Sept 2023)
- **Angular CLI 13.3.2** globally installed (released Nov 2021, EOL)
- No `nvm`, no `brew`-managed Node — the CLI was installed system-wide

Phase 0.1 says "Pin Angular to the latest LTS." Angular 19+ requires Node 18.19+, 20.11+, or 22.0+; the existing toolchain literally couldn't scaffold a modern workspace. I flagged this and asked which install path the user preferred:

- `brew install node@22` — single LTS Node, isolated keg
- `brew install nvm` + `nvm install 22` — multi-version

#### User direction

> Go ahead with node@22.

#### Phase 0 work

Installed Node 22.22.2 via `brew install node@22` (keg-only at `/usr/local/opt/node@22/bin`). Used `npx --yes @angular/cli@latest` to scaffold so we never depend on the EOL global `ng` again — resolved to **Angular 21.2.8** / **TypeScript 5.9** / **Vitest 4.1.5**.

| Task | Acceptance | Result |
|---|---|---|
| 0.1 | `ng serve` boots; default page renders | Dev server returns HTTP 200 on `/` |
| 0.2 | Six folders per architecture §5.1 + tsc clean | `core/`, `auth/`, `market/`, `symbol/`, `me/`, `shared/` with placeholder `index.ts` stubs |
| 0.3 | `strict: true`, `noImplicitAny`, `strictNullChecks` | **Already on by the scaffold** — `tsconfig.json` ships with `strict`, `noImplicitOverride`, `noPropertyAccessFromIndexSignature`, `noImplicitReturns`, `noFallthroughCasesInSwitch`, plus angular-compiler `strictTemplates`. No extra wiring needed. |
| 0.4 | `environment.ts` + production variant | **Collapsed to one file** — see decision below |
| 0.5 | `proxy.conf.json` for `/api` and `/ws` | `/api`, `/ws` (with `ws: true` upgrade), `/actuator` → `http://localhost:8080`; wired via `serve.options.proxyConfig` |

#### Design decision flagged mid-flight

I started writing the prod environment file:

```typescript
// environment.prod.ts
export const environment = {
  production: true,
  apiBaseUrl: '',
  wsBaseUrl: '',
} as const;
```

#### User pushback

> do we need this file?

The pushback was right. Both files would have had identical body content (same-origin in dev via `proxy.conf.json`, same-origin in prod via nginx) — only the `production: true/false` flag would differ, and Angular's built-in `isDevMode()` covers that. I recommended collapsing to one file with no `production` field, to be reinstated only when something actually diverges (e.g. a separate staging URL). The user agreed:

> yes

Final shape:

```typescript
// src/environments/environment.ts — same-origin in both modes.
export const environment = {
  apiBaseUrl: '',
  wsBaseUrl: '',
} as const;
```

#### Verification

- `ng build --configuration=production` → 213.54 KB initial (well under the 500 KB warn budget).
- `ng serve` → dev server boots on `:4200`, returns HTTP 200 on `/`.
- 28 files committed (no `node_modules/`, no `dist/`, no `.angular/`).

`4e171b4 frontend: phase 0 — Angular 21 workspace skeleton`

#### Plan deviations recorded in the commit

- Plan 0.1 says `--standalone --skip-tests=false`. **Angular 21 dropped `--standalone`** (it's the default) and ships **Vitest** instead of Karma — the test runner is configured but specs are written for Vitest, not Jasmine.
- Plan 0.4 envisioned two environment files. Collapsed to one with `isDevMode()` for dev/prod branching.

---

## Turn 2 — Frontend Phase 1 (core services)

### User

> Start implementing frontend phase 1.

### Assistant

#### Plan

Phase 1 lands the plumbing every page depends on — types, auth, HTTP, API, guard, routing. Before writing a single line, I read every backend DTO and controller so the TypeScript wire shapes mirror the Java records byte-for-byte:

| Backend artifact | Wire impact |
|---|---|
| `LoginResponse(token, userId, name)` | `LoginResponse` — UUID arrives as a JSON string |
| `SymbolResponse(symbol, name, refPrice)` | `SymbolRow` — `refPrice` is `BigDecimal` → string per architecture §9.2 |
| `BookSnapshot(symbol, bids, asks, last, cursor)` | matches verbatim |
| `BookLevel(price, qty, userCount)` | matches; `price` is string |
| `BookTotals(demand, supply)` | matches |
| `MyOrderResponse` | `MyOrder` — eleven fields including `Instant` createdAt/updatedAt as ISO strings |
| `MyFillResponse` | `MyFill` — server already derives `counterparty` username (UUIDs never leak) |
| `SubmitOrderRequest`, `SubmitOrderResponse`, `CancelOrderResponse` | matches; `idempotentReplay` flag preserved |
| `ErrorResponse(code, message, details?)` | `ErrorResponseBody` — §4.11 envelope |
| `WsFrame.snapshot/delta` | `WsEnvelope<P>` — `{ type, channel, cursor, payload }` |
| Outbox payloads (`tradeJson`, `orderJson`, `bookJson`) | `TradeEvent`, `OrderEvent`, `BookUpdateEvent` |

#### Files produced

- **`core/models.ts`** — every typed wire shape; BigDecimals kept as `string` per architecture §9.2.
- **`core/auth.service.ts`** — `signal`-backed `currentUser` / `isAuthenticated`. `sessionStorage` persistence (architecture §5.5: limits XSS exposure vs `localStorage`). JWT `exp` decoded best-effort (unverified — server already verifies). Auto-logout via `setTimeout` scheduled on every login, cleared on every logout. Tampered or already-expired tokens are rejected at login time so the UI never appears authenticated against a dead token.
- **`core/http.interceptor.ts`** — functional interceptor (Angular 21 style). Attaches `Authorization: Bearer <token>` except on the login call itself (no token loop). On 401 from non-login calls, calls `auth.logout()` (which navigates to `/login`) and re-throws so RxJS callers still get the error. Bonus helper `errorMessageOf(err, fallback)` pulls the §4.11 envelope's `message` field, falling back through `statusText` → caller-supplied fallback.
- **`core/api.service.ts`** — typed surface in front of `HttpClient`. `submitOrder` mints a UUIDv7 `clientOrderId` by default (via the `uuidv7` package, ~1 KB) and accepts an explicit `clientOrderId` for the **idempotent retry path** — architecture §4.6 explicitly leans on this, and the test suite asserts both branches.
- **`core/auth.guard.ts`** — functional `CanActivateFn`. Returns `true` when authed; otherwise a `UrlTree` to `/login?returnUrl=…` with the original URL preserved verbatim (encoded query params and all).
- **`app.routes.ts`** — four lazy routes per architecture §5.2; placeholder page components stand in until Phases 2/4/5/6 land the real screens.
- **`app.config.ts`** — wires `provideHttpClient(withInterceptors([authInterceptor]))`.

#### Tests (26 green via Vitest)

| Suite | Cases |
|---|---|
| `auth.service.spec.ts` | login persists session; tampered JWT rejected; expired-on-arrival JWT rejected; auto-logout when timer fires; manual logout clears storage and navigates; stale session in storage dropped on rehydrate |
| `http.interceptor.spec.ts` | Bearer attached; login call skipped; no-token case; 401 → logout for non-login calls; 401 from login (bad creds) does **not** logout; `errorMessageOf` happy + fallback paths |
| `api.service.spec.ts` | one test per method (login, getSymbols, getBook, getTotals, submitOrder auto-id, submitOrder explicit-id retry path, cancelOrder, getMyOrders, getMyFills) — UUIDv7 shape asserted via regex `/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/` |
| `auth.guard.spec.ts` | true when authed; UrlTree with returnUrl when not; nested URL preserved verbatim |

#### Friction points

1. **Lazy route imports break TS until target files exist** — the routes pointed at `./auth/login.page`, `./market/overview.page`, etc. With those files missing the compiler errored on `Cannot find module`. → Created minimal placeholder components (one-line templates) so the compiler is happy now and Phases 2/4/5/6 can swap them out.
2. **The scaffold's `app.html` ships a 344-line placeholder** ("Hello, frontend"), not just a `<router-outlet />`. Replaced with the outlet only; updated `app.spec.ts` to assert the outlet anchor exists rather than the placeholder text.
3. **`HttpErrorResponse` defaults `statusText` to `'Unknown Error'`** when constructed with empty `statusText` — my speculative "fallback when statusText empty too" test never fired in practice. → Dropped the test rather than chase a magic string.
4. **`Router` import was unused after that test deletion** — TS hint reported it; removed.

#### Verification

- All 26 unit tests pass.
- `ng build --configuration=production` → 209.90 KB initial + 4 lazy chunks (~1.5 KB total) for the page placeholders.

`64880d0 frontend: phase 1 — core services (models, auth, http, api, guard, routes)`

---

## Turn 3 — Frontend Phase 2 (login page + authenticated layout shell)

### User

> Start implementing frontend phase 2.

### Assistant

#### Spec inputs

The §6.1 wireframe in `limit-order-requirements.md` is unambiguous:

```
+----------------------------------------+
|          Limit Order App               |
+----------------------------------------+
|                                        |
|   Username: [_______________]          |
|   Password: [_______________]          |
|                                        |
|          [ Log in ]                    |
+----------------------------------------+
```

The §6.2 header — `Limit Order App                          Alice | [Log out]` — applies to every authenticated page. Plan 7.3 polishes its styling later, but plan 2.4 already requires a working logout button now. I built the structure today and left the polish for Phase 7.

#### Files produced

- **`auth/login.page.ts`** — reactive form, two `Validators.required` fields, single submit. State held in two component-local signals (`pending`, `errorMessage`), so the template stays template-driven without RxJS plumbing.
  - **401 → "Wrong username or password"** verbatim. The backend deliberately uses a unified failure string for both unknown-user and bad-password to prevent username enumeration; we surface a stable client-side message rather than echoing the envelope text so the UX is consistent regardless of how the backend phrases it.
  - **Other errors → `errorMessageOf()`** so the §4.11 envelope's `message` field is shown inline.
  - **Submit button disabled while pending** to block double-submits.
  - **`returnUrl` resolution**: read from query params (auth guard threads it in); defaults to `/`. **Refuses to bounce back to `/login`** to prevent redirect loops.
- **`shared/layout.component.ts`** — header (`Limit Order App | currentUser.name | [Log out]`) hosting `<router-outlet />`. Logout button calls `auth.logout()`. The user-controls block hides defensively when `currentUser()` is null (a brief flash during logout transitions).
- **`app.routes.ts`** — restructured. `/login` is top-level (no chrome). The three authed routes (`/`, `/symbol/:symbol`, `/me`) become **children of a layout-parent route** so the header renders once. The `authGuard` is hoisted to the parent — its `returnUrl` threads through both layers.

#### Tests (13 new, 39 total green)

| Suite | Cases |
|---|---|
| `login.page.spec.ts` | renders the §6.1 wireframe; submit disabled when invalid; submit calls AuthService with form values; success without returnUrl → `/`; success with returnUrl → that URL; success with `?returnUrl=/login` → `/` (no loop); 401 → "Wrong username or password"; non-401 → §4.11 envelope text; button disabled + "Logging in…" while pending |
| `layout.component.spec.ts` | brand + name + logout render; user-controls hidden when no user; logout click calls AuthService; hosts router-outlet |

#### Friction points

1. **`git commit -m "$(cat <<EOF ... EOF)"` failed with "unexpected EOF"** — the heredoc body had nested parentheses inside the quoted string and bash got confused about quoting. → Wrote the message to `/tmp/phase2-msg.txt` and used `git commit -F /tmp/phase2-msg.txt`. Same outcome, simpler quoting.

#### Verification

- All 39 unit tests pass.
- `ng build --configuration=production` → 232.85 KB initial + login lazy chunk 35.44 KB (forms ship there).
- Dev-server smoke: `/`, `/login`, `/symbol/AAPL`, `/me` all return HTTP 200; `/api/symbols` proxy reaches out (gets 500 because backend's down — proves wiring).

`cb4e59a frontend: phase 2 — login page + authenticated layout shell`

#### Caveat surfaced to the user

> per the system instruction, frontend changes should be browser-tested. I can't drive a browser from this environment — unit tests + curl smoke are the strongest signals available locally. Recommend booting `cd frontend && npx ng serve` alongside the running backend (`cd backend && ./mvnw spring-boot:run`) and trying the login flow end-to-end with a seed user before moving on.

---

## Turn 4 — Frontend Phase 3 (WebSocket service)

### User

> Start implementing frontend phase 3.

### Assistant

#### Design decision flagged before coding

The plan's literal signature is:

```typescript
subscribeBook(symbol: string): Observable<BookSnapshot>
```

But the backend's `/ws/book/{symbol}` channel produces three different payloads:

| Frame | Payload | Source |
|---|---|---|
| First frame on connect | `BookSnapshot` (full top-5 + cursor) | `BookWsHandler#afterConnectionEstablished` |
| `book:{symbol}` deltas | `{event: 'BOOK_UPDATE', symbol}` — **no level data** | `MatchingEngineService#bookJson` |
| `trades:{symbol}` deltas | `{event: 'TRADE', tradeId, price, qty}` | `MatchingEngineService#tradeJson` |

A strict `Observable<BookSnapshot>` contract would force the service to **REST-refetch via `/api/book/{symbol}` on every `BOOK_UPDATE`** to produce a new snapshot — an architectural choice that bloats Phase 3's surface and double-fetches under contention. Instead, I emit a typed discriminated union and let the consuming pages (Phases 4/5/6) build state from snapshot + deltas:

```typescript
type BookStreamEvent =
  | { kind: 'snapshot'; data: BookSnapshot }
  | { kind: 'delta';    data: BookUpdateEvent | TradeEvent };

type OrdersStreamEvent =
  | { kind: 'snapshot'; data: readonly MyOrder[] }
  | { kind: 'delta';    data: OrderEvent };
```

I called this out explicitly in the user-facing message before writing code so the deviation is visible in review.

#### Files produced

- **`core/ws.service.ts`** — single multiplexer at `~250 lines`.
  - One physical `WebSocket` per channel pattern; consumers share via refcount on a per-channel `Channel<E>` record.
  - **JWT in URL**: `?token=<encodeURIComponent>` query param, matching the backend's `JwtHandshakeInterceptor` Bearer-or-query fallback.
  - **Cursor floor** per channel — first `snapshot` frame sets it, deltas with `cursor <= floor` are dropped (architecture §4.8). After a fresh reconnect the floor is reset by the next snapshot, so consumers see a `'snapshot'` event and replace state.
  - **Reconnect** with exponential backoff via `backoffMs(attempt)`: 250 ms → 5 s, ±20 % jitter, doubling per attempt, capped. Reconnect aborts when `auth.isAuthenticated` is false.
  - **Auth integration** via `effect()`: when the AuthService's signal flips false (which its own JWT-expiry timer triggers autonomously), every open socket is closed deliberately and any pending reconnect timer is cleared. Plan §3.4 satisfied.
  - **Test seam**: `bookSubscriberCount(symbol)` exposed for the refcount assertion.
- **`core/models.ts`** — added the two stream-event unions described above.

#### Tests (12 new, 51 total green)

| Plan task | Cases in `ws.service.spec.ts` |
|---|---|
| 3.1 snapshot-then-deltas | snapshot then deltas, cursor-drop both `==` and `<` floor, malformed-frame skip, orders parse |
| 3.3 refcount | one socket shared across consumers; close on last unsub; per-symbol isolation (separate sockets) |
| 3.2 reconnect | reconnect after unexpected close; fresh snapshot resets the cursor floor (delta below the old floor passes through after reconnect); no-reconnect after a deliberate close |
| 3.4 auth integration | no socket opened when unauthed; close-all on auth flipping false; abort pending reconnect on auth flipping false |
| Backoff bounds | attempt 0 ∈ [200, 300] ms; cap at attempt 8 ∈ [4000, 6000] ms |

The fake `WebSocket` is implemented as a constructor that pushes a handle into a module-level `fakes[]` array on each instantiation; tests drive `emit()` and `closeFromServer()` against the handle. Real `WebSocket` is restored in `afterEach`.

#### Friction points

1. **Angular signal `effect()` flush timing** — the `closes every open socket when auth flips false` test failed initially because `await Promise.resolve()` doesn't drain Angular's effect queue. → Used `TestBed.tick()` (Angular 18+) right after `auth.authed.set(false)` to flush effects deterministically.

#### Verification

- All 51 unit tests pass.
- `ng build --configuration=production` → 232.85 KB initial (unchanged — `WsService` tree-shaken until Phase 4 imports it).

`6bead40 frontend: phase 3 — ws service (snapshot/deltas, refcount, reconnect, auth)`

#### Caveat surfaced to the user

> as in Phase 2, real browser+backend smoke is the strongest signal for WS plumbing. The unit tests stub `WebSocket` with a fake; they don't catch issues with nginx upgrade headers, dev-proxy `ws: true`, or actual JWT handshake. Worth a quick `docker compose up` (once Phase 8 infra lands) or running `./mvnw spring-boot:run` + `npx ng serve` and watching DevTools for `/ws/book/AAPL` to upgrade.

---

## Turn 5 — Frontend Phase 4 (Market Overview page)

### User

> Start implementing frontend phase 4.

### Assistant

#### Design decision flagged before coding

The §6.2 wireframe needs `Demand` and `Supply` columns to stay live. The backend's `BOOK_UPDATE` delta carries no level data (just `{event, symbol}`), so to keep those columns fresh after a fill the page has to **REST-refetch `/api/book/{symbol}` on every tickle**. That was the architectural seam I flagged at the end of Phase 3.

For 5 symbols at demo scale the refetch chatter is negligible — I went with unthrottled refetches and noted the throttling option (e.g. `switchMap` + `debounceTime`) as a Phase 7 polish if it ever matters.

#### Files produced

- **`market/overview.page.ts`** — §6.2 table rendered from a `MarketRow[]` signal. On init fetches `/api/symbols`, then opens a `WsService.subscribeBook(symbol)` stream per symbol and dispatches:
  - **snapshot** → replace `bids`/`asks`/`last`, mark row `'live'`
  - **TRADE delta** → patch `last` directly from the trade payload (no REST round-trip)
  - **BOOK_UPDATE delta** → REST refetch `/api/book/{symbol}` so `Demand`/`Supply` stay current
- Demand/Supply summed across the visible top-5 levels; `'-'` shown for missing prices (matches the AMZN-style empty row in the wireframe).
- Connection indicator flips `Connecting…` → `Live` once every row has produced its first snapshot.
- "Open" → `RouterLink` to `/symbol/{symbol}`.
- `takeUntilDestroyed` on every observable so leaving the page closes WS subscriptions (Phase 3 refcount drops them, socket closes).

#### Tests (9 new, 60 total green)

| Plan task | Cases in `overview.page.spec.ts` |
|---|---|
| 4.1 init flow | column header order; row-per-symbol; per-symbol `subscribeBook` call |
| 4.2 demand/supply | dashes-for-empty (AMZN-style); level summation matching the §6.2 numeric values |
| 4.1 deltas | TRADE doesn't refetch; BOOK_UPDATE does refetch and updates the row |
| 4.4 navigation | Open `href` is `/symbol/{symbol}` |
| 4.3 indicator | state machine `Connecting → Live` across symbols |

`FakeWs` exposes per-symbol `Subject<BookStreamEvent>` instances so tests push events directly without simulating a real WebSocket.

#### Verification

- All 60 unit tests pass.
- `ng build --configuration=production` → 240.12 KB initial; overview lazy chunk 12.51 KB.

`21deda9 frontend: phase 4 — market overview page (live, indicator, Open links)`

#### Caveat surfaced to the user

> the §6.2 acceptance is "values exactly". I've asserted the values via `data-symbol="AAPL"` row inspection, but that doesn't catch nginx upgrade-header issues or §5.4-style cross-tab consistency. Worth a `./mvnw spring-boot:run` + `npx ng serve`, login as `u1`, and confirm the seed values render exactly per the §5.4 snapshot before moving on.

---

## Turn 6 — Frontend Phase 5 (Symbol Detail page + place-order form)

### User

> Start implementing frontend phase 5.

### Assistant

#### Spec inputs

The §6.3 wireframe is the most interactive page: a header (`AAPL — Apple Inc.` + `[< Back]`), two side-by-side Qty/Price/Users tables (BIDS / ASKS), totals beneath, and a Place Order form with Side/Type radios, Price (disabled when MARKET), Quantity, Submit.

#### Files produced (four artifacts in one phase)

- **`shared/toast.service.ts`** — minimal global toast (signal-based, success/error kinds, auto-clear after 3 s, supersede semantics for newer `show()` calls). Rendered fixed-position by `LayoutComponent` above every authed page so login + place-order + cancel can share it. Phase 7.1 polishes; the contract here is what every page can rely on now.
- **`symbol/order-book.component.ts`** — pure presentation; signal inputs `bids` / `asks`. Padded to five rows per side so the layout doesn't shift when fills pop levels off the top, capped at five so passing more is a no-op.
- **`symbol/place-order.form.ts`** — §6.3 reactive form. Side/Type radios; Price control disabled and cleared on MARKET, re-enabled on LIMIT. UUIDv7 `clientOrderId` minted per attempt and **reused on 5xx / network errors** for idempotent retry (architecture §4.6); **dropped on 4xx** so a fix-the-form retry submits with a fresh id. Submit disabled while pending (browser-level double-click defense). Success → `'Order placed'` toast + form reset (price + qty); rejection → §4.11 envelope message inline + error toast.
- **`symbol/symbol-detail.page.ts`** — composes the two. Reads `:symbol` param; subscribes to `subscribeBook()`; fetches initial `getTotals()`. snapshot → replace levels+last; TRADE → patch last; BOOK_UPDATE → refetch BOTH `getBook` (top-5 levels) and `getTotals` (full-book Demand/Supply). `[< Back]` is a `RouterLink` to `/`.

#### Bug caught at test time — BigDecimal trailing zero

The test `submit posts the form values plus a UUIDv7 clientOrderId` failed first run:

```
AssertionError: expected 180.5 to be '180.50'
- Expected: "180.50"
+ Received: 180.5
```

`<input type="number">` coerces `"180.50"` → `180.5`, dropping the trailing zero and breaking the **BigDecimal-on-the-wire** contract per architecture §9.2. Fix:

```html
<!-- type=text (not type=number) to preserve trailing zeros: a number
     input would coerce "180.50" → 180.5 and break the BigDecimal-on-
     the-wire contract (architecture §9.2). -->
<input type="text" inputmode="decimal" formControlName="price" />
```

`type="text"` with `inputmode="decimal"` keeps the soft keyboard numeric on mobile while preserving exact precision client-side.

#### Tests (24 new, 84 total green)

| Suite | Cases |
|---|---|
| `toast.service.spec.ts` | default kind=success; error kind; auto-clear after duration; newer show supersedes older + resets timer; `dismiss` clears immediately |
| `order-book.component.spec.ts` | 5 rows per side with empty padding; §6.3 wireframe values render; cap at top-5 when more passed |
| `place-order.form.spec.ts` | wireframe field set; price disabled on MARKET / re-enabled on LIMIT; submit disabled until valid; UUIDv7 clientOrderId; MARKET → price=null; success toasts + fresh id next time; transient 5xx **reuses** id on retry; 4xx **drops** id; envelope message inline; pending-state button disable |
| `symbol-detail.page.spec.ts` | header + Back + book + totals + form composition; subscribeBook(:symbol) + getTotals on init; snapshot populates book + last (matches §6.3 first bid row); TRADE delta updates last in place; BOOK_UPDATE refetches both `getBook` and `getTotals`; full-book Demand/Supply from getTotals |

#### Verification

- All 84 unit tests pass (BigDecimal regression caught at test time and fixed before commit).
- `ng build --configuration=production` → 240.12 KB initial; symbol-detail lazy chunk 8.06 KB.

`b9e320d frontend: phase 5 — symbol detail (order book + place-order form)`

#### Caveats surfaced to the user

> Plan 5.4 acceptance is *"double-click submit, only one order appears in My Orders"*. The unit test asserts the button-disabled-while-pending behaviour, but only a real two-click in a browser confirms no race between click handlers.
>
> Plan 5.6 acceptance is *"the eventual fill arrives via the WS stream and updates the book/My Orders without page action"* — that requires a running backend with the outbox listener live, plus an open `/ws/book/AAPL`. The unit tests fake the WS layer; only `./mvnw spring-boot:run` + `npx ng serve` proves the end-to-end fan-out.

---

## Turn 7 — Frontend Phase 6 (My Orders + My Fills)

### User

> Start implementing frontend phase 6.

### Assistant

#### Design decision flagged before coding

Plan 6.2 says *"initial load via `getMyOrders`, then live updates via `subscribeOrders()`"*. With pure REST-first there's a tiny race window:

- t0: REST call goes out
- t1: REST response arrives (data as of t0)
- t1+ε: WS subscription opens
- t2: WS snapshot arrives at cursor C
- t1.5: a fill happens; its delta has cursor < C

Phase 3's `WsService` drops deltas with `cursor <= snapshotCursor` (the architecture §4.8 race fix). So that t1.5 delta — which the WS snapshot at t2 *did* include — is silently discarded if we only trust the REST list.

Cleanest fix: REST paints fast (good UX), then **the WS snapshot replaces the list when it arrives** (cursor-tagged authoritative), then deltas merge. Same effective contract as the plan, race-free. Flagged this to the user before writing code.

I also noted that plan 6.3 referred to `TRADE_EXECUTED` events on the orders WS, but the actual backend channel emits `ORDER` events only. Implementation reacts to ORDER deltas with status `PARTIAL` or `FILLED` — those imply a trade touched our orders.

#### Files produced

- **`shared/format.ts`** — minimal helpers (`formatTimestamp(iso)` → `'yyyy-MM-dd HH:mm:ss'` defensive, `shortId(id)` → first 8 chars).
- **`me/my-orders.page.ts`** — §6.4 top-half table.
  - REST first paint via `getMyOrders`, then WS snapshot replaces it (per the design call above).
  - ORDER deltas merge `status` + `filledQty` in place via an orderId-keyed `Map`.
  - `[X]` cancel button rendered only on OPEN/PARTIAL rows; second click while pending is a no-op; cancel error toasts the §4.11 envelope message; success stays silent (the WS-driven CANCELLED flip is feedback enough).
  - Newest-first sort by `createdAt`. Empty: "No open orders".
- **`me/my-fills.page.ts`** — §6.4 bottom-half table.
  - Initial load via `getMyFills`; refetches when an ORDER delta lands with status `PARTIAL` or `FILLED`.
  - `CANCELLED` / `REJECTED` / `OPEN` deltas do *not* refetch.
  - Newest-first sort by `executedAt`. Empty: "No fills yet".
- **`me/my-account.page.ts`** — composes both halves; refcount on `/ws/orders/mine` keeps a single socket open across both child components.

#### Tests (23 new, 107 total green)

| Suite | Cases |
|---|---|
| `format.spec.ts` | timestamp `null/undefined/empty → ""`; ISO → `yyyy-MM-dd HH:mm:ss`; non-parseable returned untouched; `shortId` 8-char truncation |
| `my-orders.page.spec.ts` | empty state; column order matches §6.4; MARKET → `'-'` price; cancel button visible only on OPEN/PARTIAL; click calls cancel + disables button + second click no-op; 4.11 envelope toasted on cancel error; ORDER delta updates row; WS snapshot replaces REST-painted list; newest-first sort |
| `my-fills.page.spec.ts` | empty state; column order; refetch on FILLED; refetch on PARTIAL; **no** refetch on CANCELLED/REJECTED/OPEN; newest-first sort |
| `my-account.page.spec.ts` | renders both halves; both halves trigger their own initial REST loads; both halves subscribe to `subscribeOrders()` (refcount handles sharing) |

#### Verification

- All 107 unit tests pass.
- `ng build --configuration=production` → 240.12 KB initial; my-account lazy chunk 6.17 KB.

`0bbd2e3 frontend: phase 6 — my orders + my fills (live, cancellable, sorted)`

#### Caveat surfaced to the user

> plan 6.3 acceptance is the **two-tab cross-tab test** (Alice + Bob trade, both see the new fill within 1 s). That's the §3 NFR — only verifiable with a live backend (`./mvnw spring-boot:run`) and the multi-instance compose stack (Phase 8 infra). The unit tests assert refetch-on-correct-event but can't measure wall-clock latency or true cross-tab/instance behaviour.

---

## Turn 8 — Frontend Phase 7 (cross-cutting polish)

### User

> Start implementing frontend phase 7.

### Assistant

#### Audit — what's already done vs what needs work

Most of Phase 7 was already in place from earlier phases:

| Plan task | Status going in |
|---|---|
| 7.1 Toast service | Built in Phase 5; place-order + cancel already use it. Login still uses inline error. |
| 7.2 Loading skeletons | Overview has empty-state text; my-orders / my-fills don't distinguish "loading" from "empty". |
| 7.3 Page layout (header, current user, logout) | Done in Phase 2. ✅ |
| 7.4 Format helpers | Phase 6 added `formatTimestamp` + `shortId`. Missing: `formatPrice`, `formatQty`. |
| 7.5 a11y (labels, tab order, ESC closes toasts) | Forms have `<label>` wrappers; tab order is DOM order. Missing: ESC-dismiss. |

So the actual Phase 7 work was: format helpers + apply consistently, loading state on `me/*`, ESC-dismiss, plus a welcome toast on login (plan §7.1 explicitly lists login as a toast user).

#### Plan deviation flagged

Plan §7.4 said **4dp** for prices, but every §6.x wireframe shows 2dp values and the seed data is 2dp-precise. The cleanest correct choice is to **trust the backend's exact precision** — round-trips losslessly and matches the wireframe verbatim. `formatPrice` returns the input string unchanged (with `'-'` for null/empty); never reparses through `Number`, which would silently drop trailing zeros (the BigDecimal regression we fixed in Phase 5). Documented in the function's doc comment.

#### Files touched

- **`shared/format.ts`** — added `formatPrice` and `formatQty` (thousands separator). Both return `'-'` for null/undefined.
- **All pages** that display prices or quantities — overview, symbol-detail, order-book, my-orders, my-fills — now route values through `formatPrice` / `formatQty` consistently.
- **`shared/layout.component.ts`** — added `@HostListener('document:keydown.escape')` that dismisses any visible toast (plan §7.5 a11y). No-op when no toast.
- **`auth/login.page.ts`** — success path fires `'Welcome, <name>'` toast (plan §7.1: toast service used by login). Inline error from Phase 2 stays put for blocking-error UX.
- **`me/my-orders.page.ts`** + **`me/my-fills.page.ts`** — added a `loaded` signal that flips true on the first REST/WS response. Empty-state row reads `Loading…` before that and `No open orders` / `No fills yet` after, distinguishing slow-network from genuinely-empty accounts.

#### Tests (11 new, 118 total green)

| Suite | New cases |
|---|---|
| `format.spec.ts` | `formatPrice` `'-'` for null/empty + verbatim preservation (180.50, 0.0001, 180); `formatQty` `'-'` for null + zero/small ints + thousands separators (1,000 / 100,000 / 1,234,567) |
| `layout.component.spec.ts` | toast renders inside chrome with kind class; ESC dismisses visible toast; ESC is a no-op when no toast |
| `login.page.spec.ts` | success fires `'Welcome, <name>'` toast |
| `my-orders.page.spec.ts` | `Loading…` while pending vs `No open orders` after empty response |
| `my-fills.page.spec.ts` | `Loading…` while pending vs `No fills yet` after empty response |

#### Verification

- All 118 unit tests pass.
- `ng build --configuration=production` → 240 KB initial; lazy chunks unchanged (~6–8 KB each).

`e8ed30a frontend: phase 7 — cross-cutting polish (format, loading, a11y, login toast)`

---

## Appendix A — Final commit log (this session)

```
e8ed30a frontend: phase 7 — cross-cutting polish (format, loading, a11y, login toast)
0bbd2e3 frontend: phase 6 — my orders + my fills (live, cancellable, sorted)
b9e320d frontend: phase 5 — symbol detail (order book + place-order form)
21deda9 frontend: phase 4 — market overview page (live, indicator, Open links)
f8dea9e docs: add 2026-04-25 frontend implementation transcript (phases 0–3)
6bead40 frontend: phase 3 — ws service (snapshot/deltas, refcount, reconnect, auth)
cb4e59a frontend: phase 2 — login page + authenticated layout shell
64880d0 frontend: phase 1 — core services (models, auth, http, api, guard, routes)
4e171b4 frontend: phase 0 — Angular 21 workspace skeleton
```

Eight atomic frontend commits — one per phase — plus the day-1 transcript landing. No fix-ups along the way. (`.claude/settings.local.json` left unstaged each time; that's an IDE-local artifact, not project state.)

## Appendix B — Test coverage by phase

| Phase | Suite | Cases | Cumulative |
|---|---|---:|---:|
| 0 | scaffold `app.spec.ts` (rewritten to assert router-outlet) | 1 | 1 |
| 1 | `auth.service.spec.ts` | 6 | 7 |
| 1 | `http.interceptor.spec.ts` | 7 | 14 |
| 1 | `api.service.spec.ts` | 9 | 23 |
| 1 | `auth.guard.spec.ts` | 3 | 26 |
| 2 | `login.page.spec.ts` (initial set, extended in 7) | 9 → 10 | 35 |
| 2 | `layout.component.spec.ts` (initial set, extended in 7) | 4 → 7 | 39 |
| 3 | `ws.service.spec.ts` | 12 | 51 |
| 4 | `overview.page.spec.ts` | 9 | 60 |
| 5 | `toast.service.spec.ts` | 5 | 65 |
| 5 | `order-book.component.spec.ts` | 3 | 68 |
| 5 | `place-order.form.spec.ts` | 10 | 78 |
| 5 | `symbol-detail.page.spec.ts` | 6 | 84 |
| 6 | `format.spec.ts` (extended in 7) | 4 → 9 | 107 |
| 6 | `my-orders.page.spec.ts` (extended in 7) | 9 → 10 | 107 |
| 6 | `my-fills.page.spec.ts` (extended in 7) | 6 → 7 | 107 |
| 6 | `my-account.page.spec.ts` | 3 | 107 |
| 7 | (cross-cutting — counts rolled into the suites above) | +11 | **118** |

`ng test --watch=false` → **118/118 passing** in ~4 s.

## Appendix C — Architectural decisions captured

| Decision | Rationale | Phase |
|---|---|---|
| Angular 21 (latest LTS major) | `npx --yes @angular/cli@latest` resolves to 21.2.8 in Apr 2026; supersedes plan §0.1 wording about latest LTS | 0 |
| Vitest as the test runner | Angular 21 ships `@angular/build:unit-test` with Vitest by default — Karma is gone | 0 |
| Standalone components only | Default in Angular 17+; `--standalone` flag removed in 21. NgModules unused | 0 |
| One `environment.ts`, no prod variant | Same-origin in both modes; `isDevMode()` covers branching | 0 |
| Dev proxy with `ws: true` for `/ws` | Same-origin in dev (no CORS); upgrade header forwarded for WebSocket | 0 |
| `sessionStorage` for JWT | Cleared on tab close; limits XSS blast radius vs `localStorage` (architecture §9.4 / plan 1.2) | 1 |
| Best-effort JWT decode in the browser | Server already verifies the signature; client only needs `exp` and `name` for UX | 1 |
| Reject expired/tampered tokens at login persist | Don't authenticate against a token we'd immediately expire | 1 |
| BigDecimal as `string` everywhere | Architecture §9.2 — no `Number` precision loss | 1 |
| Functional interceptors and guards | Angular 21 idiom; cleaner than the class-based `HTTP_INTERCEPTORS` array | 1 |
| `errorMessageOf(err)` helper in `http.interceptor.ts` | Single source of truth for §4.11 envelope unwrapping | 1 |
| UUIDv7 via `uuidv7` package | Time-ordered ids help debugging; ~1 KB lib (plan §5.5 explicitly suggests) | 1 |
| Skip Bearer attach on `/api/auth/login` | Avoids token-loop semantics; matches backend's permitAll for that path | 1 |
| 401 from login is **not** a logout trigger | "Bad credentials" is local; `auth.logout()` would clear nothing useful | 1 |
| Layout shell vs duplicating the header on each page | One component, one template — Phase 7 polishes once | 2 |
| Stable client-side text for 401 | Backend's unified message is intentional anti-enumeration; UX shows a fixed "Wrong username or password" | 2 |
| `returnUrl === '/login*'` → `/` | Defends against redirect loops if the auth guard fires on the login page itself | 2 |
| Pending-state flag held in a signal | Simpler than RxJS for component-local state; `disabled="pending() \|\| invalid"` is one line | 2 |
| Layout-parent route hosts the guard | `authGuard` evaluated once at the layout boundary; child routes inherit. `returnUrl` is the parent's URL on first visit | 2 |
| `BookStreamEvent` / `OrdersStreamEvent` discriminated unions | The backend's BOOK_UPDATE delta carries no level data → strict `Observable<BookSnapshot>` would force REST refetches; the union is type-honest and same-effective-contract (plan §3.1 deviation flagged) | 3 |
| One `WebSocket` per channel pattern, refcounted | Architecture §5.4. Multiple `<order-book>` components on a page share the socket; close on last unsub | 3 |
| JWT as `?token=` query param | Backend's `JwtHandshakeInterceptor` accepts query-param fallback (architecture §4.9) — browsers don't expose Bearer header on the WS upgrade | 3 |
| Cursor floor reset on each fresh snapshot | Lets a reconnect whose new cursor is below the OLD floor pass through correctly — Plan §3.2 wording about "discard old state" | 3 |
| `effect()` watching `auth.isAuthenticated` | When AuthService's own JWT-expiry timer fires, the effect cascades to close all sockets — single source of truth for auth state | 3 |
| `NgZone.run(...)` around `onmessage` / `onclose` | Keeps signal updates inside Angular's change detection — components subscribed to the stream's signals re-render on emission | 3 |
| Unthrottled REST refetch on BOOK_UPDATE | Backend's tickle carries no level data; refetching `/api/book/{symbol}` is the only correctness-preserving option. Cheap at 5-symbol demo scale; throttling left as a Phase 7 polish that wasn't needed | 4 |
| `data-symbol="…"` test attribute on overview rows | Cleaner per-row assertions in component specs without hooking into Angular's internals. Unobtrusive in prod | 4 |
| Loading indicator computed from `every-row-snapshotted` | Avoids extending `WsService` with per-channel state observable; simplest signal-only derivation | 4 |
| One physical socket per symbol (vs single socket multiplexing all symbols) | Matches plan §4.1 wording and architecture §5.4. WsService refcount keeps it cheap; trade-off: 5 sockets vs 1 frame-routed socket | 4 |
| Toast service shipped in Phase 5 (not Phase 7) | Phase 5.6 explicitly needed it ("Toast on successful submit"). Building it once and reusing in 6/7 is cheaper than two passes | 5 |
| `LayoutComponent` hosts the toast (not a separate `<app-toast>`) | Toast lives above all authed routes; layout already wraps them. One less component, one less template | 5 |
| Place-order Price field is `<input type="text" inputmode="decimal">`, not `type="number"` | `type="number"` coerces `"180.50"` → `180.5`, breaking BigDecimal-on-the-wire (§9.2). `inputmode="decimal"` keeps soft-keyboard numeric on mobile | 5 |
| `clientOrderId` reuse on 5xx, drop on 4xx | Matches the architecture §4.6 idempotency contract precisely: a transient retry with the same id deduplicates server-side; a corrected-form retry should land as a fresh attempt | 5 |
| Form sticky on side/type, reset price/qty on success | Common multi-order-flow ergonomic — user stays in the same trading mode but re-enters the variable inputs | 5 |
| Symbol Detail reads `:symbol` once via `paramMap.get` (snapshot, not observable) | Route never changes within a single Symbol Detail mount; switching symbols navigates, which destroys + recreates the component. Observable subscription would be over-engineered | 5 |
| Optimistic update on cancel: NO | Plan 6.2 acceptance is "row updates to CANCELLED via WS within 1s" — we wait for the WS to confirm. Simpler + matches the architecture §4.5 model | 6 |
| WS snapshot frame replaces REST-painted list (rather than merging) | Closes the race where a delta with `cursor <= snapshotCursor` would be silently dropped between REST and WS subscribe. WS snapshot is cursor-tagged authoritative | 6 |
| My-fills refetches on `PARTIAL`/`FILLED` ORDER deltas (not on every delta) | Plan §6.3 explicitly accepts the extra REST call as simpler than maintaining a parallel fills list. Filtering by status keeps the chatter to actual fills | 6 |
| `loaded` signal on data-fetching pages | Distinguishes "loading slowly" from "loaded but empty" in the empty-state row — better UX than a single empty-state message that's ambiguous | 7 |
| `formatPrice` preserves the backend's exact BigDecimal string (vs reformatting to fixed dp) | Plan §7.4 said "4dp" but every §6.x wireframe shows 2dp values and seed data is 2dp. Trusting the backend round-trips losslessly and matches the wireframe | 7 |
| `formatQty` uses `toLocaleString('en-US')` (vs hand-rolled separator) | One line, no edge-case bugs, locale-stable in the en-US locale we render to | 7 |
| ESC dismisses toast via `@HostListener('document:keydown.escape')` on layout | Layout is the only authed-page-rooting component; one host listener covers every page without a separate global key handler service | 7 |

## Appendix D — Deferred items (worth knowing in a deep-dive Q&A)

| Deferred | Why | Owner phase |
|---|---|---|
| Real browser smoke (login, WS upgrade, cross-tab demo) | I can't drive a browser from this environment; unit tests + curl smoke are the strongest local signals. Plan §8 (manual two-tab demo script) is the sign-off bar | Phase 8 / manual |
| Cross-node 1 s NFR verification | Plan 6.3 acceptance is the two-tab Alice+Bob trade test. Requires the multi-instance compose stack (`infra-plan.md`) running and active backend cross-node fan-out | Infra phase + manual |
| `/actuator/prometheus` end-to-end | Backend transcript flagged this — endpoint registration is broken in the current Spring Boot 3.3.5 / Prometheus 1.x combo. Frontend doesn't talk to `/actuator` directly so nothing to do here | Infra phase / backend follow-up |
| `app.config.ts` zone-based change detection vs zoneless | Default Angular 21 scaffold uses `provideBrowserGlobalErrorListeners` and zone-based CD; zoneless is opt-in. WS service's `NgZone.run()` reflects the current choice | Future / scale work |
| BOOK_UPDATE refetch throttling | Currently unthrottled — fine at 5-symbol demo scale. Under genuine load, switch to `switchMap` with `debounceTime` or have the backend emit full level deltas | Future / scale work |
| Optimistic UI updates on cancel | Currently waits for WS-driven CANCELLED flip (matches plan 6.2 acceptance). Could optimistically gray-out the row immediately for snappier UX | Future / polish |
| Trades-as-a-stream observable | Backend's `/ws/book/{symbol}` carries trades on the same socket; Phase 5 surfaces them via `BookStreamEvent` deltas. A dedicated `subscribeTrades(symbol)` would be additive (e.g. for a "recent trades" widget) | Future / new feature |
| `app.spec.ts` placeholder | Rewritten in Phase 1 to assert `router-outlet`; could be retired entirely now that all pages have their own specs | Optional cleanup |
| ~~Toast service~~ | **Resolved in Phase 5** | — |
| ~~`format.ts` shared helpers~~ | **Resolved in Phases 6 + 7** (`formatTimestamp`, `shortId`, `formatPrice`, `formatQty`) | — |
| ~~Loading skeletons~~ | **Resolved in Phase 7** (loaded-signal pattern on my-orders + my-fills; overview already used empty-state idiom) | — |
| ~~ESC closes toasts~~ | **Resolved in Phase 7** | — |
| ~~Welcome toast on login~~ | **Resolved in Phase 7** | — |

## Appendix E — Friction points encountered (and resolutions)

1. **Node 16 + Angular CLI 13 globally installed** — both EOL, can't scaffold modern Angular. → `brew install node@22` (keg-only); used `npx --yes @angular/cli@latest` for the scaffold and pinned the result via `package.json`.
2. **Plan 0.1 flag list out of date** — `--standalone` removed in Angular 21, `--skip-tests=false` semantics changed (Vitest now). → Dropped `--standalone`; no `--skip-tests` flag needed since the scaffold ships test-runner config out of the box. Recorded in commit body.
3. **Plan 0.4 envisioned two environment files** — both would have been identical bodies. → Collapsed to one with `isDevMode()` after user confirmation.
4. **Lazy `loadComponent` imports broke `tsc`** before placeholder pages existed. → Created one-line placeholder components for the four routes; Phase 2/4/5/6 swap them out one at a time.
5. **Scaffold's `app.html` ships a 344-line placeholder** — components routed via `<router-outlet />` would render *underneath* the placeholder. → Replaced with a single-line outlet; updated `app.spec.ts` to assert the outlet anchor.
6. **`HttpErrorResponse` defaults `statusText` to `'Unknown Error'`** when constructed with empty `statusText` — speculative test for the empty-statusText fallback never fired. → Dropped the test; the path remains in the implementation as defense.
7. **Heredoc-quoted `git commit -m "$(cat <<EOF ... EOF)"` failed with "unexpected EOF"** when the body contained nested parentheses inside quoted strings. → Wrote message to `/tmp/phase2-msg.txt` and used `git commit -F`. Same outcome, simpler quoting.
8. **Angular signal `effect()` flush timing in tests** — `await Promise.resolve()` doesn't drain Angular's effect queue. → `TestBed.tick()` (Angular 18+) flushes deterministically.
9. **Vitest doesn't expose RxJS marble-test scheduler by default** — plan §3.1 acceptance mentions "marble test stubs". → Used a hand-rolled `FakeWebSocket` in a module-level `fakes[]` array; tests drive emissions directly. Same effective coverage, simpler setup.
10. **`<input type="number">` drops trailing zeros** — `"180.50"` becomes `180.5`, breaking BigDecimal precision on the wire (architecture §9.2). The bug surfaced in `place-order.form.spec.ts` first run. → `<input type="text" inputmode="decimal">` keeps the soft keyboard numeric on mobile while preserving exact precision.
11. **Vitest `Array.from(NodeListOf<Element>)` typing** — TS18046: `'r' is of type 'unknown'` when iterating a `NodeListOf` with `.classList.contains`. → Cast through `Array.from(nodes as NodeListOf<HTMLElement>)`. Cosmetic, but worth knowing for future spec writes.
12. **Plan §6.2 wording vs the actual race window** — REST-first then WS-deltas drops a fill that lands between the two if the WS snapshot would have included it (cursor-floor logic). → Use REST for fast first paint AND let the WS snapshot replace the list when it arrives. Documented as a deliberate plan deviation.
13. **Plan §6.3 referenced `TRADE_EXECUTED` events on the orders WS** — backend actually emits `ORDER` events only on `/ws/orders/mine`. → Reacted to ORDER deltas with `PARTIAL` or `FILLED` status; same effective contract.
14. **Plan §7.4 said "4dp" for price formatting** — every §6.x wireframe shows 2dp, seed data is 2dp. → Trust the backend's exact precision verbatim (`formatPrice` is a passthrough that converts null/empty to `'-'`); documented in the function's doc comment so a future reviewer doesn't re-litigate.

## Appendix F — Why this transcript exists

Per requirements §8.6:

> Agent chat history export — the full conversation transcript(s) with the AI coding assistant (Copilot Chat or equivalent), exported as markdown / text / JSON and committed to the repo under `docs/ai-chat/`. Reviewers will read this to understand how the solution was produced.

Naming convention `YYYY-MM-DD-<topic>.md`. The frontend transcript joins `2026-04-25-backend-phases.md` and `2026-04-24-design-session.md` already in this directory. The file was renamed from `2026-04-25-frontend-phases-0-to-3.md` → `2026-04-25-frontend-phases.md` once Phases 4–7 landed and content stabilised — same convention the backend transcript followed when Phase 9 closed it out. Phase 8 (the manual two-tab demo script — procedure, not code) and the simulator + infra components remain pending; their sessions will land under their own filenames when they happen.
