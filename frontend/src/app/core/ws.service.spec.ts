import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { Subscription } from 'rxjs';

import { AuthService } from './auth.service';
import { WsService, backoffMs } from './ws.service';
import type {
  BookSnapshot,
  BookStreamEvent,
  OrdersStreamEvent,
} from './models';

// ---------------------------------------------------------------------------
// Fake WebSocket — drives the service without a real connection.
// ---------------------------------------------------------------------------

interface FakeSocket {
  url: string;
  readyState: number;
  onopen: ((this: WebSocket, ev: Event) => unknown) | null;
  onmessage: ((this: WebSocket, ev: MessageEvent) => unknown) | null;
  onclose: ((this: WebSocket, ev: CloseEvent) => unknown) | null;
  onerror: ((this: WebSocket, ev: Event) => unknown) | null;
  close: () => void;
  // test helpers:
  emit: (raw: unknown) => void;
  closeFromServer: (code?: number) => void;
}

const fakes: FakeSocket[] = [];

function makeFakeWebSocket() {
  return class FakeWS {
    static OPEN = 1;
    static CLOSED = 3;
    url: string;
    readyState = 0;
    onopen: ((ev: Event) => unknown) | null = null;
    onmessage: ((ev: MessageEvent) => unknown) | null = null;
    onclose: ((ev: CloseEvent) => unknown) | null = null;
    onerror: ((ev: Event) => unknown) | null = null;

    constructor(url: string) {
      this.url = url;
      const handle: FakeSocket = {
        url,
        get readyState() { return that.readyState; },
        set readyState(v: number) { that.readyState = v; },
        get onopen() { return that.onopen as never; },
        set onopen(v) { that.onopen = v as never; },
        get onmessage() { return that.onmessage as never; },
        set onmessage(v) { that.onmessage = v as never; },
        get onclose() { return that.onclose as never; },
        set onclose(v) { that.onclose = v as never; },
        get onerror() { return that.onerror as never; },
        set onerror(v) { that.onerror = v as never; },
        close: () => this.close(),
        emit: (raw: unknown) =>
          this.onmessage?.(new MessageEvent('message', { data: typeof raw === 'string' ? raw : JSON.stringify(raw) })),
        closeFromServer: (code = 1006) => {
          this.readyState = 3;
          this.onclose?.(new CloseEvent('close', { code }));
        },
      };
      // eslint-disable-next-line @typescript-eslint/no-this-alias
      const that = this;
      fakes.push(handle);
      // Fire onopen synchronously so the test doesn't need to wait.
      queueMicrotask(() => {
        this.readyState = 1;
        this.onopen?.(new Event('open'));
      });
    }

    close() {
      this.readyState = 3;
      this.onclose?.(new CloseEvent('close', { code: 1000 }));
    }
  };
}

// ---------------------------------------------------------------------------
// Fake AuthService — controllable signal so we can flip auth on/off.
// ---------------------------------------------------------------------------

