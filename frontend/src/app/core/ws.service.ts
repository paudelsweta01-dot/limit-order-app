import { DestroyRef, Injectable, NgZone, effect, inject } from '@angular/core';
import { Observable, Subject } from 'rxjs';

import { AuthService } from './auth.service';
import type {
  BookSnapshot,
  BookStreamEvent,
  BookUpdateEvent,
  MyOrder,
  OrderEvent,
  OrdersStreamEvent,
  TradeEvent,
  WsEnvelope,
} from './models';

/** Backoff schedule for reconnect (architecture §5.4). */
const BACKOFF_INITIAL_MS = 250;
const BACKOFF_CAP_MS = 5000;
const BACKOFF_JITTER = 0.20;

interface Channel<E> {
  readonly subject: Subject<E>;
  socket: WebSocket | null;
  refCount: number;
  attempt: number;
  /** Highest cursor the consumer has been told about. -1 means "no snapshot yet". */
  snapshotCursor: number;
  /** True after a deliberate close (logout / refcount went to zero). */
  closed: boolean;
  reconnectTimer: ReturnType<typeof setTimeout> | null;
}

/**
 * One WebSocket multiplexer used by every page that needs a live stream.
 *
 * <ul>
 *   <li>One physical socket per channel pattern. {@link subscribeBook}
 *       opens {@code /ws/book/{symbol}}; {@link subscribeOrders} opens
 *       {@code /ws/orders/mine}. Multiple consumers share the socket via
 *       refcount — when the last unsubscribes, the socket is closed.</li>
 *   <li>Frames arrive as {@code WsEnvelope}; the first {@code snapshot}
 *       sets the cursor floor and is forwarded as a {@code snapshot} event.
 *       Subsequent {@code delta} frames whose cursor is {@code <= floor}
 *       are dropped (architecture §4.8 race fix).</li>
 *   <li>Reconnect is exponential with ±20% jitter, 250 ms → 5 s. After a
 *       successful reconnect the cursor floor is reset by the next
 *       snapshot — components see a fresh snapshot event and can
 *       discard their old state.</li>
 *   <li>The auth signal is watched: when it flips to false (e.g. JWT
 *       expiry triggers {@link AuthService#logout}), every open socket
 *       is closed deliberately so reconnects don't loop on a dead token.</li>
 * </ul>
 */
@Injectable({ providedIn: 'root' })
export class WsService {
  private readonly auth = inject(AuthService);
  private readonly zone = inject(NgZone);

  private readonly bookChannels = new Map<string, Channel<BookStreamEvent>>();
  private ordersChannel: Channel<OrdersStreamEvent> | null = null;

  constructor() {
    // When the user logs out (signal flips false), tear down every open
    // socket. AuthService's own JWT-expiry timer is what triggers this
    // path in production, satisfying plan §3.4.
    inject(DestroyRef).onDestroy(() => this.closeAllChannels());
    effect(() => {
      const authed = this.auth.isAuthenticated();
      if (!authed) this.closeAllChannels();
    });
  }

  subscribeBook(symbol: string): Observable<BookStreamEvent> {
    return new Observable<BookStreamEvent>((observer) => {
      const channel = this.acquireBookChannel(symbol);
      const sub = channel.subject.subscribe(observer);
      return () => {
        sub.unsubscribe();
        this.releaseBookChannel(symbol);
      };
    });
  }

  subscribeOrders(): Observable<OrdersStreamEvent> {
    return new Observable<OrdersStreamEvent>((observer) => {
      const channel = this.acquireOrdersChannel();
      const sub = channel.subject.subscribe(observer);
      return () => {
        sub.unsubscribe();
        this.releaseOrdersChannel();
      };
    });
  }

  /** Test-only: how many consumers currently hold a book stream open. */
  bookSubscriberCount(symbol: string): number {
    return this.bookChannels.get(symbol)?.refCount ?? 0;
  }

  // ---------------------------------------------------------------------
  // book channel
  // ---------------------------------------------------------------------

  private acquireBookChannel(symbol: string): Channel<BookStreamEvent> {
    let ch = this.bookChannels.get(symbol);
    if (!ch) {
      ch = newChannel<BookStreamEvent>();
      this.bookChannels.set(symbol, ch);
      this.openBookSocket(symbol, ch);
    }
    ch.refCount++;
    return ch;
  }

  private releaseBookChannel(symbol: string): void {
    const ch = this.bookChannels.get(symbol);
    if (!ch) return;
    ch.refCount--;
    if (ch.refCount <= 0) {
      this.bookChannels.delete(symbol);
      closeChannel(ch);
    }
  }

  private openBookSocket(symbol: string, ch: Channel<BookStreamEvent>): void {
    const path = `/ws/book/${encodeURIComponent(symbol)}`;
    this.openSocket(ch, path, (envelope) => parseBookFrame(envelope), () => {
      // Reconnect loop callback — only re-open if still wanted.
      if (!ch.closed && this.bookChannels.get(symbol) === ch) {
        this.openBookSocket(symbol, ch);
      }
    });
  }

  // ---------------------------------------------------------------------
  // orders channel
  // ---------------------------------------------------------------------

  private acquireOrdersChannel(): Channel<OrdersStreamEvent> {
    if (!this.ordersChannel) {
      this.ordersChannel = newChannel<OrdersStreamEvent>();
      this.openOrdersSocket(this.ordersChannel);
    }
    this.ordersChannel.refCount++;
    return this.ordersChannel;
  }

