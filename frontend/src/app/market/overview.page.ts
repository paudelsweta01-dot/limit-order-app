import {
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';

import { ApiService } from '../core/api.service';
import type {
  BookLevel,
  BookSnapshot,
  BookStreamEvent,
  SymbolRow,
  TradeEvent,
} from '../core/models';
import { WsService } from '../core/ws.service';

interface MarketRow {
  readonly symbol: string;
  readonly name: string;
  readonly state: 'connecting' | 'live';
  readonly bids: readonly BookLevel[];
  readonly asks: readonly BookLevel[];
  readonly last: string | null;
}

/**
 * §6.2 Market Overview — landing page after login.
 *
 * <p>One row per symbol; each row's prices/quantities are kept live by a
 * per-symbol {@link WsService#subscribeBook} subscription:
 *
 * <ul>
 *   <li>Snapshot frame replaces the row's bids/asks/last in one shot.</li>
 *   <li>{@code TRADE} delta updates {@code last} from the trade payload —
 *       cheap, no REST call needed.</li>
 *   <li>{@code BOOK_UPDATE} delta carries no level data, so the page
 *       fetches a fresh {@code /api/book/{symbol}} to keep
 *       Demand/Supply current. At demo scale (5 symbols) the refetch
 *       chatter is negligible; under genuine load Phase 7 can throttle
 *       via {@code switchMap} or backend can start emitting full deltas.</li>
 * </ul>
 *
 * <p>The connection indicator at the bottom flips to "Live" once every
 * subscribed symbol has produced its first snapshot.
 */
@Component({
  selector: 'app-market-overview',
  imports: [RouterLink],
  template: `
    <section class="market-overview">
      <h2>Market Overview</h2>
      <table>
        <thead>
          <tr>
            <th>Symbol</th>
            <th>Last</th>
            <th>Best Bid</th>
            <th>Best Ask</th>
            <th>Demand</th>
            <th>Supply</th>
            <th>View</th>
          </tr>
        </thead>
        <tbody>
          @for (row of rows(); track row.symbol) {
            <tr [attr.data-symbol]="row.symbol">
              <td>{{ row.symbol }}</td>
              <td>{{ row.last ?? '-' }}</td>
              <td>{{ bestBid(row) ?? '-' }}</td>
              <td>{{ bestAsk(row) ?? '-' }}</td>
              <td>{{ demand(row) }}</td>
              <td>{{ supply(row) }}</td>
              <td>
                <a class="open-btn" [routerLink]="['/symbol', row.symbol]">Open</a>
              </td>
            </tr>
          } @empty {
            <tr><td colspan="7" class="empty">Loading symbols…</td></tr>
          }
        </tbody>
      </table>
      <p class="indicator" [class.live]="allLive()">
        <span class="dot" aria-hidden="true">●</span>
        {{ allLive() ? 'Live' : 'Connecting…' }}
      </p>
    </section>
  `,
  styles: [`
    .market-overview { max-width: 60rem; }
    h2 { margin: 0 0 1rem; }
    table { width: 100%; border-collapse: collapse; }
    th, td { padding: 0.4rem 0.6rem; text-align: left; border-bottom: 1px solid #eee; }
    th { font-weight: 600; }
    td.empty { color: #888; font-style: italic; text-align: center; }
    .open-btn {
      display: inline-block; padding: 0.2rem 0.6rem; border: 1px solid #aaa;
      border-radius: 3px; text-decoration: none; color: inherit;
    }
    .open-btn:hover { background: #f4f4f4; }
    .indicator { margin-top: 1rem; color: #b8860b; }
    .indicator.live { color: #2a7a2a; }
    .dot { font-size: 1.1em; vertical-align: middle; }
  `],
})
export class OverviewPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly ws = inject(WsService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly rows = signal<readonly MarketRow[]>([]);
  protected readonly allLive = computed(() => {
    const r = this.rows();
    return r.length > 0 && r.every((row) => row.state === 'live');
  });

  ngOnInit(): void {
    this.api
      .getSymbols()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((symbols) => this.bootstrap(symbols));
  }

  private bootstrap(symbols: readonly SymbolRow[]): void {
    this.rows.set(symbols.map((s) => ({
      symbol: s.symbol,
      name: s.name,
      state: 'connecting',
      bids: [],
      asks: [],
      last: null,
    })));
    for (const s of symbols) {
      this.ws
        .subscribeBook(s.symbol)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe((event) => this.applyEvent(s.symbol, event));
    }
  }

  private applyEvent(symbol: string, event: BookStreamEvent): void {
    if (event.kind === 'snapshot') {
      this.replaceRow(symbol, (row) => ({
        ...row,
        state: 'live',
        bids: event.data.bids,
        asks: event.data.asks,
        last: event.data.last,
      }));
      return;
    }
    if (event.data.event === 'TRADE') {
      const trade = event.data as TradeEvent;
      this.replaceRow(symbol, (row) => ({ ...row, last: trade.price }));
      return;
    }
    if (event.data.event === 'BOOK_UPDATE') {
      // No level payload on BOOK_UPDATE — fetch fresh levels via REST so
      // Demand/Supply stay live. Cheap at demo scale.
      this.api
        .getBook(symbol)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe((snapshot: BookSnapshot) => {
          this.replaceRow(symbol, (row) => ({
            ...row,
            bids: snapshot.bids,
            asks: snapshot.asks,
            last: snapshot.last ?? row.last,
          }));
        });
    }
  }

  private replaceRow(symbol: string, mut: (row: MarketRow) => MarketRow): void {
    this.rows.update((rows) =>
      rows.map((r) => (r.symbol === symbol ? mut(r) : r)),
    );
  }

  // ---------- template helpers ----------

  protected bestBid(row: MarketRow): string | null {
    return row.bids[0]?.price ?? null;
  }

  protected bestAsk(row: MarketRow): string | null {
    return row.asks[0]?.price ?? null;
  }

  protected demand(row: MarketRow): number {
    return row.bids.reduce((acc, lvl) => acc + lvl.qty, 0);
  }

  protected supply(row: MarketRow): number {
    return row.asks.reduce((acc, lvl) => acc + lvl.qty, 0);
  }
}
