import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { uuidv7 } from 'uuidv7';

import { environment } from '../../environments/environment';
import type {
  BookSnapshot,
  BookTotals,
  CancelOrderResponse,
  LoginRequest,
  LoginResponse,
  MyFill,
  MyOrder,
  OrderSide,
  OrderType,
  SubmitOrderRequest,
  SubmitOrderResponse,
  SymbolRow,
} from './models';

/**
 * Typed REST surface in front of the backend's `/api/*` endpoints.
 * Pages and components inject this — never `HttpClient` directly — so the
 * URL space is centralised.
 */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  // ---------- auth ----------
  login(req: LoginRequest): Observable<LoginResponse> {
    return this.http.post<LoginResponse>(`${this.base}/api/auth/login`, req);
  }

  // ---------- symbols + book ----------
  getSymbols(): Observable<readonly SymbolRow[]> {
    return this.http.get<SymbolRow[]>(`${this.base}/api/symbols`);
  }

  getBook(symbol: string): Observable<BookSnapshot> {
    return this.http.get<BookSnapshot>(`${this.base}/api/book/${encodeURIComponent(symbol)}`);
  }

  getTotals(symbol: string): Observable<BookTotals> {
    return this.http.get<BookTotals>(
      `${this.base}/api/book/${encodeURIComponent(symbol)}/totals`,
    );
  }

  // ---------- orders ----------
  /**
   * Builds a fresh time-ordered clientOrderId (UUIDv7) for each call.
   * For retries on transient network failure, reuse the same id by
   * passing it as `clientOrderId` to keep the backend's idempotency
   * (architecture §4.6) doing its job.
   */
  submitOrder(input: {
    symbol: string;
    side: OrderSide;
    type: OrderType;
    price: string | null;
    quantity: number;
    clientOrderId?: string;
  }): Observable<SubmitOrderResponse> {
    const body: SubmitOrderRequest = {
      clientOrderId: input.clientOrderId ?? uuidv7(),
      symbol: input.symbol,
      side: input.side,
      type: input.type,
      price: input.price,
      quantity: input.quantity,
    };
    return this.http.post<SubmitOrderResponse>(`${this.base}/api/orders`, body);
  }

  cancelOrder(orderId: string): Observable<CancelOrderResponse> {
    return this.http.delete<CancelOrderResponse>(
      `${this.base}/api/orders/${encodeURIComponent(orderId)}`,
    );
  }

  getMyOrders(): Observable<readonly MyOrder[]> {
    return this.http.get<MyOrder[]>(`${this.base}/api/orders/mine`);
  }

  getMyFills(): Observable<readonly MyFill[]> {
    return this.http.get<MyFill[]>(`${this.base}/api/fills/mine`);
  }
}
