# Infrastructure

Docker-compose stack for the multi-instance limit-order app. Companion to
[`docs/plans/infra-plan.md`](../docs/plans/infra-plan.md) and
[`docs/architecture/architecture.md`](../docs/architecture/architecture.md) §8.

## Layout

```
.
├── docker-compose.yml         # repo root — Phase 5 populates services
├── .env.example               # repo root — copy to .env before bringing the stack up
└── infra/
    ├── README.md              # this file
    ├── nginx/                 # Phase 3: nginx.conf (API + WS reverse proxy / round-robin LB)
    └── postgres/              # Phase 4: 00-init.sql (timezone only; Flyway owns the schema)
```

## Port assignments

| Host port | Container port | Service | Purpose |
|---|---|---|---|
| `:80` | `80` | nginx (LB) | API (`/api/...`), WebSocket (`/ws/...`), `/actuator/health` (read-only) — see Phase 3 |
| `:4200` | `80` | frontend (nginx) | Angular SPA static bundle — see Phase 2 |
| `127.0.0.1:5432` | `5432` | postgres | **Local-debug only** — bound to loopback so the DB is reachable for `psql` / IDE inspection but not exposed externally — see Phase 4.6 |
| (none) | `8080` | backend-1, backend-2 | Not externally exposed; reachable only from the `lob-net` bridge so all client traffic must traverse the LB |

## Why these choices

- **No sticky sessions on the LB.** Each backend node holds its own `LISTEN` connection and serves any client; the architecture's outbox + `LISTEN/NOTIFY` design gives us cross-node consistency without session affinity (architecture §4.7 / §4.8).
- **Both backends behind the LB without external ports.** Forces every test (manual or simulator) to exercise the LB path, so cross-node fan-out is implicitly verified on every request.
- **Postgres bound to loopback.** Convenient for local debugging without leaking the DB to the laptop's network. Override with `POSTGRES_HOST_BIND=0.0.0.0` if the reviewer needs to attach from a different machine.

## Running

```bash
cp .env.example .env
$EDITOR .env          # set POSTGRES_PASSWORD, JWT_SIGNING_SECRET
docker compose up --build
```

(Phase 5 wires the services; Phase 6 documents the smoke checks.)
