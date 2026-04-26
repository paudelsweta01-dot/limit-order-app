import { TestBed, ComponentFixture } from '@angular/core/testing';
import { Subject, of } from 'rxjs';

import { ApiService } from '../core/api.service';
import { WsService } from '../core/ws.service';
import { MyFillsPage } from './my-fills.page';
import type { MyFill, OrdersStreamEvent } from '../core/models';

class FakeApi {
  current: MyFill[] = [];
  fillsCalls = 0;
  getMyFills = vi.fn(() => {
    this.fillsCalls++;
    return of([...this.current] as readonly MyFill[]);
  });
}

class FakeWs {
  subj = new Subject<OrdersStreamEvent>();
  subscribeOrders = vi.fn(() => this.subj.asObservable());
  emit(event: OrdersStreamEvent) { this.subj.next(event); }
}

const sampleFill = (over: Partial<MyFill>): MyFill => ({
  tradeId: 't1',
  symbol: 'AAPL',
  side: 'BUY',
  price: '180.50',
  quantity: 120,
  executedAt: '2026-04-21T10:14:02Z',
  counterparty: 'u2',
  ...over,
});

function makeFixture(): {
  fixture: ComponentFixture<MyFillsPage>;
  api: FakeApi;
  ws: FakeWs;
} {
  const api = new FakeApi();
  const ws = new FakeWs();
  TestBed.configureTestingModule({
    imports: [MyFillsPage],
    providers: [
      { provide: ApiService, useValue: api },
      { provide: WsService, useValue: ws },
    ],
  });
  const fixture = TestBed.createComponent(MyFillsPage);
  return { fixture, api, ws };
}

describe('MyFillsPage', () => {
  it('shows the empty state when getMyFills returns no rows', () => {
    const { fixture } = makeFixture();
    fixture.detectChanges();
    expect((fixture.nativeElement.querySelector('.empty') as HTMLElement).textContent).toContain('No fills yet');
  });

  it('shows "Loading…" while the initial getMyFills is still in flight', () => {
    const { fixture, api } = makeFixture();
    const pending = new Subject<readonly MyFill[]>();
    api.getMyFills = vi.fn(() => pending.asObservable());
    fixture.detectChanges();
    expect((fixture.nativeElement.querySelector('.empty') as HTMLElement).textContent?.trim()).toBe('Loading…');

    pending.next([]);
    fixture.detectChanges();
    expect((fixture.nativeElement.querySelector('.empty') as HTMLElement).textContent?.trim()).toBe('No fills yet');
  });

  it('renders one row per fill, columns matching §6.4', () => {
    const { fixture, api } = makeFixture();
    api.current = [sampleFill({})];
    fixture.detectChanges();
    const cells = Array.from(fixture.nativeElement.querySelectorAll('tr[data-trade-id="t1"] td'))
      .map((c) => (c as HTMLElement).textContent?.trim());
    // tradeId is shortened to 8 chars (here 't1'), then symbol/side/price/qty/time/counter.
    expect(cells.slice(0, 5)).toEqual(['t1', 'AAPL', 'BUY', '180.50', '120']);
    expect(cells[5]).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
    expect(cells[6]).toBe('u2');
  });

  it('refetches on FILLED ORDER deltas', () => {
    const { fixture, api, ws } = makeFixture();
    fixture.detectChanges();
    expect(api.fillsCalls).toBe(1); // initial load

    ws.emit({ kind: 'delta', data: { event: 'ORDER', orderId: 'o1', status: 'FILLED', filledQty: 100 } });
    expect(api.fillsCalls).toBe(2);
  });

  it('refetches on PARTIAL ORDER deltas', () => {
    const { fixture, api, ws } = makeFixture();
    fixture.detectChanges();
    ws.emit({ kind: 'delta', data: { event: 'ORDER', orderId: 'o1', status: 'PARTIAL', filledQty: 50 } });
    expect(api.fillsCalls).toBe(2);
  });

  it('does NOT refetch on CANCELLED / REJECTED / OPEN deltas (no fill implied)', () => {
    const { fixture, api, ws } = makeFixture();
    fixture.detectChanges();
    api.fillsCalls = 0;
    ws.emit({ kind: 'delta', data: { event: 'ORDER', orderId: 'o1', status: 'CANCELLED', filledQty: 0 } });
    ws.emit({ kind: 'delta', data: { event: 'ORDER', orderId: 'o2', status: 'REJECTED',  filledQty: 0 } });
    ws.emit({ kind: 'delta', data: { event: 'ORDER', orderId: 'o3', status: 'OPEN',      filledQty: 0 } });
    expect(api.fillsCalls).toBe(0);
  });

  it('renders fills newest first by executedAt', () => {
    const { fixture, api } = makeFixture();
    api.current = [
      sampleFill({ tradeId: 'old', executedAt: '2026-04-21T10:00:00Z' }),
      sampleFill({ tradeId: 'new', executedAt: '2026-04-21T11:00:00Z' }),
      sampleFill({ tradeId: 'mid', executedAt: '2026-04-21T10:30:00Z' }),
    ];
    fixture.detectChanges();
    const ids = Array.from(fixture.nativeElement.querySelectorAll('tbody tr[data-trade-id]'))
      .map((r) => (r as HTMLElement).getAttribute('data-trade-id'));
    expect(ids).toEqual(['new', 'mid', 'old']);
  });
});
