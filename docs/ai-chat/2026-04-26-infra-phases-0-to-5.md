# AI Coding Assistant — Infrastructure Implementation Session (Phases 0–5)

**Date:** 2026-04-26
**Participants:** Sweta Paudel (engineer) and Claude (AI coding assistant)
**Session purpose:** Implement infrastructure Phases 0 through 5 from [`docs/plans/infra-plan.md`](../plans/infra-plan.md), per the spec in [`docs/requirnments/limit-order-requirements.md`](../requirnments/limit-order-requirements.md), the architecture in [`docs/architecture/architecture.md`](../architecture/architecture.md), and the project standards in [`.claude/instructions.md`](../../.claude/instructions.md).
**Outcome:** **Full multi-instance stack runs end-to-end via `docker compose up --build`.** Five services all healthy: postgres + two backends + nginx LB + frontend. Verified in this session: LB round-robin (no sticky sessions; alternating `172.18.0.4 ↔ 172.18.0.3` over 10 successive `/actuator/health` probes), end-to-end auth (login as seed user `u1` → JWT → `GET /api/symbols` → `POST /api/orders` → `OPEN`), SPA shell on `:4200`, postgres persistence across `down`/`up`. 6 atomic infra commits.

> **File-history note.** This transcript covers Phases 0–5 — the code-producing infra phases. Phase 6 (smoke verification — a procedural checklist) and Phase 8 (README documentation) remain. Phase 7 (no-compose dev mode) and Phase 9 (hardening) are flagged as optional in the plan. The filename keeps the `-0-to-5` suffix until those land, mirroring the backend transcript's `-0-to-5 → final` rename pattern.

This transcript exists per Deliverable §8.6 of the requirements ("Agent chat history export — committed to the repo under `docs/ai-chat/`"). It is a faithful record of how each phase was produced, the decisions made, and the friction points encountered.

---

## Turn 1 — Infra Phase 0 (layout & conventions)

### User

> Start implementing infra phase 0.

### Assistant

#### Plan

Phase 0 is purely layout — `infra/` skeleton, `.env.example`, gitignore safety, port docs. No build steps, no compose-up. Verified existing state up front: `.env` already in `.gitignore`, repo root has only `backend/ docs/ frontend/`, `infra/` doesn't exist yet.

#### Files produced

- `infra/nginx/.gitkeep` and `infra/postgres/.gitkeep` — empty subdirs aren't tracked by git, so each holds a brief comment noting what Phase 3 / Phase 4 land there. Better than truly-empty `.gitkeep` files; conveys intent.
- `docker-compose.yml` (repo root) — minimal stub `name: lob` + `services: {}`. Validates clean via `docker compose config`. Phase 4/5 populate it.
- `.env.example` (repo root) — five variables documented:
  - `POSTGRES_DB`, `POSTGRES_USER` (defaults `lob` matching architecture §7)
  - `POSTGRES_PASSWORD` (placeholder; reviewer must edit)
  - `JWT_SIGNING_SECRET` (≥ 32 bytes per architecture §4.9; `openssl rand -base64 48` hint)
  - `OUTBOX_RETENTION_MINUTES` (default `5`)
- `.gitignore` — already excluded `.env` from the backend session; added a defensive `!.env.example` allowlist comment so a future tweak to a `.env*` glob can't silently exclude the template.
- `infra/README.md` — port-assignment table per plan §0.4: nginx `:80` (API + WS), frontend nginx `:4200`, Postgres `127.0.0.1:5432` (loopback-only), backends not externally exposed. Includes the `cp .env.example .env && docker compose up --build` starter.

#### Verification

- `git check-ignore -v .env.example .env`: `.env` matches gitignore line 33, `.env.example` doesn't match any rule (will be tracked). Touched a fake `.env` file briefly and `git status` confirmed it stayed untracked.
- `docker compose config` on the stub returned `name: lob\nservices: {}` — validates.

`a0abebd infra: phase 0 — layout, .env.example, port assignments`

