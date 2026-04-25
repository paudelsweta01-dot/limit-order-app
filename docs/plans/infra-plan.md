# Infrastructure — Implementation Plan

**Companion to:** [`architecture.md`](../architecture/architecture.md). Plan is *what* to build, *in what order*, with *what* signals "done."

**Scope:** `infra/` directory plus per-component Dockerfiles. Local dev + reviewer-runnable docker-compose stack: Postgres + 2 backend instances + nginx + frontend. **No sticky sessions; round-robin LB; WS upgrade headers only.**

**Definition of Done (component-level):**
- `docker compose up --build` from the repo root brings the entire system up cleanly: Postgres healthy, both backends healthy on `/actuator/health`, frontend reachable on `http://localhost:4200`, API reachable on `http://localhost/api/...`.
- The simulator's `scenario` mode (`docs/requirnments` seed) run against the composed stack exits 0.
- Killing one backend with `docker kill backend-1` while the simulator is running causes no test failure — load shifts to backend-2 within seconds.
- Bringing down the whole stack with `docker compose down` and back up resumes a usable system (Postgres volume persists).
- Reviewer can follow the README and reproduce all of the above on a fresh laptop.

**Dependencies on other components:** Backend Phase 9 (observability + healthcheck), Frontend Phase 0 (production build), Simulator Phase 3 (for the smoke test). Earlier infra work — Dockerfiles — can start as soon as the corresponding component skeleton exists.

---

## Phase 0 — Layout & conventions

| # | Task | Acceptance |
|---|---|---|
| 0.1 | Create `infra/` with subdirectories: `nginx/`, `postgres/`, plus the top-level `docker-compose.yml`. | Tree exists. |
| 0.2 | Create `.env.example` at repo root listing every variable used by compose: `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `JWT_SIGNING_SECRET`, `OUTBOX_RETENTION_MINUTES`. Each line commented with purpose. | Reviewer can `cp .env.example .env` and edit secrets. |
| 0.3 | `.gitignore` excludes `.env` (real secrets) but tracks `.env.example`. | `git status` after editing `.env` shows nothing to stage. |
| 0.4 | Document compose port assignments in this file: nginx `:80` (API + WS), frontend nginx `:4200`, Postgres `:5432` (mapped only for local debug — bind to `127.0.0.1`), backends not externally exposed. | Section §5 below; matched by reality once compose is up. |

---

## Phase 1 — Backend Dockerfile

| # | Task | Acceptance |
|---|---|---|
| 1.1 | `backend/Dockerfile` is multi-stage: stage 1 uses `maven:3.9-eclipse-temurin-17` to build, copies `pom.xml` first then `src`, runs `mvn -B -DskipTests package`. Stage 2 uses `eclipse-temurin:17-jre-alpine`, copies the fat JAR, exposes 8080, runs `java -jar app.jar`. | `docker build backend/` succeeds; final image < ~250MB. |
| 1.2 | Image runs as a non-root user (`USER 1000:1000`). | `docker run --rm <img> id` returns uid=1000. |
| 1.3 | `HEALTHCHECK` instruction: `curl -fsS http://localhost:8080/actuator/health \|\| exit 1` every 15s, start-period 30s, retries 3. | `docker ps` shows `(healthy)` after start. |
| 1.4 | Tunable JVM flags via `JAVA_TOOL_OPTIONS` env var; default `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`. | Container respects host memory limit. |

---

## Phase 2 — Frontend Dockerfile

| # | Task | Acceptance |
|---|---|---|
| 2.1 | `frontend/Dockerfile` multi-stage: stage 1 `node:20-alpine` runs `npm ci` and `npm run build --configuration=production` outputting to `dist/`. Stage 2 `nginx:alpine` copies `dist/<app>/` to `/usr/share/nginx/html`. | `docker build frontend/` succeeds; final image < ~50MB. |
| 2.2 | A small `frontend/nginx.conf` for *the frontend container* (separate from the API LB nginx config) configures: `try_files $uri $uri/ /index.html;` for SPA routing and gzipping of static assets. | `docker run -p 4200:80 <img>` serves the SPA on `:4200`. |
| 2.3 | Build-time `API_BASE_URL` is empty string (same-origin via the LB nginx) — wired through the `environment.prod.ts` file used by `--configuration=production`. | App in browser issues requests to `http://localhost/api/...`, not `http://localhost:8080/...`. |

---

## Phase 3 — nginx LB config

The single most important config in the whole stack — sticky sessions are deliberately *off*, but WS upgrade has to be exactly right.

