# Limit Order App — top-level Makefile.
#
# Convenience targets only — none of these are required to use the app.
# `docker compose up --build` and per-module `./mvnw test` / `npx ng test`
# remain the canonical entry points (see README.md).

.PHONY: help backup tls-setup test build clean

help:
	@echo "Limit Order App — Make targets"
	@echo ""
	@echo "  make backup        Plan §9.4 — pg_dump the running stack to backups/lob-<ts>.sql.gz"
	@echo "  make tls-setup     Plan §9.1 — generate self-signed certs via mkcert (one-time)"
	@echo "  make test          Run all three module test suites (backend / frontend / simulator)"
	@echo "  make build         Build all three modules"
	@echo "  make clean         Remove generated build artefacts"
	@echo ""
	@echo "See README.md for the full reviewer flow."

# Plan §9.4 — Postgres backup. Requires the compose stack running.
backup:
	@./infra/scripts/backup.sh

# Plan §9.1 — one-time TLS cert generation. Requires `mkcert` installed.
tls-setup:
	@./infra/tls/setup.sh

test:
	@echo "==> backend"
	@cd backend && ./mvnw test
	@echo "==> frontend"
	@cd frontend && npx ng test --watch=false
	@echo "==> simulator"
	@cd simulator && ./mvnw test

build:
	@echo "==> backend"
	@cd backend && ./mvnw -DskipTests package
	@echo "==> frontend"
	@cd frontend && npm run build
	@echo "==> simulator"
	@cd simulator && ./mvnw -DskipTests package

clean:
	@cd backend && ./mvnw clean
	@cd simulator && ./mvnw clean
	@rm -rf frontend/dist frontend/node_modules/.cache