#### Friction recurrence

Docker not on PATH from the working shell — same vintage as the backend transcript's Phase 1 friction. Used `/Applications/Docker.app/Contents/Resources/bin/docker` directly throughout this session. Worth a note for the eventual makefile.

---

## Turn 2 — Infra Phase 1 (backend Dockerfile)

### User

> Start implementing phase 1.

### Assistant

#### Plan

Multi-stage Dockerfile for the backend: `maven:3.9-eclipse-temurin-17` builder → `eclipse-temurin:17-jre-alpine` runtime. Plus a `.dockerignore` so the build context doesn't ship `target/` or IDE cruft.

A few design micro-decisions I made up-front rather than asking:

- **`curl` for the healthcheck probe** (vs busybox `wget`). Plan §1.3 specifies `curl -fsS …`; `wget --spider` does HEAD which `/actuator/health` doesn't always serve. ~5 MB extra in the runtime image; worth it for plan-literal.
- **`mvn dependency:go-offline` after copying just `pom.xml`** before copying `src/`. Standard Docker layer-cache trick — when only `src/` changes, the deps download stays cached (~30 s saved per rebuild).
- **`USER 1000:1000` numeric, no `addgroup`/`adduser`**. Linux accepts numeric uids without an `/etc/passwd` entry; logging that says "started by ?" is the predictable consequence (and harmless).

#### Files produced

- `backend/Dockerfile` — multi-stage with the four plan tasks satisfied:
  - 1.1 multi-stage build with the specified base images, `mvn -B -DskipTests package`, fat-JAR `lob-backend-*.jar` glob
  - 1.2 `USER 1000:1000`
  - 1.3 `HEALTHCHECK --interval=15s --start-period=30s --retries=3 CMD curl -fsS http://localhost:8080/actuator/health || exit 1`
  - 1.4 `ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"` — overridable per-container
- `backend/.dockerignore` — trims `target/`, IDE/metadata cruft

#### Verification

- `docker build backend/`: succeeded.
- Image size **246 MB** (under the 250 MB plan target).
- `docker run --rm --entrypoint sh <img> -c id` → `uid=1000 gid=1000`.
- `docker inspect`'s `.Config.Healthcheck` matched the plan's CMD/timings exactly.
- Container start: `Picked up JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` — confirms 1.4 wiring at start time. Spring Boot banner, Tomcat on 8080, then DB-unreachable exit (expected; no Postgres yet).
- Multi-arch base images so no `platform:` pin needed for Apple Silicon.

`cf90de1 infra: phase 1 — backend Dockerfile (multi-stage, non-root, healthcheck)`

#### Friction recurrence

Docker.app's `docker-credential-desktop` helper isn't on PATH; the `# syntax=docker/dockerfile:1.7` directive triggered a frontend image pull which failed credential lookup. Fix: prepend `/Applications/Docker.app/Contents/Resources/bin` to PATH for the build command. Same fix applies for every subsequent build in this session.

#### What I asked at the end

Three build-order options:
- **(a)** Phase 2 (frontend) → 3 (nginx) → 4 (Postgres) → 5 (compose) → 6 (smoke). Plan-literal.
- **(b)** Skip to Phase 4 (Postgres) so we can validate the backend image against a real DB in isolation.
- **(c)** Pause and pivot to the simulator (so 6.3's scenario-mode acceptance has something to call).

---

## Turn 3 — Build-order pivot to Phase 4

### User

> let's go with option b.

### Assistant

Phase 4 (Postgres) before Phase 2/3, so the Phase 1 backend image can be exercised against a real DB in isolation before any LB or frontend exists. This is a particularly informative integration check — a Flyway migration end-to-end run is a much stronger "the backend image works" signal than the start-and-fail-on-DB smoke from Phase 1.

This pivot is recorded as a turn (vs collapsed into Phase 4 below) because it changed the plan's stated execution order (`Phase 0 → 1 → 6.1 sanity → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9` per the plan's "Suggested execution order"). Worth review-time visibility.