| # | Task | Acceptance |
|---|---|---|
| 3.1 | `infra/nginx/nginx.conf`: `worker_processes auto; events { worker_connections 1024; }`. | Runs without warnings. |
| 3.2 | `upstream backend { server backend-1:8080; server backend-2:8080; }` — default round-robin, **no `ip_hash`, no `sticky cookie`**. | Successive `curl http://localhost/actuator/health` rotate which `instance` field appears in the JSON (assuming backend exposes it). |
| 3.3 | `location /api/` proxies to `http://backend` with `proxy_set_header Host $host; proxy_set_header X-Real-IP $remote_addr; proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for; proxy_set_header X-Forwarded-Proto $scheme;`. | API works from browser. |
| 3.4 | `location /ws/` proxies to `http://backend` with `proxy_http_version 1.1; proxy_set_header Upgrade $http_upgrade; proxy_set_header Connection "upgrade"; proxy_read_timeout 3600s; proxy_send_timeout 3600s;`. | `wscat -c ws://localhost/ws/book/AAPL -H "Authorization: Bearer …"` connects and receives a snapshot frame. |
| 3.5 | `location /actuator/health` proxies (read-only diagnostic) to backend. | `curl http://localhost/actuator/health` returns `UP`. |
| 3.6 | A small `client_max_body_size 16k` for `/api/orders` — orders are tiny; cap silly bodies. | 16k+ POST is rejected with 413. |
| 3.7 | Access log format includes `$upstream_addr` so we can tell which backend served each request from logs alone. | `docker compose logs nginx` shows the upstream addresses. |

---

## Phase 4 — Postgres setup

| # | Task | Acceptance |
|---|---|---|
| 4.1 | Compose declares `postgres:16-alpine` with named volume `pgdata:/var/lib/postgresql/data` so the DB survives `docker compose down` (but is wiped by `docker compose down -v`). | Restart preserves data. |
| 4.2 | Env: `POSTGRES_DB=lob`, `POSTGRES_USER=lob`, `POSTGRES_PASSWORD=${POSTGRES_PASSWORD}`. | `psql -U lob -d lob` from inside the container works. |
| 4.3 | `infra/postgres/00-init.sql` mounted at `/docker-entrypoint-initdb.d/00-init.sql` is **empty or just sets timezone to UTC** — schema is owned by Flyway, not initdb scripts. (Flyway is the source of truth; initdb conflicts confuse migrations.) | First boot logs show Flyway, not initdb DDL. |
| 4.4 | `healthcheck: pg_isready -U lob -d lob` every 5s. | `docker ps` shows `(healthy)`. |
| 4.5 | Tune `max_connections=200` and `shared_buffers=256MB` via either a mounted `postgresql.conf` or `command:` overrides. (Default `max_connections=100` is fine for our pool sizes; bump for headroom under simulator load.) | `SHOW max_connections;` returns `200`. |
| 4.6 | Postgres only listens on `127.0.0.1:5432` from the host (compose `ports: ["127.0.0.1:5432:5432"]`) so the DB is reachable for local debug but not exposed externally. | `nc -z 0.0.0.0 5432` from another machine on the LAN fails. |

---

## Phase 5 — docker-compose.yml

