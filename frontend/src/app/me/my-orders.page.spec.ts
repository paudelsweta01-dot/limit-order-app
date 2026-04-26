import { TestBed, ComponentFixture } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';

import { ApiService } from '../core/api.service';
import { ToastService } from '../shared/toast.service';
import { WsService } from '../core/ws.service';
import { MyOrdersPage } from './my-orders.page';
import type {
  CancelOrderResponse,
  MyOrder,
  OrdersStreamEvent,
} from '../core/models';

class FakeApi {
  initial: MyOrder[] = [];
  cancelResponse: Subject<CancelOrderResponse> | null = null;
  cancelCalls: string[] = [];
  getMyOrders = vi.fn(() => of(this.initial as readonly MyOrder[]));
  cancelOrder = vi.fn((id: string) => {
    this.cancelCalls.push(id);
    return this.cancelResponse?.asObservable() ?? of({ orderId: id, status: 'CANCELLED', filledQty: 0 });
  });
}

class FakeWs {
  subj = new Subject<OrdersStreamEvent>();
  subscribeOrders = vi.fn(() => this.subj.asObservable());
  emit(event: OrdersStreamEvent) { this.subj.next(event); }
}

class FakeToast {
  shown: { msg: string; kind: string }[] = [];
  show = vi.fn((msg: string, kind: 'success' | 'error' = 'success') => this.shown.push({ msg, kind }));
  dismiss = vi.fn();
  current = () => null;
}

const sampleOrder = (over: Partial<MyOrder>): MyOrder => ({
  orderId: 'o1',
  clientOrderId: 'c1',
  symbol: 'AAPL',
  side: 'BUY',
  type: 'LIMIT',
  price: '180.50',
  quantity: 100,
  filledQty: 0,
  status: 'OPEN',
  createdAt: '2026-04-21T10:00:00Z',
  updatedAt: '2026-04-21T10:00:00Z',
  ...over,
});

function makeFixture(): {
  fixture: ComponentFixture<MyOrdersPage>;
  api: FakeApi;
  ws: FakeWs;
  toast: FakeToast;
} {
  const api = new FakeApi();
  const ws = new FakeWs();
  const toast = new FakeToast();
  TestBed.configureTestingModule({
    imports: [MyOrdersPage],
    providers: [
      { provide: ApiService, useValue: api },
      { provide: WsService, useValue: ws },
      { provide: ToastService, useValue: toast },
    ],
  });
  const fixture = TestBed.createComponent(MyOrdersPage);
  return { fixture, api, ws, toast };
}