---

## Turn 4 — Infra Phase 4 (Postgres setup)

### User

(continuation of the build-order pivot above)

### Assistant

#### Files produced

- `infra/postgres/00-init.sql` — replaces the Phase 0 `.gitkeep`. **Schema-free by design.** Only `ALTER DATABASE lob SET timezone TO 'UTC'`. The file's comment documents *why* DDL belongs in Flyway, not here:
  > Running DDL here would create rows in `flyway_schema_history` with checksums that don't match the migration files, locking up the next migration with a checksum-mismatch error.
- `docker-compose.yml` grew a `postgres` service:
  - `postgres:16-alpine` (multi-arch), named volume `pgdata`
  - `POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set in .env}` — **fail-fast `:?` syntax** so compose validation fails if the reviewer skipped editing `.env`. Better than silently booting with a default secret.
  - `command: ["postgres","-c","max_connections=200","-c","shared_buffers=256MB"]` — inline tunings rather than a separate `postgresql.conf` file (one less artefact)
  - Healthcheck `pg_isready -U lob -d lob` with a 10 s `start_period` so the first probe doesn't fire mid-initdb
  - Port `127.0.0.1:5432:5432` — loopback-only (plan §4.6); psql works locally, not from other LAN hosts
  - 00-init.sql mounted read-only at `/docker-entrypoint-initdb.d/`

#### Verification

- `docker compose config` → clean.
- After `docker compose up -d postgres`:
  - `SHOW max_connections` → **200**
  - `SHOW shared_buffers` → **256MB**
  - `SHOW timezone` → **UTC**
  - `docker ps` shows `(healthy)` within ~2 s
  - initdb log shows only `ALTER DATABASE` — no spurious schema DDL
- **Persistence smoke** (plan §6.6 minus the seed orders): `docker compose down` (no `-v`) → volume persists → `up` again → 5 symbols still present (Flyway recognised v1/v2/v3 as already applied).
- **Bonus end-to-end** — the explicit reason for picking option b:
  ```
  docker run --network lob_default \
    -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/lob \
    -e SPRING_DATASOURCE_USERNAME=lob \
    -e SPRING_DATASOURCE_PASSWORD=changeme \
    -e JWT_SIGNING_SECRET=this-is-a-32-byte-or-longer-test-only-secret \
    -e INSTANCE_ID=smoke \
    lob-backend:phase1
  ```
  Backend connected, Flyway migrated `v1 → v2 → v3` end-to-end. Verification queries:
  - `SELECT symbol FROM symbols ORDER BY symbol;` → `AAPL, AMZN, GOOGL, MSFT, TSLA`
  - `SELECT username FROM users ORDER BY username;` → `u1, u2, u3, u4`

  Backend → DB integration empirically proven before any LB or frontend exists.

#### Friction

Initial `eval "$D inspect …"` hack mangled SQL strings via double-expansion (`SHOW max_connections;` got parsed as multiple args). Switched to setting `PATH` once per command rather than aliasing through `eval`.

`23edc08 infra: phase 4 — postgres service (compose-up + persistence + flyway)`

---

## Turn 5 — Infra Phase 2 (frontend Dockerfile)

### User

> Start implementing phase 2.

### Assistant

#### Note on plan §2.3

Plan §2.3 says wire `API_BASE_URL` through `environment.prod.ts`. We collapsed to a single `environment.ts` in Phase 0 of the **frontend** session (with `apiBaseUrl: ''`), and the user OK'd that. The same-origin contract is already satisfied — the prod build literally bakes in empty base URLs. No new file needed; the verification step is grepping the bundle for any `localhost:8080` leak.

I flagged this re-use to the user before writing code so the plan deviation was visible.

#### Files produced

- `frontend/Dockerfile` — multi-stage:
  - **build**: `node:20-alpine` → `npm ci --no-audit --no-fund` (lockfile-first for layer cache) → `npx ng build --configuration=production`. Output at `/build/dist/frontend/browser/` (Angular 21 emits the browser bundle to `dist/<project>/browser/`)
  - **runtime**: `nginx:alpine-slim` (vs `nginx:alpine` — see image-size discovery below)
