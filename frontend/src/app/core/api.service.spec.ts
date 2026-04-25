import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';

import { ApiService } from './api.service';

describe('ApiService', () => {
  let api: ApiService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(ApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('login → POST /api/auth/login', () => {
    api.login({ username: 'a', password: 'b' }).subscribe();
    const req = httpMock.expectOne('/api/auth/login');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ username: 'a', password: 'b' });
    req.flush({ token: 't', userId: 'u', name: 'n' });
  });

  it('getSymbols → GET /api/symbols', () => {
    api.getSymbols().subscribe();
    const req = httpMock.expectOne('/api/symbols');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('getBook(AAPL) → GET /api/book/AAPL', () => {
    api.getBook('AAPL').subscribe();
    const req = httpMock.expectOne('/api/book/AAPL');
    expect(req.request.method).toBe('GET');
    req.flush({ symbol: 'AAPL', bids: [], asks: [], last: null, cursor: 0 });
  });

  it('getTotals(AAPL) → GET /api/book/AAPL/totals', () => {
    api.getTotals('AAPL').subscribe();
    const req = httpMock.expectOne('/api/book/AAPL/totals');
    expect(req.request.method).toBe('GET');
    req.flush({ demand: 0, supply: 0 });
  });

  it('submitOrder auto-generates a clientOrderId (uuidv7) when none provided', () => {
    api.submitOrder({
      symbol: 'AAPL',
      side: 'BUY',
      type: 'LIMIT',
      price: '175.00',
      quantity: 100,
    }).subscribe();

    const req = httpMock.expectOne('/api/orders');
    expect(req.request.method).toBe('POST');
    const body = req.request.body as { clientOrderId: string };
    // UUIDv7 surface: 36 chars, version nibble 7.
    expect(body.clientOrderId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
    req.flush({
      orderId: 'o1', status: 'OPEN', filledQty: 0, idempotentReplay: false,
    });
  });

  it('submitOrder reuses the supplied clientOrderId (idempotent retry path)', () => {
    const id = '01900000-0000-7000-8000-000000000001';
    api.submitOrder({
      symbol: 'AAPL',
      side: 'BUY',
      type: 'LIMIT',
      price: '175.00',
      quantity: 100,
      clientOrderId: id,
    }).subscribe();

    const req = httpMock.expectOne('/api/orders');
    expect((req.request.body as { clientOrderId: string }).clientOrderId).toBe(id);
    req.flush({ orderId: 'o1', status: 'OPEN', filledQty: 0, idempotentReplay: false });
  });

  it('cancelOrder → DELETE /api/orders/{id}', () => {
    api.cancelOrder('o1').subscribe();
    const req = httpMock.expectOne('/api/orders/o1');
    expect(req.request.method).toBe('DELETE');
    req.flush({ orderId: 'o1', status: 'CANCELLED', filledQty: 0 });
  });

  it('getMyOrders → GET /api/orders/mine', () => {
    api.getMyOrders().subscribe();
    const req = httpMock.expectOne('/api/orders/mine');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('getMyFills → GET /api/fills/mine', () => {
    api.getMyFills().subscribe();
    const req = httpMock.expectOne('/api/fills/mine');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });
});
