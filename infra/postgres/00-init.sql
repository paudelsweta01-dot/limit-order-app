-- Postgres initdb script (mounted at
-- /docker-entrypoint-initdb.d/00-init.sql by docker-compose.yml).
--
-- This file MUST stay schema-free. The backend's Flyway migrations
-- (V1__init.sql etc., architecture §7.1) are the single source of truth
-- for table definitions; running DDL here would create rows in
-- `flyway_schema_history` with checksums that don't match the migration
-- files, locking up the next migration with a checksum-mismatch error.
--
-- All it does: pin the database's session timezone to UTC so Postgres
-- never silently re-interprets timestamps based on the host's locale
-- (architecture §9.1 — the system writes UTC, reads UTC, stores UTC).

ALTER DATABASE lob SET timezone TO 'UTC';