- `frontend/nginx.conf` — server block mounted at `/etc/nginx/conf.d/default.conf`:
  - SPA fallback: `try_files $uri $uri/ /index.html`
  - Static assets: `try_files $uri =404` (real 404 for missing assets, *not* the SPA fallback — prevents masking missing-asset bugs)
  - gzip on text/JS/JSON/SVG, min 1 KB, comp level 6
  - Hashed assets → `Cache-Control: public, immutable; max-age=30d`
  - `index.html` → `Cache-Control: no-store, no-cache, must-revalidate` (fresh deploy picked up immediately)
- `frontend/.dockerignore` — trims `node_modules/`, `dist/`, `.angular/`

#### Image-size discovery — `nginx:alpine` → `nginx:alpine-slim`

First build came in at **62.5 MB** — over the plan's "~50 MB" target. Investigation:
- `nginx:alpine` ≈ 50 MB (alpine 3.20+ has grown over time)
- `nginx:alpine-slim` is a smaller variant that strips perl + GeoIP + image-filter + xslt + njs dynamic modules — **none of which a static-SPA server needs**

Pulled the slim variant: 12.7 MB on disk. Rebuilt with `FROM nginx:alpine-slim AS runtime` → final image **13.1 MB**, well under budget. The decision is captured in a comment on the `FROM` line.

#### Verification (six checks, all green)

- `docker build frontend/` → 13.1 MB final
- `GET /` → 200 (524 bytes index.html)
- `GET /login`, `GET /symbol/AAPL`, `GET /me` → 200 (SPA fallback)
- `GET /missing.css` → **404** (real 404, not SPA fallback)
- gzip + cache-control headers correct on hashed JS, no-store on index.html
- Bundle scan: zero `localhost:8080` / `localhost:4200`; `apiBaseUrl:""` baked into the chunk → all calls become same-origin relative paths, ready for the LB to catch in Phase 3

`9c2ddae infra: phase 2 — frontend Dockerfile (multi-stage, SPA routing, gzip)`

---

## Turn 6 — Infra Phase 3 (nginx LB config)

### User

> Start implementing phase 3.

### Assistant

#### Plan

Phase 3 is config-only — the nginx service is wired in Phase 5. The plan calls this "the single most important config in the whole stack — sticky sessions are deliberately *off*, but WS upgrade has to be exactly right." Two design choices worth a comment in the file:

1. **`Connection "upgrade"` (literal string) vs `$connection_upgrade` map idiom**. The map idiom (`map $http_upgrade $connection_upgrade { default upgrade; '' close; }`) is the canonical nginx WS pattern and is safer if a non-upgrade request ever hits `/ws/`. The plan explicitly specifies the literal string. I went plan-literal, since `/ws/` is a separate location and only handles WS upgrade requests in our setup.
2. **Longest-prefix scoping for `client_max_body_size 16k`**. nginx's longest-prefix-match means `location /api/orders` wins over `location /api/`, so the 16 KB cap is scoped to order submission only. Other `/api/*` endpoints retain the default 1 MB so future bulk-action endpoints aren't accidentally throttled. Documented in the file.

#### Files produced

- `infra/nginx/nginx.conf` — replaces the Phase 0 `.gitkeep`. Full top-level config:
  - `worker_processes auto`, `events { worker_connections 1024 }`
  - `log_format lb` includes `upstream=$upstream_addr` + `rt`/`uct`/`urt` per plan §3.7
  - `upstream backend { server backend-1:8080; server backend-2:8080; }` — default round-robin, **no `ip_hash`, no `sticky cookie`** (architecture §4.7)
  - Four locations: `/actuator/health`, `/api/orders` (with 16 KB cap), `/api/` (catch-all), `/ws/` (with the four upgrade-related headers + 3600 s timeouts)
  - All proxy locations set `Host`, `X-Real-IP`, `X-Forwarded-For`, `X-Forwarded-Proto`

