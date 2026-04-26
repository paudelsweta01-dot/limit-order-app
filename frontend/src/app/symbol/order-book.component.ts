import { Component, computed, input } from '@angular/core';

import type { BookLevel } from '../core/models';

const LEVELS_PER_SIDE = 5;

/**
 * §6.3 Order Book — pure presentation. Two side-by-side tables (BIDS,
 * ASKS), each with five fixed rows of {Qty, Price, Users}. Missing
 * levels render as blank rows so the layout doesn't shift as fills
 * pop levels off the top of either side.
 */
@Component({
  selector: 'app-order-book',
  template: `
    <div class="book-grid">
      <div class="side">
        <h3>BIDS (Demand)</h3>
        <table>
          <thead>
            <tr><th>Qty</th><th>Price</th><th>Users</th></tr>
          </thead>
          <tbody>
            @for (lvl of paddedBids(); track $index) {
              <tr [class.empty]="!lvl">
                <td>{{ lvl?.qty ?? '' }}</td>
                <td>{{ lvl?.price ?? '' }}</td>
                <td>{{ lvl?.userCount ?? '' }}</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
      <div class="side">
        <h3>ASKS (Supply)</h3>
        <table>
          <thead>
            <tr><th>Qty</th><th>Price</th><th>Users</th></tr>
          </thead>
          <tbody>
            @for (lvl of paddedAsks(); track $index) {
              <tr [class.empty]="!lvl">
                <td>{{ lvl?.qty ?? '' }}</td>
                <td>{{ lvl?.price ?? '' }}</td>
                <td>{{ lvl?.userCount ?? '' }}</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    </div>
  `,
  styles: [`
    .book-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 1.5rem; }
    table { width: 100%; border-collapse: collapse; }
    th, td { padding: 0.3rem 0.5rem; text-align: right; border-bottom: 1px solid #eee; }
    th:first-child, td:first-child { text-align: left; }
    th { font-weight: 600; }
    tr.empty td { color: #aaa; }
    h3 { margin: 0 0 0.4rem; font-size: 0.9rem; letter-spacing: 0.05em; }
  `],
})
export class OrderBookComponent {
  readonly bids = input<readonly BookLevel[]>([]);
  readonly asks = input<readonly BookLevel[]>([]);

  readonly paddedBids = computed(() => padToFive(this.bids()));
  readonly paddedAsks = computed(() => padToFive(this.asks()));
}

function padToFive(levels: readonly BookLevel[]): readonly (BookLevel | null)[] {
  const top = levels.slice(0, LEVELS_PER_SIDE);
  while (top.length < LEVELS_PER_SIDE) top.push(null as unknown as BookLevel);
  return top.map((l, i) => (i < levels.length ? l : null));
}
