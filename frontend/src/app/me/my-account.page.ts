import { Component } from '@angular/core';

import { MyOrdersPage } from './my-orders.page';
import { MyFillsPage } from './my-fills.page';

/**
 * §6.4 — single page hosting both halves of the user's view of their
 * own activity. Each half is an independent component that holds its
 * own state and WS subscription; refcounted via {@link WsService}, so
 * both halves share the underlying {@code /ws/orders/mine} socket.
 */
@Component({
  selector: 'app-my-account',
  imports: [MyOrdersPage, MyFillsPage],
  template: `
    <section class="my-account">
      <h2>My Account</h2>
      <app-my-orders />
      <app-my-fills />
    </section>
  `,
  styles: [`
    .my-account { max-width: 70rem; }
    h2 { margin: 0 0 1rem; }
  `],
})
export class MyAccountPage {}
