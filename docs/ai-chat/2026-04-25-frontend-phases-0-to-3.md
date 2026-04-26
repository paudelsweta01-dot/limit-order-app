# AI Coding Assistant — Frontend Implementation Session (Phases 0–3)

**Date:** 2026-04-25
**Participants:** Sweta Paudel (engineer) and Claude (AI coding assistant)
**Session purpose:** Implement frontend Phases 0 through 3 from [`docs/plans/frontend-plan.md`](../plans/frontend-plan.md), per the spec in [`docs/requirnments/limit-order-requirements.md`](../requirnments/limit-order-requirements.md), the architecture in [`docs/architecture/architecture.md`](../architecture/architecture.md), and the project standards in [`.claude/instructions.md`](../../.claude/instructions.md).
**Outcome:** **51/51 unit tests green** at the end of the session. The Angular workspace boots, the login page renders the §6.1 wireframe and authenticates against the backend's `/api/auth/login`, and the WebSocket multiplexer is in place with snapshot/cursor handling, exponential-backoff reconnect, and refcounted channels. 4 atomic frontend commits.

> **File-history note.** This transcript covers Phases 0–3 — the same pattern the backend transcript followed (originally `2026-04-25-backend-phases-0-to-5.md`, renamed once Phase 9 landed). Future turns extending this file (Phases 4 onwards) will append rather than rewrite, and the filename will be tightened to `2026-04-25-frontend-phases.md` once the frontend is feature-complete.

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

## Appendix A — Final commit log (this session)

```
6bead40 frontend: phase 3 — ws service (snapshot/deltas, refcount, reconnect, auth)
cb4e59a frontend: phase 2 — login page + authenticated layout shell
64880d0 frontend: phase 1 — core services (models, auth, http, api, guard, routes)
4e171b4 frontend: phase 0 — Angular 21 workspace skeleton
```

Four atomic commits — one per phase — with no fix-ups along the way. (`.claude/settings.local.json` left unstaged each time; that's an IDE-local artifact, not project state.)

## Appendix B — Test coverage by phase

| Phase | Suite | Cases | Cumulative |
|---|---|---:|---:|
| 0 | scaffold `app.spec.ts` (rewritten to assert router-outlet) | 1 | 1 |
| 1 | `auth.service.spec.ts` | 6 | 7 |
| 1 | `http.interceptor.spec.ts` | 7 | 14 |
| 1 | `api.service.spec.ts` | 9 | 23 |
| 1 | `auth.guard.spec.ts` | 3 | 26 |
| 2 | `login.page.spec.ts` | 9 | 35 |
| 2 | `layout.component.spec.ts` | 4 | 39 |
| 3 | `ws.service.spec.ts` | 12 | 51 |

`ng test --watch=false` → **51/51 passing** in ~3 s.

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

## Appendix D — Deferred items (worth knowing in a deep-dive Q&A)

| Deferred | Why | Owner phase |
|---|---|---|
| Real browser smoke for the login flow | I can't drive a browser from this environment; recommended user run `ng serve` + `./mvnw spring-boot:run` and try the §6.1 form against a seed user | Manual verification |
| Real browser smoke for the WS upgrade | Unit tests stub `WebSocket`; they don't catch nginx upgrade-header misconfig or dev-proxy `ws: true` issues. Phase 4/5 will exercise this for real | Phase 4+ / manual |
| `app.config.ts` zone-based change detection vs zoneless | Default Angular 21 scaffold uses `provideBrowserGlobalErrorListeners` and zone-based CD; zoneless is opt-in. WS service's `NgZone.run()` reflects the current choice | Future / scale work |
| WS service's "trades:{symbol}" subscription | Backend's `BookWsHandler` already subscribes both `book:` and `trades:` server-side; one socket carries both delta types — handled transparently in `parseBookFrame`. No extra client wiring needed unless we want a separate trade-stream observable | — (handled) |
| BOOK_UPDATE → REST-refetch wiring | Phase 5 will decide whether `<order-book>` refetches on tickle or backend starts emitting full level deltas. Phase 3 leaves both options open | Phase 5 |
| `app.spec.ts` placeholder | Rewritten in Phase 1 to assert `router-outlet`; Phase 7 should retire it once a real shell test exists | Phase 7 |
| Toast service for transient successes/errors | Plan 7.1; `errorMessage` signal in login page is local for now | Phase 7 |
| `format.ts` shared helpers (BigDecimal price 4dp, qty thousands separator, timestamps) | Plan 7.4; pages use `string` raw for now | Phase 7 |

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

## Appendix F — Why this transcript exists

Per requirements §8.6:

> Agent chat history export — the full conversation transcript(s) with the AI coding assistant (Copilot Chat or equivalent), exported as markdown / text / JSON and committed to the repo under `docs/ai-chat/`. Reviewers will read this to understand how the solution was produced.

Naming convention `YYYY-MM-DD-<topic>.md`. The frontend transcript joins `2026-04-25-backend-phases.md` and `2026-04-24-design-session.md` already in this directory. Future implementation sessions (Phases 4 onwards) will either append to this file or land alongside it under a tighter name (`2026-04-25-frontend-phases.md`) once content stabilises.
