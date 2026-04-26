import { TestBed, ComponentFixture } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Subject, of } from 'rxjs';

import { ApiService } from '../core/api.service';
import { ToastService } from '../shared/toast.service';
import { WsService } from '../core/ws.service';
import { SymbolDetailPage } from './symbol-detail.page';
import type {
  BookSnapshot,
  BookStreamEvent,
  BookTotals,
  SubmitOrderResponse,
} from '../core/models';

class FakeApi {
  totals: BookTotals = { demand: 0, supply: 0 };
  bookCalls: string[] = [];
  totalsCalls: string[] = [];
  bookResponse = (sym: string): BookSnapshot => ({
    symbol: sym, bids: [], asks: [], last: null, cursor: 0,
  });
  getBook = vi.fn((sym: string) => {
    this.bookCalls.push(sym);
    return of(this.bookResponse(sym));
  });
  getTotals = vi.fn((sym: string) => {
    this.totalsCalls.push(sym);
    return of(this.totals);
  });
  submitOrder = vi.fn(() => of<SubmitOrderResponse>({
    orderId: 'o1', status: 'OPEN', filledQty: 0, idempotentReplay: false,
  }));
}

class FakeWs {
  streams = new Map<string, Subject<BookStreamEvent>>();
  subscribeBook = vi.fn((symbol: string) => {
    let s = this.streams.get(symbol);
    if (!s) { s = new Subject<BookStreamEvent>(); this.streams.set(symbol, s); }
    return s.asObservable();
  });
  emit(symbol: string, event: BookStreamEvent) {
    this.streams.get(symbol)?.next(event);
  }
}

function makeFixture(symbol = 'AAPL'): {
  fixture: ComponentFixture<SymbolDetailPage>;
  api: FakeApi;
  ws: FakeWs;
} {
  const api = new FakeApi();
  const ws = new FakeWs();
  TestBed.configureTestingModule({
    imports: [SymbolDetailPage],
    providers: [
      provideRouter([]),
      { provide: ApiService, useValue: api },
      { provide: WsService, useValue: ws },
      { provide: ToastService, useValue: { show: vi.fn(), dismiss: vi.fn(), current: () => null } },
      {
        provide: ActivatedRoute,
        useValue: { snapshot: { paramMap: convertToParamMap({ symbol }) } },
      },
    ],
  });
  const fixture = TestBed.createComponent(SymbolDetailPage);
  fixture.detectChanges();
  return { fixture, api, ws };
}

describe('SymbolDetailPage', () => {
  it('renders the symbol header, Back button, order book, totals, and place-order form', () => {
    const { fixture } = makeFixture('AAPL');
    const html = fixture.nativeElement as HTMLElement;
    expect(html.querySelector('h2')?.textContent).toContain('AAPL');
    expect(html.querySelector('a.back')?.getAttribute('href')).toBe('/');
    expect(html.querySelector('app-order-book')).toBeTruthy();
    expect(html.querySelector('app-place-order-form')).toBeTruthy();
    expect(html.querySelector('.totals')?.textContent).toContain('Total Demand');
  });

  it('subscribes to subscribeBook(:symbol) and fetches initial totals on init', () => {
    const { ws, api } = makeFixture('AAPL');
    expect(ws.subscribeBook).toHaveBeenCalledWith('AAPL');
    expect(api.totalsCalls).toEqual(['AAPL']);
  });

  it('snapshot frame populates the order book + last price', () => {
    const { fixture, ws } = makeFixture('AAPL');
    ws.emit('AAPL', {
      kind: 'snapshot',
      data: {
        symbol: 'AAPL',
        bids: [{ price: '180.00', qty: 50, userCount: 1 }],
        asks: [
          { price: '180.50', qty: 80,  userCount: 1 },
          { price: '181.00', qty: 100, userCount: 1 },
          { price: '182.00', qty: 150, userCount: 1 },
        ],
        last: '180.50',
        cursor: 1,
      },
    });
    fixture.detectChanges();
    const totalsHtml = (fixture.nativeElement.querySelector('.totals') as HTMLElement).textContent ?? '';
    expect(totalsHtml).toContain('Last:');
    expect(totalsHtml).toContain('180.50');
    // First bid row matches the §6.3 wireframe (Qty=50, Price=180.00, Users=1).
    const bidRow = fixture.nativeElement.querySelectorAll('app-order-book .side tbody tr')[0] as HTMLElement;
    const cells = Array.from(bidRow.querySelectorAll('td')).map((t) => t.textContent?.trim());
    expect(cells).toEqual(['50', '180.00', '1']);
  });

  it('TRADE delta updates the Last price in place', () => {
    const { fixture, ws } = makeFixture('AAPL');
    ws.emit('AAPL', { kind: 'snapshot', data: { symbol: 'AAPL', bids: [], asks: [], last: '180.00', cursor: 1 } });
    ws.emit('AAPL', { kind: 'delta', data: { event: 'TRADE', tradeId: 't1', price: '181.25', qty: 5 } });
    fixture.detectChanges();
    expect((fixture.nativeElement.querySelector('.totals') as HTMLElement).textContent).toContain('181.25');
  });

  it('BOOK_UPDATE delta refetches both /api/book/{sym} and /api/book/{sym}/totals', () => {
    const { ws, api } = makeFixture('AAPL');
    api.bookCalls.length = 0;
    api.totalsCalls.length = 0;
    ws.emit('AAPL', { kind: 'delta', data: { event: 'BOOK_UPDATE', symbol: 'AAPL' } });
    expect(api.bookCalls).toEqual(['AAPL']);
    expect(api.totalsCalls).toEqual(['AAPL']);
  });

  it('totals update from getTotals response (full-book Demand/Supply)', () => {
    const { fixture, api, ws } = makeFixture('AAPL');
    api.totals = { demand: 50, supply: 330 };
    ws.emit('AAPL', { kind: 'delta', data: { event: 'BOOK_UPDATE', symbol: 'AAPL' } });
    fixture.detectChanges();
    const text = (fixture.nativeElement.querySelector('.totals') as HTMLElement).textContent ?? '';
    expect(text).toMatch(/Total Demand:\s*50/);
    expect(text).toMatch(/Total Supply:\s*330/);
  });
});