| # | Task | Acceptance |
|---|---|---|
| 5.1 | Top-level `docker-compose.yml` declares services: `postgres`, `backend-1`, `backend-2`, `nginx`, `frontend`. Named network `lob-net` (default bridge is fine; explicit is clearer). | `docker compose config` validates without warnings. |
| 5.2 | `backend-1` and `backend-2` share build context (`build: ./backend`) and inherit a YAML anchor for env / depends_on; differ only by `INSTANCE_ID` and host port (none — they're not externally exposed). | `docker compose up` starts both. |
| 5.3 | Each backend `depends_on: { postgres: { condition: service_healthy } }`. | Backends don't try to migrate against a not-yet-ready DB. |
| 5.4 | Backend env: `SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/lob`, `SPRING_DATASOURCE_USERNAME=lob`, `SPRING_DATASOURCE_PASSWORD=${POSTGRES_PASSWORD}`, `JWT_SIGNING_SECRET=${JWT_SIGNING_SECRET}`, `OUTBOX_RETENTION_MINUTES=${OUTBOX_RETENTION_MINUTES:-5}`, `INSTANCE_ID=backend-1` (or `-2`). | Visible in `docker compose config`. |
| 5.5 | nginx `depends_on: [backend-1, backend-2]`, mounts `infra/nginx/nginx.conf:/etc/nginx/nginx.conf:ro`, exposes `80:80`. | `curl http://localhost` reaches the LB. |
| 5.6 | frontend `depends_on: [nginx]`, exposes `4200:80`. | `curl http://localhost:4200` returns the SPA shell. |
| 5.7 | `restart: unless-stopped` on `postgres`, `nginx`, both backends. Frontend can be `restart: on-failure` (it's static). | Killing a backend container restarts it. |
| 5.8 | Tag the project name explicitly: `name: lob` at the top of the compose file (Compose v2). | `docker compose ls` shows `lob`. |

---

## Phase 6 — Smoke verification

| # | Task | Acceptance |
|---|---|---|
| 6.1 | Cold-start sequence: `docker compose down -v && docker compose up --build`. Watch logs for: Postgres healthy → both backends apply Flyway → both report `Started LobApplication` → nginx idle → frontend `nginx: ready`. | All within ~30s on a developer laptop. |
| 6.2 | Manual smoke: `curl -s http://localhost/api/symbols` returns 5 symbols. `curl -s http://localhost/actuator/health` returns `UP`. Browser at `http://localhost:4200` loads the login page. | Three checks green. |
| 6.3 | Run the simulator's scenario mode: `java -jar simulator/target/*.jar scenario --baseUrl=http://localhost --file=docs/requirnments/seed.csv`. Exit code 0; the §5.4 snapshot assertion passes. | Green. |
| 6.4 | Multi-instance check: `java -jar simulator/target/*.jar multi-instance --nodeA=http://localhost --nodeB=http://localhost ...`. (Both URLs hit the LB; LB rotates internally — equivalent to the test from outside the cluster.) Alternative: expose backends individually for a stricter test. | Green. |
| 6.5 | Resilience check: `docker kill lob-backend-1` while a load run is active. Run continues; no errors; `docker compose ps` shows backend-1 restarted within ~5s. | Green. |
| 6.6 | Persistence check: `docker compose down` (without `-v`), then `docker compose up`. The seeded users + a few orders from a prior run are still present. | Green. |

---

## Phase 7 — Local-dev mode (no compose)

For the build loop where compose ceremony is too slow.

| # | Task | Acceptance |
|---|---|---|
| 7.1 | `infra/dev/postgres-only.compose.yml` brings up just Postgres + a one-shot Flyway runner. | `docker compose -f infra/dev/postgres-only.compose.yml up` is enough for backend tests. |
| 7.2 | README "Local development" section: how to run backend on port 8080 and 8081 from IntelliJ run configs, point Angular's `proxy.conf.json` at `:8080`, run simulator against either. | Reviewer can iterate without compose. |

---

## Phase 8 — Documentation deliverables

These live in the README per §8.3 of the spec but the work happens here.

| # | Task | Acceptance |
|---|---|---|
| 8.1 | README "How to run" section: copy-pasteable commands for compose-up + simulator-scenario + browser URL. | Reviewer follows it on a clean laptop and reaches "I can place an order in the browser." |
| 8.2 | README "Architecture diagram" — ASCII version (already in `architecture.md` §1) or a small PNG/SVG; either is fine per §8.3. | Diagram embedded in README. |
| 8.3 | README "Concurrency strategy" — distilled from `architecture.md` §4.3, focused on (a) per-symbol advisory lock, (b) outbox + LISTEN/NOTIFY for cross-node fan-out, (c) idempotency via UNIQUE constraint, (d) NFR §3 satisfied. | Section under 300 words; spec-grade. |
| 8.4 | README "Trade-offs" — distilled from `architecture.md` §9.6 + §9.7. | Section under 300 words. |
| 8.5 | `docs/ai-chat/` populated with the Copilot Chat / agent transcripts as required by §8.6. | Directory non-empty at submission time. |

---

## Phase 9 — Hardening (optional)

Skip if time is tight; document as deferred.

| # | Task | Acceptance |
|---|---|---|
| 9.1 | Self-signed TLS at the nginx layer (`localhost.pem` + `localhost-key.pem` via `mkcert`) for a closer-to-prod local. | `https://localhost` works without browser warnings on the dev machine. |
| 9.2 | nginx rate limit on `/api/auth/login`: 5 req/min/IP. | Brute-force attempt is throttled. |
| 9.3 | Compose `cpu_limits` and `mem_limits` per service so a runaway process can't starve the laptop. | `docker stats` shows enforced limits. |
| 9.4 | Postgres backup script (`pg_dump`) wired to a `make backup` target. | Manual run produces a `.sql.gz`. |

---

## Risks & open items

- **Mac/Apple Silicon vs Linux/amd64 image mismatches**: pin platform on each service (`platform: linux/amd64` if necessary, or use multi-arch base images like `eclipse-temurin:21-jre-alpine` which is multi-arch). Document the choice.
- **Postgres listening only on `127.0.0.1`**: deliberate. If the reviewer wants direct access from a non-host machine, document the override (`POSTGRES_HOST_BIND=0.0.0.0`). Default is locked.
- **Compose name conflicts**: if a reviewer already has a `lob` project, our compose `name: lob` collides. The README mentions `docker compose ls` to inspect.
- **JVM warm-up under simulator load**: first ~10s after start, p99 latency is poor. Documented; not fixed (CDS/AppCDS could be added later).
- **No CI in this plan**. If the team wants GitHub Actions running compose-up + simulator-scenario on every push, that's a Phase 9 add — out of the spec's stated requirements.

## Suggested execution order

Backend Phase 9 (healthcheck) lands first → Infra Phase 0 → 1 (backend image) → **6.1 sanity** with a stub frontend → 2 (frontend image) → 3 (nginx) → 4 (Postgres) → 5 (compose) → **6 (full smoke)** → 7 (dev shortcuts) → 8 (docs) → 9 (optional).
