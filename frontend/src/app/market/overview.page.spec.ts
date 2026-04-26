import { TestBed, ComponentFixture } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of } from 'rxjs';

import { OverviewPage } from './overview.page';
import { ApiService } from '../core/api.service';
import { WsService } from '../core/ws.service';
import type {
  BookSnapshot,
  BookStreamEvent,
  SymbolRow,
} from '../core/models';

class FakeApi {
  symbols: SymbolRow[] = [
    { symbol: 'AAPL',  name: 'Apple Inc.',     refPrice: '180.00' },
    { symbol: 'MSFT',  name: 'Microsoft Corp', refPrice: '420.00' },
    { symbol: 'AMZN',  name: 'Amazon.com',     refPrice: '170.00' },
  ];
  bookCalls: string[] = [];
  bookResponse = (symbol: string): BookSnapshot => ({
    symbol, bids: [], asks: [], last: null, cursor: 0,
  });
  getSymbols = vi.fn(() => of(this.symbols as readonly SymbolRow[]));
  getBook = vi.fn((sym: string) => {
    this.bookCalls.push(sym);
    return of(this.bookResponse(sym));
  });
}

class FakeWs {
  streams = new Map<string, Subject<BookStreamEvent>>();
  subscribeBook = vi.fn((symbol: string) => {
    let s = this.streams.get(symbol);
    if (!s) {
      s = new Subject<BookStreamEvent>();
      this.streams.set(symbol, s);
    }
    return s.asObservable();
  });
  emit(symbol: string, event: BookStreamEvent) {
    this.streams.get(symbol)?.next(event);
  }
}

function makeFixture(): {
  fixture: ComponentFixture<OverviewPage>;
  api: FakeApi;
  ws: FakeWs;
} {
  const api = new FakeApi();
  const ws = new FakeWs();
  TestBed.configureTestingModule({
    imports: [OverviewPage],
    providers: [
      provideRouter([]),
      { provide: ApiService, useValue: api },
      { provide: WsService, useValue: ws },
    ],
  });
  const fixture = TestBed.createComponent(OverviewPage);
  fixture.detectChanges();
  return { fixture, api, ws };
}

const SNAPSHOT = (sym: string, opts: Partial<BookSnapshot> = {}): BookStreamEvent => ({
  kind: 'snapshot',
  data: {
    symbol: sym,
    bids: [],
    asks: [],
    last: null,
    cursor: 1,
    ...opts,
  },
});

