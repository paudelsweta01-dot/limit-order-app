// Wire shapes — must mirror the backend DTOs in
// `com.sweta.limitorder.api.*` and the WS frame format in
// `com.sweta.limitorder.ws.WsFrame`. Numerics that the backend serialises
// as BigDecimal arrive as JSON strings (architecture §9.2); they're typed
// as `string` here and only parsed at presentation time.

export type OrderSide = 'BUY' | 'SELL';
export type OrderType = 'LIMIT' | 'MARKET';
export type OrderStatus = 'OPEN' | 'PARTIAL' | 'FILLED' | 'CANCELLED' | 'REJECTED';

// ---------- REST ----------

export interface SymbolRow {
  readonly symbol: string;
  readonly name: string;
  readonly refPrice: string;
}

export interface BookLevel {
  readonly price: string;
  readonly qty: number;
  readonly userCount: number;
}

export interface BookSnapshot {
  readonly symbol: string;
  readonly bids: readonly BookLevel[];
  readonly asks: readonly BookLevel[];
  readonly last: string | null;
  readonly cursor: number;
}

export interface BookTotals {
  readonly demand: number;
  readonly supply: number;
}

export interface LoginRequest {
  readonly username: string;
  readonly password: string;
}

export interface LoginResponse {
  readonly token: string;
  readonly userId: string;
  readonly name: string;
}

export interface SubmitOrderRequest {
  readonly clientOrderId: string;
  readonly symbol: string;
  readonly side: OrderSide;
  readonly type: OrderType;
  readonly price: string | null;
  readonly quantity: number;
}

export interface SubmitOrderResponse {
  readonly orderId: string;
  readonly status: OrderStatus;
  readonly filledQty: number;
  readonly rejectReason?: string;
  readonly idempotentReplay: boolean;
}

export interface CancelOrderResponse {
  readonly orderId: string;
  readonly status: OrderStatus;
  readonly filledQty: number;
}

export interface MyOrder {
  readonly orderId: string;
  readonly clientOrderId: string;
  readonly symbol: string;
  readonly side: OrderSide;
  readonly type: OrderType;
  readonly price: string | null;
  readonly quantity: number;
  readonly filledQty: number;
  readonly status: OrderStatus;
  readonly rejectReason?: string;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface MyFill {
  readonly tradeId: string;
  readonly symbol: string;
  readonly side: OrderSide;
  readonly price: string;
  readonly quantity: number;
  readonly executedAt: string;
  readonly counterparty: string;
}

// Architecture §4.11 error envelope.
export interface ErrorResponseBody {
  readonly code: string;
  readonly message: string;
  readonly details?: ReadonlyArray<{ readonly field: string; readonly message: string }>;
}

// ---------- WebSocket frames ----------
//
// Outer envelope: `{ type, channel, cursor, payload }` (`ws/WsFrame.java`).
// Channel naming follows the matching engine's emit calls:
//   book:{symbol}     → BookUpdateEvent
//   trades:{symbol}   → TradeEvent
//   orders:{userId}   → OrderEvent

export type WsFrameType = 'snapshot' | 'delta';

export interface WsEnvelope<P = unknown> {
  readonly type: WsFrameType;
  readonly channel: string;
  readonly cursor: number;
  readonly payload: P;
}

// Delta payloads — string-format constants come from
// `MatchingEngineService#tradeJson|orderJson|bookJson`.

export interface TradeEvent {
  readonly event: 'TRADE';
  readonly tradeId: string;
  readonly price: string;
  readonly qty: number;
}

export interface OrderEvent {
  readonly event: 'ORDER';
  readonly orderId: string;
  readonly status: OrderStatus;
  readonly filledQty: number;
}

export interface BookUpdateEvent {
  readonly event: 'BOOK_UPDATE';
  readonly symbol: string;
}

// Snapshot payloads on the WS:
//   /ws/book/{symbol}    → BookSnapshot (REST shape, sent verbatim)
//   /ws/orders/mine      → MyOrder[]    (REST shape, sent verbatim)
// Deltas on /ws/book/{symbol} are either a BookUpdateEvent or TradeEvent
// depending on the channel. We keep the discriminator on `payload.event`
// for runtime narrowing.
