import { TestBed } from '@angular/core/testing';
import { Subject, of } from 'rxjs';

import { ApiService } from '../core/api.service';
import { WsService } from '../core/ws.service';
import { MyAccountPage } from './my-account.page';
import type {
  MyFill,
  MyOrder,
  OrdersStreamEvent,
} from '../core/models';

class FakeApi {
  getMyOrders = vi.fn(() => of([] as readonly MyOrder[]));
  getMyFills = vi.fn(() => of([] as readonly MyFill[]));
  cancelOrder = vi.fn();
}

class FakeWs {
  subj = new Subject<OrdersStreamEvent>();
  subscribeOrders = vi.fn(() => this.subj.asObservable());
}

describe('MyAccountPage', () => {
  let api: FakeApi;
  let ws: FakeWs;

  beforeEach(() => {
    api = new FakeApi();
    ws = new FakeWs();
    TestBed.configureTestingModule({
      imports: [MyAccountPage],
      providers: [
        { provide: ApiService, useValue: api },
        { provide: WsService, useValue: ws },
      ],
    });
  });

  it('renders both halves: app-my-orders + app-my-fills', () => {
    const fixture = TestBed.createComponent(MyAccountPage);
    fixture.detectChanges();
    const html = fixture.nativeElement as HTMLElement;
    expect(html.querySelector('app-my-orders')).toBeTruthy();
    expect(html.querySelector('app-my-fills')).toBeTruthy();
  });

  it('both halves trigger their own initial REST loads on first paint', () => {
    const fixture = TestBed.createComponent(MyAccountPage);
    fixture.detectChanges();
    expect(api.getMyOrders).toHaveBeenCalledTimes(1);
    expect(api.getMyFills).toHaveBeenCalledTimes(1);
  });

  it('both halves subscribe to subscribeOrders() — refcount keeps the socket open across both', () => {
    const fixture = TestBed.createComponent(MyAccountPage);
    fixture.detectChanges();
    expect(ws.subscribeOrders).toHaveBeenCalledTimes(2);
  });
});