  private releaseOrdersChannel(): void {
    const ch = this.ordersChannel;
    if (!ch) return;
    ch.refCount--;
    if (ch.refCount <= 0) {
      this.ordersChannel = null;
      closeChannel(ch);
    }
  }

  private openOrdersSocket(ch: Channel<OrdersStreamEvent>): void {
    this.openSocket(ch, '/ws/orders/mine', (envelope) => parseOrdersFrame(envelope), () => {
      if (!ch.closed && this.ordersChannel === ch) {
        this.openOrdersSocket(ch);
      }
    });
  }

  // ---------------------------------------------------------------------
  // shared socket plumbing
  // ---------------------------------------------------------------------

  private openSocket<E>(
    ch: Channel<E>,
    path: string,
    parseFrame: (env: WsEnvelope) => E | null,
    onReconnect: () => void,
  ): void {
    if (!this.auth.isAuthenticated()) {
      // No point opening a socket for a signed-out session — the handshake
      // would 401 and we'd just churn through reconnect attempts.
      return;
    }
    const url = this.urlFor(path);
    let ws: WebSocket;
    try {
      ws = new WebSocket(url);
    } catch {
      this.scheduleReconnect(ch, onReconnect);
      return;
    }
    ch.socket = ws;

    ws.onmessage = (ev) => this.zone.run(() => this.handleFrame(ch, ev.data, parseFrame));
    ws.onerror = () => {
      // The browser will follow up with a `close` event; reconnect
      // logic lives there to avoid double-scheduling.
    };
    ws.onclose = () => this.zone.run(() => {
      ch.socket = null;
      if (ch.closed) return;
      if (!this.auth.isAuthenticated()) return;
      this.scheduleReconnect(ch, onReconnect);
    });
  }

  private handleFrame<E>(
    ch: Channel<E>,
    raw: unknown,
    parseFrame: (env: WsEnvelope) => E | null,
  ): void {
    if (typeof raw !== 'string') return;
    let env: WsEnvelope;
    try {
      env = JSON.parse(raw) as WsEnvelope;
    } catch {
      return; // skip malformed frames
    }
    if (env.type === 'snapshot') {
      ch.snapshotCursor = env.cursor;
      ch.attempt = 0; // a clean snapshot is the cleanest "we're back"
    } else if (env.type === 'delta') {
      // Plan §3.1: drop frames whose id <= snapshotCursor. The race fix
      // from architecture §4.8 — the snapshot already reflected this
      // event's effects.
      if (env.cursor <= ch.snapshotCursor) return;
      ch.snapshotCursor = env.cursor;
    } else {
      return;
    }
    const event = parseFrame(env);
    if (event !== null) ch.subject.next(event);
  }

  private scheduleReconnect<E>(ch: Channel<E>, onReconnect: () => void): void {
    if (ch.closed) return;
    if (!this.auth.isAuthenticated()) return;
    if (ch.reconnectTimer !== null) return;
    const delay = backoffMs(ch.attempt);
    ch.attempt++;
    ch.reconnectTimer = setTimeout(() => {
      ch.reconnectTimer = null;
      onReconnect();
    }, delay);
  }

  private closeAllChannels(): void {
    for (const ch of this.bookChannels.values()) closeChannel(ch);
    this.bookChannels.clear();
    if (this.ordersChannel) {
      closeChannel(this.ordersChannel);
      this.ordersChannel = null;
    }
  }

  private urlFor(path: string): string {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const token = this.auth.token();
    const tokenParam = token ? `?token=${encodeURIComponent(token)}` : '';
    return `${proto}//${location.host}${path}${tokenParam}`;
  }
}

// ---------- module-private helpers ----------

function newChannel<E>(): Channel<E> {
  return {
    subject: new Subject<E>(),
    socket: null,
    refCount: 0,
    attempt: 0,
    snapshotCursor: -1,
    closed: false,
    reconnectTimer: null,
  };
}

function closeChannel<E>(ch: Channel<E>): void {
  ch.closed = true;
  if (ch.reconnectTimer !== null) {
    clearTimeout(ch.reconnectTimer);
    ch.reconnectTimer = null;
  }
  if (ch.socket) {
    try { ch.socket.close(); } catch { /* ignore */ }
    ch.socket = null;
  }
  ch.subject.complete();
}

/** Backoff: 250ms → 5s, ±20% jitter (architecture §5.4). */
export function backoffMs(attempt: number): number {
  const base = Math.min(BACKOFF_INITIAL_MS * 2 ** attempt, BACKOFF_CAP_MS);
  const jitter = 1 + (Math.random() * 2 - 1) * BACKOFF_JITTER;
  return Math.round(base * jitter);
}

function parseBookFrame(env: WsEnvelope): BookStreamEvent | null {
  if (env.type === 'snapshot') {
    return { kind: 'snapshot', data: env.payload as BookSnapshot };
  }
  const payload = env.payload as BookUpdateEvent | TradeEvent;
  if (payload?.event === 'BOOK_UPDATE' || payload?.event === 'TRADE') {
    return { kind: 'delta', data: payload };
  }
  return null;
}

function parseOrdersFrame(env: WsEnvelope): OrdersStreamEvent | null {
  if (env.type === 'snapshot') {
    return { kind: 'snapshot', data: env.payload as readonly MyOrder[] };
  }
  const payload = env.payload as OrderEvent;
  if (payload?.event === 'ORDER') {
    return { kind: 'delta', data: payload };
  }
  return null;
}
