import {
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { ApiService } from '../core/api.service';
import type { MyFill, OrdersStreamEvent } from '../core/models';
import { WsService } from '../core/ws.service';
import { formatTimestamp, shortId } from '../shared/format';

const FILL_TRIGGER_STATUSES = new Set(['PARTIAL', 'FILLED']);

/**
 * §6.4 (bottom half) — every fill on the authenticated user's orders,
 * newest first. Initial load via {@link ApiService#getMyFills}; the
 * page refetches whenever an ORDER delta on {@link WsService#subscribeOrders}
 * has a status of PARTIAL or FILLED (those are the deltas that imply
 * a trade just touched one of our orders). Plan §6.3 explicitly
 * accepts the extra REST call as the simpler alternative to
 * maintaining a parallel fills list.
 */
@Component({
  selector: 'app-my-fills',
  template: `
    <h3>My Fills</h3>
    <table>
      <thead>
        <tr>
          <th>TradeId</th>
          <th>Symbol</th>
          <th>Side</th>
          <th>Price</th>
          <th>Qty</th>
          <th>Time</th>
          <th>Counter</th>
        </tr>
      </thead>
      <tbody>
        @for (f of fills(); track f.tradeId) {
          <tr [attr.data-trade-id]="f.tradeId">
            <td>{{ short(f.tradeId) }}</td>
            <td>{{ f.symbol }}</td>
            <td>{{ f.side }}</td>
            <td>{{ f.price }}</td>
            <td>{{ f.quantity }}</td>
            <td>{{ formatTime(f.executedAt) }}</td>
            <td>{{ f.counterparty }}</td>
          </tr>
        } @empty {
          <tr><td colspan="7" class="empty">No fills yet</td></tr>
        }
      </tbody>
    </table>
  `,
  styles: [`
    h3 { margin: 1.5rem 0 0.6rem; }
    table { width: 100%; border-collapse: collapse; }
    th, td { padding: 0.3rem 0.6rem; text-align: left; border-bottom: 1px solid #eee; }
    th { font-weight: 600; }
    .empty { color: #888; font-style: italic; text-align: center; }
  `],
})
export class MyFillsPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly ws = inject(WsService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly state = signal<readonly MyFill[]>([]);

  protected readonly fills = computed(() => {
    const list = [...this.state()];
    list.sort((a, b) => (b.executedAt > a.executedAt ? 1 : b.executedAt < a.executedAt ? -1 : 0));
    return list;
  });

  protected readonly short = shortId;
  protected readonly formatTime = formatTimestamp;

  ngOnInit(): void {
    this.refresh();
    this.ws
      .subscribeOrders()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((event) => this.onOrdersEvent(event));
  }

  private onOrdersEvent(event: OrdersStreamEvent): void {
    // The WS snapshot itself carries no fills info, so the only thing
    // worth reacting to here is a delta with a fill-implying status.
    if (event.kind !== 'delta') return;
    if (FILL_TRIGGER_STATUSES.has(event.data.status)) this.refresh();
  }

  private refresh(): void {
    this.api
      .getMyFills()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((rows) => this.state.set(rows));
  }
}