class FakeAuth {
  authed = signal(true);
  isAuthenticated = () => this.authed();
  token = () => 'fake-token';
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const SNAPSHOT_FRAME = (cursor: number, payload: unknown) =>
  JSON.stringify({ type: 'snapshot', channel: `book:AAPL`, cursor, payload });

const DELTA_FRAME = (cursor: number, payload: unknown) =>
  JSON.stringify({ type: 'delta', channel: `book:AAPL`, cursor, payload });

const sampleBookSnapshot: BookSnapshot = {
  symbol: 'AAPL',
  bids: [],
  asks: [],
  last: null,
  cursor: 100,
};

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('WsService', () => {
  let auth: FakeAuth;
  let svc: WsService;
  let originalWS: typeof WebSocket;

  beforeEach(() => {
    fakes.length = 0;
    auth = new FakeAuth();
    originalWS = (globalThis as { WebSocket: typeof WebSocket }).WebSocket;
    (globalThis as { WebSocket: unknown }).WebSocket = makeFakeWebSocket();
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: auth }],
    });
    svc = TestBed.inject(WsService);
  });

  afterEach(() => {
    (globalThis as { WebSocket: typeof WebSocket }).WebSocket = originalWS;
  });

  // ------------- 3.1 snapshot-then-deltas -------------

  it('emits snapshot then deltas, dropping deltas with cursor <= snapshotCursor', async () => {
    const events: BookStreamEvent[] = [];
    const sub = svc.subscribeBook('AAPL').subscribe((e) => events.push(e));
    await Promise.resolve();

    expect(fakes.length).toBe(1);
    const ws = fakes[0];
    expect(ws.url).toContain('/ws/book/AAPL');
    expect(ws.url).toContain('token=fake-token');

    ws.emit(SNAPSHOT_FRAME(100, sampleBookSnapshot));
    // delta with cursor <= 100 should be dropped
    ws.emit(DELTA_FRAME(100, { event: 'BOOK_UPDATE', symbol: 'AAPL' }));
    ws.emit(DELTA_FRAME(99,  { event: 'BOOK_UPDATE', symbol: 'AAPL' }));
    // delta with cursor > 100 should pass
    ws.emit(DELTA_FRAME(101, { event: 'TRADE', tradeId: 't1', price: '180.00', qty: 5 }));

    expect(events).toHaveLength(2);
    expect(events[0]).toEqual({ kind: 'snapshot', data: sampleBookSnapshot });
    expect(events[1]).toEqual({
      kind: 'delta',
      data: { event: 'TRADE', tradeId: 't1', price: '180.00', qty: 5 },
    });
    sub.unsubscribe();
  });

  it('subscribeOrders parses ORDER deltas and forwards snapshot arrays', async () => {
    const events: OrdersStreamEvent[] = [];
    const sub = svc.subscribeOrders().subscribe((e) => events.push(e));
    await Promise.resolve();

    const ws = fakes[0];
    expect(ws.url).toContain('/ws/orders/mine');
    ws.emit(JSON.stringify({
      type: 'snapshot', channel: 'orders:u1', cursor: 5, payload: [],
    }));
    ws.emit(JSON.stringify({
      type: 'delta', channel: 'orders:u1', cursor: 6,
      payload: { event: 'ORDER', orderId: 'o1', status: 'FILLED', filledQty: 100 },
    }));

    expect(events).toHaveLength(2);
    expect(events[0]).toEqual({ kind: 'snapshot', data: [] });
    expect(events[1]).toEqual({
      kind: 'delta',
      data: { event: 'ORDER', orderId: 'o1', status: 'FILLED', filledQty: 100 },
    });
    sub.unsubscribe();
  });

  it('skips malformed frames silently', async () => {
    const events: BookStreamEvent[] = [];
    svc.subscribeBook('AAPL').subscribe((e) => events.push(e));
    await Promise.resolve();
    const ws = fakes[0];
    ws.emit('not json');
    ws.emit(SNAPSHOT_FRAME(1, sampleBookSnapshot));
    expect(events).toHaveLength(1);
  });

  // ------------- 3.3 refcount -------------

  it('shares one underlying socket across consumers and closes it after the last unsubscribe', async () => {
    const subs: Subscription[] = [];
    subs.push(svc.subscribeBook('AAPL').subscribe());
    subs.push(svc.subscribeBook('AAPL').subscribe());
    await Promise.resolve();

    expect(fakes).toHaveLength(1);
    expect(svc.bookSubscriberCount('AAPL')).toBe(2);

    subs[0].unsubscribe();
    expect(fakes[0].readyState).toBe(1); // socket still open
    expect(svc.bookSubscriberCount('AAPL')).toBe(1);

    subs[1].unsubscribe();
    expect(fakes[0].readyState).toBe(3); // closed after last unsub
    expect(svc.bookSubscriberCount('AAPL')).toBe(0);
  });

  it('uses separate sockets per symbol', async () => {
    svc.subscribeBook('AAPL').subscribe();
    svc.subscribeBook('MSFT').subscribe();
    await Promise.resolve();
    expect(fakes).toHaveLength(2);
    expect(fakes.map((f) => f.url)).toEqual([
      expect.stringContaining('/ws/book/AAPL'),
      expect.stringContaining('/ws/book/MSFT'),
    ]);
  });

  // ------------- 3.2 reconnect -------------

  it('reconnects after an unexpected close and treats the next snapshot as fresh state', async () => {
    vi.useFakeTimers();
    const events: BookStreamEvent[] = [];
    svc.subscribeBook('AAPL').subscribe((e) => events.push(e));
    await Promise.resolve();

    // First connection: snapshot at cursor 100, then unexpected close.
    fakes[0].emit(SNAPSHOT_FRAME(100, sampleBookSnapshot));
    fakes[0].closeFromServer(1006);

    // Advance past the maximum jittered delay for attempt 0 (≤ 300 ms).
    await vi.advanceTimersByTimeAsync(400);

    expect(fakes).toHaveLength(2);
    // Old cursor floor must have been reset by the new snapshot — a delta
    // at cursor 50 (below the OLD floor of 100) now passes through.
    fakes[1].emit(SNAPSHOT_FRAME(40, sampleBookSnapshot));
    fakes[1].emit(DELTA_FRAME(50, { event: 'BOOK_UPDATE', symbol: 'AAPL' }));

    expect(events).toHaveLength(3);
    expect(events[1].kind).toBe('snapshot');
    expect(events[2].kind).toBe('delta');
    vi.useRealTimers();
  });

  it('does NOT reconnect after a deliberate (unsubscribe-driven) close', async () => {
    vi.useFakeTimers();
    const sub = svc.subscribeBook('AAPL').subscribe();
    await Promise.resolve();
    sub.unsubscribe();
    await vi.advanceTimersByTimeAsync(2000);
    expect(fakes).toHaveLength(1);
    vi.useRealTimers();
  });

  // ------------- 3.4 auth integration -------------

  it('does NOT open a socket when AuthService is unauthenticated', async () => {
    auth.authed.set(false);
    svc.subscribeBook('AAPL').subscribe();
    await Promise.resolve();
    expect(fakes).toHaveLength(0);
  });

  it('closes every open socket when auth signal flips false', async () => {
    svc.subscribeBook('AAPL').subscribe();
    svc.subscribeOrders().subscribe();
    await Promise.resolve();
    expect(fakes).toHaveLength(2);
    auth.authed.set(false);
    TestBed.tick(); // flush the auth-signal effect
    expect(fakes.every((f) => f.readyState === 3)).toBe(true);
  });

  it('aborts a pending reconnect once auth flips false', async () => {
    vi.useFakeTimers();
    svc.subscribeBook('AAPL').subscribe();
    await vi.advanceTimersByTimeAsync(0);
    fakes[0].closeFromServer(1006);

    auth.authed.set(false);
    TestBed.tick();

    // Allow the backoff timer to tick if it were still scheduled.
    await vi.advanceTimersByTimeAsync(2000);
    expect(fakes).toHaveLength(1);
    vi.useRealTimers();
  });
});

describe('backoffMs', () => {
  it('starts at 250ms (±20% jitter) on attempt 0', () => {
    const v = backoffMs(0);
    expect(v).toBeGreaterThanOrEqual(200);
    expect(v).toBeLessThanOrEqual(300);
  });

  it('grows exponentially and caps at 5000ms', () => {
    // attempt 5: 250 * 32 = 8000 → capped to 5000 (±20%).
    const v = backoffMs(8);
    expect(v).toBeGreaterThanOrEqual(4000);
    expect(v).toBeLessThanOrEqual(6000);
  });
});