describe('OverviewPage', () => {
  it('renders the §6.2 column header row', () => {
    const { fixture } = makeFixture();
    const headers = (fixture.nativeElement as HTMLElement)
      .querySelectorAll('thead th');
    expect(Array.from(headers).map((h) => h.textContent?.trim())).toEqual([
      'Symbol', 'Last', 'Best Bid', 'Best Ask', 'Demand', 'Supply', 'View',
    ]);
  });

  it('renders one row per symbol returned by getSymbols()', () => {
    const { fixture } = makeFixture();
    const rows = fixture.nativeElement.querySelectorAll('tbody tr');
    expect(rows).toHaveLength(3);
    expect((rows[0] as HTMLElement).getAttribute('data-symbol')).toBe('AAPL');
  });

  it('subscribes to subscribeBook(symbol) for each symbol once symbols arrive', () => {
    const { ws } = makeFixture();
    expect(ws.subscribeBook).toHaveBeenCalledTimes(3);
    expect(ws.subscribeBook).toHaveBeenCalledWith('AAPL');
    expect(ws.subscribeBook).toHaveBeenCalledWith('MSFT');
    expect(ws.subscribeBook).toHaveBeenCalledWith('AMZN');
  });

  it('shows dashes for empty Last/Best Bid/Best Ask and zero for empty Demand/Supply (AMZN-style row)', () => {
    const { fixture, ws } = makeFixture();
    ws.emit('AMZN', SNAPSHOT('AMZN'));
    fixture.detectChanges();
    const tds = rowCells(fixture, 'AMZN');
    expect(tds).toEqual(['AMZN', '-', '-', '-', '0', '0', 'Open']);
  });

  it('populates Best Bid/Ask + Demand/Supply on snapshot, sums qty across visible levels', () => {
    const { fixture, ws } = makeFixture();
    ws.emit('AAPL', SNAPSHOT('AAPL', {
      last: '180.50',
      bids: [
        { price: '180.00', qty: 30, userCount: 1 },
        { price: '179.50', qty: 20, userCount: 1 },
      ],
      asks: [
        { price: '180.50', qty: 200, userCount: 2 },
        { price: '181.00', qty: 130, userCount: 1 },
      ],
    }));
    fixture.detectChanges();
    const tds = rowCells(fixture, 'AAPL');
    expect(tds).toEqual(['AAPL', '180.50', '180.00', '180.50', '50', '330', 'Open']);
  });

  it('TRADE delta updates last price in place, no REST refetch', () => {
    const { fixture, ws, api } = makeFixture();
    ws.emit('AAPL', SNAPSHOT('AAPL', { last: '180.00' }));
    fixture.detectChanges();
    ws.emit('AAPL', {
      kind: 'delta',
      data: { event: 'TRADE', tradeId: 't1', price: '180.50', qty: 10 },
    });
    fixture.detectChanges();
    expect(rowCells(fixture, 'AAPL')[1]).toBe('180.50');
    expect(api.getBook).not.toHaveBeenCalled();
  });

  it('BOOK_UPDATE delta triggers a REST getBook(symbol) refetch', () => {
    const { fixture, ws, api } = makeFixture();
    ws.emit('AAPL', SNAPSHOT('AAPL'));
    fixture.detectChanges();
    api.bookResponse = (sym) => ({
      symbol: sym,
      bids: [{ price: '180.00', qty: 100, userCount: 1 }],
      asks: [],
      last: '180.50',
      cursor: 5,
    });
    ws.emit('AAPL', {
      kind: 'delta',
      data: { event: 'BOOK_UPDATE', symbol: 'AAPL' },
    });
    fixture.detectChanges();
    expect(api.bookCalls).toEqual(['AAPL']);
    expect(rowCells(fixture, 'AAPL')[2]).toBe('180.00'); // Best Bid filled in by refetch
    expect(rowCells(fixture, 'AAPL')[4]).toBe('100');    // Demand
  });

  it('Open button routes to /symbol/{symbol}', () => {
    const { fixture } = makeFixture();
    const link = fixture.nativeElement.querySelector(
      'tr[data-symbol="AAPL"] a.open-btn',
    ) as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/symbol/AAPL');
  });

  it('indicator reads "Connecting…" before any snapshot, "Live" once every symbol has snapshotted', () => {
    const { fixture, ws } = makeFixture();

    // Initial — no streams have produced a snapshot yet.
    expect(indicatorText(fixture)).toContain('Connecting');

    ws.emit('AAPL', SNAPSHOT('AAPL'));
    fixture.detectChanges();
    expect(indicatorText(fixture)).toContain('Connecting'); // others still pending

    ws.emit('MSFT', SNAPSHOT('MSFT'));
    ws.emit('AMZN', SNAPSHOT('AMZN'));
    fixture.detectChanges();
    expect(indicatorText(fixture)).toContain('Live');
  });
});

function rowCells(fixture: ComponentFixture<OverviewPage>, symbol: string): string[] {
  const row = fixture.nativeElement.querySelector(`tr[data-symbol="${symbol}"]`) as HTMLTableRowElement;
  return Array.from(row.querySelectorAll('td')).map((td) => td.textContent?.trim() ?? '');
}

function indicatorText(fixture: ComponentFixture<OverviewPage>): string {
  return (fixture.nativeElement.querySelector('.indicator') as HTMLElement)
    .textContent?.trim() ?? '';
}