describe('MyOrdersPage', () => {
  it('shows the empty state when getMyOrders returns no rows', () => {
    const { fixture } = makeFixture();
    fixture.detectChanges();
    expect((fixture.nativeElement.querySelector('.empty') as HTMLElement).textContent).toContain('No open orders');
  });

  it('shows "Loading…" while getMyOrders is still in flight (distinct from empty state)', () => {
    const { fixture, api } = makeFixture();
    const pending = new Subject<readonly MyOrder[]>();
    api.getMyOrders = vi.fn(() => pending.asObservable());
    fixture.detectChanges();
    expect((fixture.nativeElement.querySelector('.empty') as HTMLElement).textContent?.trim()).toBe('Loading…');

    pending.next([]);
    fixture.detectChanges();
    expect((fixture.nativeElement.querySelector('.empty') as HTMLElement).textContent?.trim()).toBe('No open orders');
  });

  it('renders one row per order from getMyOrders, columns matching §6.4', () => {
    const { fixture, api } = makeFixture();
    api.initial = [sampleOrder({})];
    fixture.detectChanges();
    const cells = Array.from(fixture.nativeElement.querySelectorAll('tr[data-order-id="o1"] td'))
      .map((c) => (c as HTMLElement).textContent?.trim());
    // OrderId is shortened to 8 chars, then symbol/side/type/price/qty/filled/status.
    expect(cells.slice(0, 8)).toEqual(['o1', 'AAPL', 'BUY', 'LIMIT', '180.50', '100', '0', 'OPEN']);
  });

  it('shows "-" for MARKET orders with null price', () => {
    const { fixture, api } = makeFixture();
    api.initial = [sampleOrder({ orderId: 'm1', type: 'MARKET', price: null })];
    fixture.detectChanges();
    const priceCell = fixture.nativeElement.querySelectorAll('tr[data-order-id="m1"] td')[4] as HTMLElement;
    expect(priceCell.textContent?.trim()).toBe('-');
  });

  it('cancel button is visible only on OPEN/PARTIAL rows', () => {
    const { fixture, api } = makeFixture();
    api.initial = [
      sampleOrder({ orderId: 'open',    status: 'OPEN'      }),
      sampleOrder({ orderId: 'partial', status: 'PARTIAL'   }),
      sampleOrder({ orderId: 'filled',  status: 'FILLED'    }),
      sampleOrder({ orderId: 'cancel',  status: 'CANCELLED' }),
      sampleOrder({ orderId: 'reject',  status: 'REJECTED'  }),
    ];
    fixture.detectChanges();
    const has = (id: string) => !!fixture.nativeElement.querySelector(`tr[data-order-id="${id}"] button.cancel-btn`);
    expect(has('open')).toBe(true);
    expect(has('partial')).toBe(true);
    expect(has('filled')).toBe(false);
    expect(has('cancel')).toBe(false);
    expect(has('reject')).toBe(false);
  });

  it('clicking cancel calls cancelOrder(id) once and disables the button while pending', () => {
    const { fixture, api } = makeFixture();
    api.initial = [sampleOrder({})];
    api.cancelResponse = new Subject<CancelOrderResponse>();
    fixture.detectChanges();
    const button = fixture.nativeElement.querySelector('tr[data-order-id="o1"] button.cancel-btn') as HTMLButtonElement;
    button.click();
    fixture.detectChanges();
    expect(api.cancelCalls).toEqual(['o1']);
    expect(button.disabled).toBe(true);
    button.click(); // second click while pending — must not double-fire
    expect(api.cancelCalls).toEqual(['o1']);
  });

  it('cancel error toasts the §4.11 envelope message', () => {
    const { fixture, api, toast } = makeFixture();
    api.initial = [sampleOrder({})];
    api.cancelOrder = vi.fn(() => throwError(() => new HttpErrorResponse({
      error: { code: 'CONFLICT', message: 'order already filled' },
      status: 409,
      statusText: 'Conflict',
    })));
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('button.cancel-btn') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(toast.shown[0]).toEqual({ msg: 'order already filled', kind: 'error' });
  });

  it('ORDER delta updates the row status + filledQty in place', () => {
    const { fixture, api, ws } = makeFixture();
    api.initial = [sampleOrder({})];
    fixture.detectChanges();

    ws.emit({ kind: 'delta', data: { event: 'ORDER', orderId: 'o1', status: 'PARTIAL', filledQty: 30 } });
    fixture.detectChanges();
    const cells = Array.from(fixture.nativeElement.querySelectorAll('tr[data-order-id="o1"] td'))
      .map((c) => (c as HTMLElement).textContent?.trim());
    expect(cells[6]).toBe('30');      // Filled
    expect(cells[7]).toBe('PARTIAL'); // Status
  });

  it('WS snapshot frame replaces the REST-painted list (cursor-consistent authoritative)', () => {
    const { fixture, api, ws } = makeFixture();
    api.initial = [sampleOrder({})];
    fixture.detectChanges();

    // WS arrives — replace with a different list.
    ws.emit({ kind: 'snapshot', data: [
      sampleOrder({ orderId: 'o2', symbol: 'MSFT', createdAt: '2026-04-21T11:00:00Z' }),
    ] });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('tr[data-order-id="o1"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('tr[data-order-id="o2"]')).not.toBeNull();
  });

  it('renders rows newest first by createdAt', () => {
    const { fixture, api } = makeFixture();
    api.initial = [
      sampleOrder({ orderId: 'old', createdAt: '2026-04-21T10:00:00Z' }),
      sampleOrder({ orderId: 'new', createdAt: '2026-04-21T11:00:00Z' }),
      sampleOrder({ orderId: 'mid', createdAt: '2026-04-21T10:30:00Z' }),
    ];
    fixture.detectChanges();
    const ids = Array.from(fixture.nativeElement.querySelectorAll('tbody tr[data-order-id]'))
      .map((r) => (r as HTMLElement).getAttribute('data-order-id'));
    expect(ids).toEqual(['new', 'mid', 'old']);
  });
});
