import {
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { ApiService } from '../core/api.service';
import type {
  BookLevel,
  BookSnapshot,
  BookStreamEvent,
  BookTotals,
} from '../core/models';
import { WsService } from '../core/ws.service';
import { OrderBookComponent } from './order-book.component';
import { PlaceOrderForm } from './place-order.form';

interface BookState {
  readonly bids: readonly BookLevel[];
  readonly asks: readonly BookLevel[];
  readonly last: string | null;
}

const EMPTY_BOOK: BookState = { bids: [], asks: [], last: null };

/**
 * §6.3 Symbol Detail. Composes:
 *
 * <ul>
 *   <li>{@link OrderBookComponent} — top-5 levels both sides via the
 *       same {@code WsService.subscribeBook} stream Phase 4 used.</li>
 *   <li>{@link PlaceOrderForm} — submits orders for {@code :symbol}.</li>
 * </ul>
 *
 * <p>Total Demand/Supply (§5.3) come from {@code /api/book/{sym}/totals}
 * because the WS snapshot only carries top-5 levels. We refresh totals
 * on every {@code BOOK_UPDATE} delta — backend tickle, frontend pulls.
 */
@Component({
  selector: 'app-symbol-detail',
  imports: [RouterLink, OrderBookComponent, PlaceOrderForm],
  template: `
    <section class="symbol-detail">
      <header class="symbol-header">
        <h2>{{ symbolCode() }}</h2>
        <a routerLink="/" class="back">&lt; Back</a>
      </header>

      <app-order-book [bids]="book().bids" [asks]="book().asks" />

      <p class="totals">
        Total Demand: <strong>{{ totals().demand }}</strong> &nbsp;|&nbsp;
        Total Supply: <strong>{{ totals().supply }}</strong> &nbsp;|&nbsp;
        Last: <strong>{{ book().last ?? '-' }}</strong>
      </p>

      <app-place-order-form [symbol]="symbolCode()" />
    </section>
  `,
  styles: [`
    .symbol-detail { max-width: 60rem; }
    .symbol-header { display: flex; align-items: baseline; justify-content: space-between; }
    h2 { margin: 0 0 0.8rem; }
    .back { text-decoration: none; color: inherit; }
    .back:hover { text-decoration: underline; }
    .totals { margin: 1rem 0 1.5rem; padding: 0.6rem 0.8rem; background: #f8f8f8; border-radius: 3px; }
  `],
})
export class SymbolDetailPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly ws = inject(WsService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly symbolCode = signal<string>('');
  protected readonly book = signal<BookState>(EMPTY_BOOK);
  protected readonly totals = signal<BookTotals>({ demand: 0, supply: 0 });

  ngOnInit(): void {
    const sym = this.route.snapshot.paramMap.get('symbol') ?? '';
    this.symbolCode.set(sym);
    this.refreshTotals(sym);
    this.ws
      .subscribeBook(sym)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((event) => this.applyEvent(sym, event));
  }

  private applyEvent(sym: string, event: BookStreamEvent): void {
    if (event.kind === 'snapshot') {
      this.book.set({
        bids: event.data.bids,
        asks: event.data.asks,
        last: event.data.last,
      });
      return;
    }
    if (event.data.event === 'TRADE') {
      this.book.update((b) => ({ ...b, last: event.data.event === 'TRADE' ? event.data.price : b.last }));
      return;
    }
    if (event.data.event === 'BOOK_UPDATE') {
      this.refreshSnapshot(sym);
      this.refreshTotals(sym);
    }
  }

  private refreshSnapshot(sym: string): void {
    this.api
      .getBook(sym)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((snap: BookSnapshot) => {
        this.book.update((b) => ({
          bids: snap.bids,
          asks: snap.asks,
          last: snap.last ?? b.last,
        }));
      });
  }

  private refreshTotals(sym: string): void {
    this.api
      .getTotals(sym)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((t) => this.totals.set(t));
  }
}
