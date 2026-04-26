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
import { errorMessageOf } from '../core/http.interceptor';
import type { MyOrder, OrderEvent, OrdersStreamEvent } from '../core/models';
import { ToastService } from '../shared/toast.service';
import { WsService } from '../core/ws.service';
import { formatPrice, formatQty, formatTimestamp, shortId } from '../shared/format';
import { HttpErrorResponse } from '@angular/common/http';

const ACTIVE_STATUSES = new Set<MyOrder['status']>(['OPEN', 'PARTIAL']);

/**
 * §6.4 (top half) — every order the authenticated user has submitted,
 * newest first. {@link ApiService#getMyOrders} paints first; the WS
 * snapshot frame replaces that list (it's the cursor-consistent
 * authoritative view), and ORDER deltas patch rows in place.
 *
 * <p>The cancel button is rendered only on OPEN/PARTIAL rows. A click
 * fires {@link ApiService#cancelOrder}; the row's transition to
 * CANCELLED arrives back as an ORDER delta on the same WS — no
 * optimistic update needed (architecture §4.5 + plan §6.2 wording
 * "row updates to CANCELLED via WS within 1s").
 */
@Component({
  selector: 'app-my-orders',
  template: `
    <h3>My Orders</h3>
    <table>
      <thead>
        <tr>
          <th>OrderId</th>
          <th>Symbol</th>
          <th>Side</th>
          <th>Type</th>
          <th>Price</th>
          <th>Qty</th>
          <th>Filled</th>
          <th>Status</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        @for (o of orders(); track o.orderId) {
          <tr [attr.data-order-id]="o.orderId">
            <td>{{ short(o.orderId) }}</td>
            <td>{{ o.symbol }}</td>
            <td>{{ o.side }}</td>
            <td>{{ o.type }}</td>
            <td>{{ price(o.price) }}</td>
            <td>{{ qty(o.quantity) }}</td>
            <td>{{ qty(o.filledQty) }}</td>
            <td>{{ o.status }}</td>
            <td>
              @if (canCancel(o)) {
                <button
                  type="button"
                  class="cancel-btn"
                  [disabled]="pendingCancels().has(o.orderId)"
                  (click)="cancel(o)">[X]</button>
              }
            </td>
          </tr>
        } @empty {
          <tr>
            <td colspan="9" class="empty">
              {{ loaded() ? 'No open orders' : 'Loading…' }}
            </td>
          </tr>
        }
      </tbody>
    </table>
  `,
  styles: [`
    h3 { margin: 0 0 0.6rem; }
    table { width: 100%; border-collapse: collapse; }
    th, td { padding: 0.3rem 0.6rem; text-align: left; border-bottom: 1px solid #eee; }
    th { font-weight: 600; }
    .cancel-btn { padding: 0 0.4rem; cursor: pointer; }
    .cancel-btn:disabled { color: #888; cursor: progress; }
    .empty { color: #888; font-style: italic; text-align: center; }
  `],
})
export class MyOrdersPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly ws = inject(WsService);
  private readonly toast = inject(ToastService);
  private readonly destroyRef = inject(DestroyRef);

  /** Map keyed by orderId for O(1) delta merges. */
  private readonly state = signal<ReadonlyMap<string, MyOrder>>(new Map());
  protected readonly pendingCancels = signal<ReadonlySet<string>>(new Set());
  /** Flips true once the first REST or WS response has landed — until
   *  then the empty-state row reads "Loading…" instead of "No open orders". */
  protected readonly loaded = signal(false);

  protected readonly orders = computed(() => {
    const list = Array.from(this.state().values());
    list.sort((a, b) => (b.createdAt > a.createdAt ? 1 : b.createdAt < a.createdAt ? -1 : 0));
    return list;
  });

  protected readonly short = shortId;
  protected readonly formatTime = formatTimestamp;
  protected readonly price = formatPrice;
  protected readonly qty = formatQty;

  ngOnInit(): void {
    this.api
      .getMyOrders()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((rows) => this.replaceAll(rows));

    this.ws
      .subscribeOrders()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((event) => this.applyEvent(event));
  }

  protected canCancel(o: MyOrder): boolean {
    return ACTIVE_STATUSES.has(o.status);
  }

  protected cancel(o: MyOrder): void {
    if (this.pendingCancels().has(o.orderId)) return;
    this.pendingCancels.update((s) => {
      const next = new Set(s);
      next.add(o.orderId);
      return next;
    });
    this.api
      .cancelOrder(o.orderId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.clearPending(o.orderId);
          // Don't toast on success — the user sees the row flip to
          // CANCELLED via the WS within 1s; an extra toast is noise.
        },
        error: (err: HttpErrorResponse) => {
          this.clearPending(o.orderId);
          this.toast.show(errorMessageOf(err, 'Cancel failed'), 'error');
        },
      });
  }

  // ---------- internals ----------

  private applyEvent(event: OrdersStreamEvent): void {
    if (event.kind === 'snapshot') {
      this.replaceAll(event.data);
      return;
    }
    this.mergeDelta(event.data);
  }

  private replaceAll(rows: readonly MyOrder[]): void {
    const next = new Map<string, MyOrder>();
    for (const r of rows) next.set(r.orderId, r);
    this.state.set(next);
    this.loaded.set(true);
  }

  private mergeDelta(d: OrderEvent): void {
    const cur = this.state().get(d.orderId);
    if (!cur) return; // delta for an order we haven't seen — refresh comes via next snapshot/REST
    const merged: MyOrder = {
      ...cur,
      status: d.status,
      filledQty: d.filledQty,
      // updatedAt isn't on the delta payload; bump locally so any sort
      // by updatedAt (none today, but defensive) stays monotonic.
      updatedAt: new Date().toISOString(),
    };
    this.state.update((map) => {
      const next = new Map(map);
      next.set(d.orderId, merged);
      return next;
    });
  }

  private clearPending(orderId: string): void {
    this.pendingCancels.update((s) => {
      const next = new Set(s);
      next.delete(orderId);
      return next;
    });
  }
}
