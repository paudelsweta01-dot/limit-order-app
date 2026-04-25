-- V1__init.sql
-- Initial schema for the limit-order matching app.
-- Mirrors the architecture blueprint §7.1 exactly.

------------------------------------------------------------
-- Users
------------------------------------------------------------
CREATE TABLE users (
    user_id         UUID         PRIMARY KEY,
    username        TEXT         UNIQUE NOT NULL,
    display_name    TEXT         NOT NULL,
    password_hash   TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

------------------------------------------------------------
-- Symbols
------------------------------------------------------------
CREATE TABLE symbols (
    symbol      TEXT           PRIMARY KEY,
    name        TEXT           NOT NULL,
    ref_price   NUMERIC(18,4)  NOT NULL CHECK (ref_price > 0)
);

------------------------------------------------------------
-- Order ENUM types
------------------------------------------------------------
CREATE TYPE order_side   AS ENUM ('BUY','SELL');
CREATE TYPE order_type   AS ENUM ('LIMIT','MARKET');
CREATE TYPE order_status AS ENUM ('OPEN','PARTIAL','FILLED','CANCELLED');

------------------------------------------------------------
-- Orders
--
-- Schema-enforced invariants (architecture §3 / §7.1):
--   * filled_qty must always be in [0, quantity]
--   * LIMIT orders carry a price; MARKET orders never do
--   * (user_id, client_order_id) is the idempotency key
------------------------------------------------------------
CREATE TABLE orders (
    order_id         UUID           PRIMARY KEY,
    client_order_id  TEXT           NOT NULL,
    user_id          UUID           NOT NULL REFERENCES users(user_id),
    symbol           TEXT           NOT NULL REFERENCES symbols(symbol),
    side             order_side     NOT NULL,
    type             order_type     NOT NULL,
    price            NUMERIC(18,4),
    quantity         BIGINT         NOT NULL CHECK (quantity > 0),
    filled_qty       BIGINT         NOT NULL DEFAULT 0,
    status           order_status   NOT NULL,
    reject_reason    TEXT,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT orders_filled_qty_bounds_chk
        CHECK (filled_qty >= 0 AND filled_qty <= quantity),

    CONSTRAINT orders_price_matches_type_chk
        CHECK ( (type = 'LIMIT'  AND price IS NOT NULL)
             OR (type = 'MARKET' AND price IS NULL) ),

    CONSTRAINT orders_client_order_id_unique
        UNIQUE (user_id, client_order_id)
);

-- Hot path: best opposite-side resting order per symbol.
-- Partial index keeps it tight as filled/cancelled rows accumulate over time.
CREATE INDEX orders_book_idx
    ON orders (symbol, side, price, created_at)
    WHERE status IN ('OPEN','PARTIAL');

-- "My orders" page lookups.
CREATE INDEX orders_user_idx
    ON orders (user_id, created_at DESC);

------------------------------------------------------------
-- Trades (immutable once written)
------------------------------------------------------------
CREATE TABLE trades (
    trade_id        UUID           PRIMARY KEY,
    symbol          TEXT           NOT NULL REFERENCES symbols(symbol),
    buy_order_id    UUID           NOT NULL REFERENCES orders(order_id),
    sell_order_id   UUID           NOT NULL REFERENCES orders(order_id),
    buy_user_id     UUID           NOT NULL REFERENCES users(user_id),
    sell_user_id    UUID           NOT NULL REFERENCES users(user_id),
    price           NUMERIC(18,4)  NOT NULL CHECK (price > 0),
    quantity        BIGINT         NOT NULL CHECK (quantity > 0),
    executed_at     TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX trades_symbol_idx     ON trades (symbol,        executed_at DESC);
CREATE INDEX trades_buy_user_idx   ON trades (buy_user_id,   executed_at DESC);
CREATE INDEX trades_sell_user_idx  ON trades (sell_user_id,  executed_at DESC);

------------------------------------------------------------
-- Outbox for cross-node fan-out (architecture §4.7)
--
-- Trigger-fired pg_notify on every insert means committed events
-- reach all backend instances within a single round-trip — the
-- §3 NFR "trade visible cross-node within 1s" is satisfied trivially.
------------------------------------------------------------
CREATE TABLE market_event_outbox (
    id          BIGSERIAL    PRIMARY KEY,
    channel     TEXT         NOT NULL,
    payload     JSONB        NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX outbox_created_idx ON market_event_outbox (created_at);

CREATE OR REPLACE FUNCTION notify_market_event() RETURNS trigger AS $$
BEGIN
    PERFORM pg_notify('market_event', NEW.id::text);
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER market_event_outbox_notify
    AFTER INSERT ON market_event_outbox
    FOR EACH ROW EXECUTE FUNCTION notify_market_event();