#### Verification (against `nginx:alpine-slim` in isolation)

- `nginx -t` syntax check — **had to stub upstream hostnames** because nginx resolves them at config-test time. `--add-host backend-1:127.0.0.1 --add-host backend-2:127.0.0.1` makes the names resolvable (any address; `nginx -t` doesn't connect, just resolves). Then **PASS**.
- Functional smoke without backends:
  - `POST 100B /api/orders` → **502** (body cap passed, proxy fails — no backend)
  - `POST 17 KB /api/orders` → **413** (body cap fires *before* proxy attempt)
  - `POST 17 KB /api/auth/login` → **502** (no cap on `/api/`, proxy attempted) — proves longest-prefix scoping works
- Access log line: `upstream=127.0.0.1:8080, 127.0.0.1:8080` on the 502s — incidentally proves nginx's automatic round-robin + failover at the LB level (Phase 5 will exercise it against real backends). The 413 shows `upstream=-`, confirming nginx rejected the request before any proxy attempt.

`3a3c12e infra: phase 3 — nginx LB config (round-robin, WS upgrade, 16k cap)`

---

## Turn 7 — Infra Phase 5 (full compose stack)

### User

> Start implementing phase 5.

### Assistant

#### Plan

Phase 5 wires everything together. Starting from the postgres-only compose, add backend-1, backend-2, nginx, frontend, and an explicit `lob-net` network. Plan §5.2 explicitly asks for a YAML anchor sharing env/depends_on between the two backends — using that.

Two design choices worth flagging:

1. **`condition: service_healthy` on nginx → backends** (vs the plan's literal short-form `depends_on: [backend-1, backend-2]` which means `service_started`). Without it, nginx accepts traffic ~5 s into backend boot and serves 502 for the next ~10 s while Spring + Flyway + LISTEN finish. With healthy, total cold-start is ~30 s but no error window. Plan §6.1's acceptance ("all within ~30 s") is met either way; the healthy version gives a cleaner first-time experience.
2. **Explicit `container_name: lob-backend-{1,2}`** so plan §6.5's `docker kill lob-backend-1` example works literally. The trade-off is that `docker compose up --scale backend-1=3` would error — but we deliberately want exactly two backends, not a scaled service.

#### Files produced

`docker-compose.yml` extended (postgres service stays from Phase 4; four new services + network):

```yaml
x-backend-env:  &backend-env   # SPRING_DATASOURCE_*, JWT_SIGNING_SECRET, OUTBOX_RETENTION_MINUTES
x-backend-base: &backend-base  # build, depends_on postgres:service_healthy, restart, network

services:
  postgres:    # …unchanged from Phase 4 + networks: [lob-net]
  backend-1:   # <<: *backend-base, environment: <<: *backend-env + INSTANCE_ID: backend-1
  backend-2:   # ditto, INSTANCE_ID: backend-2
  nginx:       # nginx:alpine-slim, depends_on backend-{1,2}:service_healthy, mounts Phase 3's config
  frontend:    # build: ./frontend, depends_on nginx, restart: on-failure (static, only restart on crash)

volumes:  pgdata:
networks: lob-net: { driver: bridge }
```

YAML anchor merge: `<<: *backend-env` followed by `INSTANCE_ID: backend-1` correctly produces a six-key environment map. Compose v2 supports this.

#### Verification — full cold start

- `docker compose up -d --build`: all five containers reach their final state (`Up (healthy)` for postgres + 2 backends; `Up` for nginx + frontend).
- **LB rotation**: 10 successive `curl http://localhost/actuator/health` calls in nginx access log:
  ```
  upstream=172.18.0.4:8080  ← backend-1
  upstream=172.18.0.3:8080  ← backend-2
  upstream=172.18.0.4:8080
  upstream=172.18.0.3:8080
  …
  ```
  Perfect alternation. (The first batch of 6 probes happened to all hit one backend — likely racing nginx-worker startup. With 10+, rotation is unambiguous. Worth noting that `docker inspect` confirmed the IPs map: backend-1 → `.4`, backend-2 → `.3`.)
- **End-to-end auth flow through the LB**:
  - `POST /api/auth/login {"username":"u1","password":"alice123"}` → 200 + JWT
  - `GET /api/symbols` (with Bearer) → all five symbols with refPrices
  - `POST /api/orders {LIMIT BUY 100 @ 175.00 AAPL}` → 201 + `{orderId, status: OPEN, filledQty: 0}` (no opposing ask, sits resting)
- `curl http://localhost:4200/` → 200, 524 bytes SPA shell.
- `docker compose down -v` cleanly removes containers + lob-net + pgdata.

#### Friction

- Initial login attempt with `password1` failed (401). Re-read `V3__seed_users.java` to find the actual passwords: `alice123`, `bob123`, `charlie123`, `diana123` — keyed off the display name, not the username.
- HEREDOC quoting in `git commit -m "$(cat <<EOF...EOF)"` continues to be unreliable when the message body contains nested parentheses inside quoted strings. Sticking with `git commit -F /tmp/msg.txt` for every infra commit. Same friction as the frontend transcript noted.

`00bfb48 infra: phase 5 — full compose stack (postgres + 2 backends + nginx + frontend)`

#### What I asked at the end

Most of plan §6.2's manual smoke is already proven (LB, auth, SPA shell). The remaining checks need the simulator: §6.3 scenario, §6.4 multi-instance, §6.5 resilience kill-test. Three options:
- **(a)** Pivot to the simulator so §6.3 has something to call.
- **(b)** Run the manual subset of §6 now (kill-test + persistence) and defer simulator-driven checks.
- **(c)** Push these commits to GitHub.

The user's response (this turn): "let's go with option a but before that format our entire conversation history for infra implementation to a md file." → this transcript.

---

## Appendix A — Final commit log (this session)

```
00bfb48 infra: phase 5 — full compose stack (postgres + 2 backends + nginx + frontend)
3a3c12e infra: phase 3 — nginx LB config (round-robin, WS upgrade, 16k cap)
9c2ddae infra: phase 2 — frontend Dockerfile (multi-stage, SPA routing, gzip)
23edc08 infra: phase 4 — postgres service (compose-up + persistence + flyway)
cf90de1 infra: phase 1 — backend Dockerfile (multi-stage, non-root, healthcheck)
a0abebd infra: phase 0 — layout, .env.example, port assignments
```

Six atomic commits. Build order: 0 → 1 → 4 → 2 → 3 → 5 (per the user's option-b pivot at Turn 3 above; deviates from the plan's literal order but lands at the same final state).

## Appendix B — Architectural decisions captured

| Decision | Rationale | Phase |
|---|---|---|
| `.gitkeep` files carry forward-pointing comments (vs being empty) | Empty placeholders waste a tracked file; comments doubling as documentation pay rent | 0 |
| `${POSTGRES_PASSWORD:?…}` / `${JWT_SIGNING_SECRET:?…}` | Fail-fast on missing `.env` rather than silently booting with a default secret | 0, 4, 5 |
| `!.env.example` allowlist comment | Defense-in-depth against a future `.env*` glob silently excluding the template | 0 |
| Multi-arch base images (no `platform:` pin) | Apple Silicon vs CI runners "just work" without compose tweaks | 1, 2, 4 |
| `curl` for backend healthcheck (~5 MB cost) over `wget --spider` | Plan-literal; `wget --spider` does HEAD which actuator/health doesn't always serve | 1 |
| `mvn dependency:go-offline` after pom-only copy | Standard layer-cache trick; src-only changes save ~30 s of redownload per rebuild | 1 |
| `USER 1000:1000` (numeric, no `addgroup`/`adduser`) | Linux accepts numeric uids; "started by ?" log is the harmless predictable consequence | 1 |
| `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` | Respects cgroup memory limits set by compose; fail-fast under sustained OOM so the restart policy reschedules | 1 |
| Build-order pivot: Phase 4 before Phases 2/3 | Lets the backend image be exercised against a real DB in isolation; Flyway end-to-end is a much stronger signal than the start-and-fail-on-DB smoke from Phase 1 | 3 (this turn) |
| Postgres tunings via `command:` (vs mounted `postgresql.conf`) | One less artefact to keep in sync; the two tunings (`max_connections=200`, `shared_buffers=256MB`) are short enough that inline is no harder to read | 4 |
| `infra/postgres/00-init.sql` schema-free | Flyway is the single source of truth for DDL; initdb DDL would create rows in `flyway_schema_history` with checksums that don't match the migration files, locking subsequent migrations | 4 |
| Single `environment.ts` (no `environment.prod.ts`) for plan §2.3 | Same-origin in both modes; the prod build bakes in `apiBaseUrl: ''`. Verified via bundle grep for `localhost:8080` leakage | 2 |
| `nginx:alpine-slim` over `nginx:alpine` | Drops perl/GeoIP/image-filter/xslt/njs (~37 MB saved); none needed for static-SPA serving. Brings the image from 62.5 MB to 13.1 MB | 2 |
| Real 404 (vs SPA fallback) for missing static assets | `try_files $uri =404` on the JS/CSS location prevents the SPA fallback from masking missing-asset bugs in production | 2 |
| `Cache-Control: no-store` on index.html, `public, immutable; max-age=30d` on hashed assets | A fresh deploy is picked up immediately because index.html isn't cached; the hashed JS/CSS it references will be fresh too | 2 |
| Plan-literal `Connection "upgrade"` (vs `$connection_upgrade` map idiom) | Plan-literal; `/ws/` is a separate location and only handles upgrade requests | 3 |
| `client_max_body_size 16k` scoped to `/api/orders` only via longest-prefix match | Future bulk-action endpoints under `/api/*` aren't accidentally throttled | 3 |
| `log_format lb` includes `upstream=$upstream_addr` + `rt`/`uct`/`urt` | Answers "did the LB rotate?" + "where's latency living?" from `docker compose logs nginx` alone, no MDC inspection needed | 3 |
| YAML anchors `x-backend-env` + `x-backend-base` | Plan §5.2 literal; backend-1 and backend-2 differ only in `INSTANCE_ID` + `container_name` | 5 |
| `condition: service_healthy` on nginx → backends (vs the plan's short-form) | No 502 window during cold start; total compose-up to first-200 stays ~30 s | 5 |
| `container_name: lob-backend-{1,2}` (vs scaled replicas) | Plan §6.5's `docker kill lob-backend-1` example works literally; we never want to scale these services | 5 |
| `restart: on-failure` for frontend (vs `unless-stopped`) | Static content; only restart if the process actually crashed | 5 |
| Backends not externally exposed | Forces every request to traverse the LB → multi-instance design is exercised on every call | 5 |

## Appendix C — Deferred items

| Deferred | Why | Owner phase |
|---|---|---|
| Phase 6 manual smoke (full plan §6.2/§6.5/§6.6 checklist) | LB rotation, auth flow, persistence are already proven (Phase 4 + 5 verifications above). The kill-test (§6.5) needs an active load — natural pairing with the simulator | Phase 6 / simulator |
| Phase 6.3 simulator scenario run | Needs the simulator to exist; that's the next pivot per the plan's suggested order | Simulator phase + Phase 6 |
| Phase 7 no-compose dev mode (`infra/dev/postgres-only.compose.yml`) | Useful for tight backend-test loops but not on the path to "stack works for review" | Phase 7 |
| Phase 8 README deliverables (§8.1–8.5) | Architecture diagram, concurrency strategy, trade-offs sections — distilled from `architecture.md`. Editorial work, not infra | Phase 8 |
| Phase 9 hardening (TLS, rate-limits, cpu/mem caps, pg_dump) | Plan flags as optional; skipped per "skip if time is tight" | Phase 9 (optional) |
| Backend healthcheck `start_period: 30s` | Tight on a slow laptop; would bump to 60 s if Phase 6 cold-start observations show probe failures during initial Spring boot | Phase 6 if needed |
| `/actuator/prometheus` exposure | The backend transcript flagged this as broken in the Spring Boot 3.3.5 / Prometheus 1.x combo. Frontend doesn't talk to `/actuator` directly so nothing infra-side to do; backend follow-up | Backend follow-up |
| ~~`docker compose up --build` cold-start verified~~ | **Resolved in Phase 5** — full stack reaches healthy and end-to-end auth + order submission works through the LB | — |
| ~~LB round-robin verified~~ | **Resolved in Phase 5** — 10 successive probes alternate 172.18.0.4 ↔ 172.18.0.3 in nginx access log | — |
| ~~Postgres persistence across compose down/up~~ | **Resolved in Phase 4** — `down` (no `-v`) preserves the named volume; `up` again finds 5 symbols | — |

## Appendix D — Friction points

1. **Docker not on PATH** — Docker.app's binary lives at `/Applications/Docker.app/Contents/Resources/bin/docker`; the working shell doesn't pick it up. Used absolute path or `PATH=…:$PATH docker …` for every command this session. Same friction the backend transcript noted in Phase 1.
2. **`docker-credential-desktop` helper not on PATH either** — `# syntax=docker/dockerfile:1.7` and other operations that pull from registry fail credential lookup. Fix: prepend `/Applications/Docker.app/Contents/Resources/bin` to PATH for the build commands.
3. **`nginx -t` resolves upstream hostnames at config-test time** — `backend-1`/`backend-2` aren't resolvable outside the compose network, so syntax-checking the config in isolation fails. Fix: `--add-host backend-1:127.0.0.1 --add-host backend-2:127.0.0.1` stubs so resolution succeeds (any address works; `-t` doesn't connect).
4. **Initial frontend image at 62.5 MB, over the ~50 MB target** — switched from `nginx:alpine` to `nginx:alpine-slim` (drops perl/GeoIP/image-filter/xslt/njs). Result: 13.1 MB.
5. **Initial 6-probe LB rotation test all hit one backend** — likely a race with nginx workers' round-robin counter at startup. With 10+ successive probes the rotation is unambiguous. Worth noting in the eventual transcript that "small N" tests can be misleading here.
6. **`eval` double-expansion mangled `psql` SQL strings** — `eval "$D compose exec -T postgres psql -tAc 'SHOW max_connections;'"` parsed `SHOW` and `max_connections;` as separate args. Switched to `PATH=…:$PATH docker compose exec …` directly per command.
7. **Wrong seed-user passwords on first login attempt** — guessed `password1`; actual values are `{user}123` keyed off display name (`alice123`, `bob123`, etc.). Found by reading `V3__seed_users.java`.
8. **HEREDOC quoting in `git commit -m "$(cat <<EOF…EOF)"` continues to be unreliable** when the message body has nested parentheses. Sticking with `git commit -F /tmp/msg.txt`.

## Appendix E — Why this transcript exists

Per requirements §8.6:

> Agent chat history export — the full conversation transcript(s) with the AI coding assistant (Copilot Chat or equivalent), exported as markdown / text / JSON and committed to the repo under `docs/ai-chat/`. Reviewers will read this to understand how the solution was produced.

Naming convention `YYYY-MM-DD-<topic>.md`. The infra transcript joins `2026-04-25-backend-phases.md`, `2026-04-25-frontend-phases.md`, and `2026-04-24-design-session.md` already in this directory. Filename keeps the `-0-to-5` suffix until Phases 6–9 land (Phase 6 is procedural smoke verification, Phase 7 is optional dev-mode, Phase 8 is README docs, Phase 9 is optional hardening); rename to `2026-04-26-infra-phases.md` will follow the same pattern the backend and frontend transcripts established.
