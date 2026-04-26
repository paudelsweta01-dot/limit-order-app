import { TestBed, ComponentFixture } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { Subject, throwError } from 'rxjs';

import { ApiService } from '../core/api.service';
import { ToastService } from '../shared/toast.service';
import { PlaceOrderForm } from './place-order.form';
import type { SubmitOrderResponse } from '../core/models';

class FakeApi {
  responses: Subject<SubmitOrderResponse> | null = null;
  calls: unknown[] = [];
  submitOrder = vi.fn((req: unknown) => {
    this.calls.push(req);
    if (this.responses) return this.responses.asObservable();
    throw new Error('no response stub configured');
  });
}

class FakeToast {
  shown: { msg: string; kind: string }[] = [];
  show = vi.fn((msg: string, kind: 'success' | 'error' = 'success') => {
    this.shown.push({ msg, kind });
  });
  dismiss = vi.fn();
  current = () => null;
}

function makeFixture(): {
  fixture: ComponentFixture<PlaceOrderForm>;
  api: FakeApi;
  toast: FakeToast;
} {
  const api = new FakeApi();
  const toast = new FakeToast();
  TestBed.configureTestingModule({
    imports: [PlaceOrderForm],
    providers: [
      { provide: ApiService, useValue: api },
      { provide: ToastService, useValue: toast },
    ],
  });
  const fixture = TestBed.createComponent(PlaceOrderForm);
  fixture.componentRef.setInput('symbol', 'AAPL');
  fixture.detectChanges();
  return { fixture, api, toast };
}

function setControl(
  fixture: ComponentFixture<PlaceOrderForm>,
  name: string,
  value: string,
) {
  const inputs = fixture.nativeElement.querySelectorAll(
    `[formControlName="${name}"]`,
  ) as NodeListOf<HTMLInputElement>;
  for (const el of Array.from(inputs)) {
    if (el.type === 'radio') {
      if (el.value === value) {
        el.checked = true;
        el.dispatchEvent(new Event('change'));
      }
    } else {
      el.value = value;
      el.dispatchEvent(new Event('input'));
    }
  }
  fixture.detectChanges();
}

describe('PlaceOrderForm', () => {
  it('renders the §6.3 form fields (Side, Type, Price, Quantity, Submit)', () => {
    const { fixture } = makeFixture();
    const html = fixture.nativeElement as HTMLElement;
    expect(html.querySelectorAll('input[formControlName="side"]')).toHaveLength(2);
    expect(html.querySelectorAll('input[formControlName="type"]')).toHaveLength(2);
    expect(html.querySelector('input[formControlName="price"]')).toBeTruthy();
    expect(html.querySelector('input[formControlName="quantity"]')).toBeTruthy();
    expect(html.querySelector('button[type="submit"]')).toBeTruthy();
  });

  it('disables the price input when MARKET is selected, re-enables on LIMIT', () => {
    const { fixture } = makeFixture();
    setControl(fixture, 'type', 'MARKET');
    const price = fixture.nativeElement.querySelector(
      'input[formControlName="price"]',
    ) as HTMLInputElement;
    expect(price.disabled).toBe(true);
    setControl(fixture, 'type', 'LIMIT');
    expect(price.disabled).toBe(false);
  });

  it('keeps submit disabled until price (LIMIT) and quantity are valid', () => {
    const { fixture } = makeFixture();
    const button = fixture.nativeElement.querySelector(
      'button[type="submit"]',
    ) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    setControl(fixture, 'price', '180.50');
    setControl(fixture, 'quantity', '100');
    expect(button.disabled).toBe(false);
  });

  it('submit posts the form values plus a UUIDv7 clientOrderId', () => {
    const { fixture, api } = makeFixture();
    api.responses = new Subject<SubmitOrderResponse>();
    setControl(fixture, 'price', '180.50');
    setControl(fixture, 'quantity', '100');
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
    expect(api.submitOrder).toHaveBeenCalledTimes(1);
    const call = api.calls[0] as { clientOrderId: string; symbol: string; side: string; type: string; price: string | null; quantity: number };
    expect(call.symbol).toBe('AAPL');
    expect(call.side).toBe('BUY');
    expect(call.type).toBe('LIMIT');
    expect(call.price).toBe('180.50');
    expect(call.quantity).toBe(100);
    expect(call.clientOrderId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
  });

  it('MARKET orders submit with price=null', () => {
    const { fixture, api } = makeFixture();
    api.responses = new Subject<SubmitOrderResponse>();
    setControl(fixture, 'type', 'MARKET');
    setControl(fixture, 'quantity', '50');
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
    expect((api.calls[0] as { price: string | null }).price).toBeNull();
  });

  it('on success: toast fires and a fresh clientOrderId is minted on next submit', () => {
    const { fixture, api, toast } = makeFixture();
    api.responses = new Subject<SubmitOrderResponse>();
    setControl(fixture, 'price', '180.50');
    setControl(fixture, 'quantity', '100');
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
    api.responses.next({ orderId: 'o1', status: 'OPEN', filledQty: 0, idempotentReplay: false });
    fixture.detectChanges();

    expect(toast.shown[0]).toEqual({ msg: 'Order placed', kind: 'success' });

    // Second submission — re-fill (form was reset) and submit again.
    setControl(fixture, 'price', '181.00');
    setControl(fixture, 'quantity', '20');
    api.responses = new Subject<SubmitOrderResponse>();
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));

    const id1 = (api.calls[0] as { clientOrderId: string }).clientOrderId;
    const id2 = (api.calls[1] as { clientOrderId: string }).clientOrderId;
    expect(id2).not.toBe(id1);
  });

  it('on a transient (5xx) error the same clientOrderId is reused on retry', () => {
    const { fixture, api } = makeFixture();
    setControl(fixture, 'price', '180.50');
    setControl(fixture, 'quantity', '100');

    api.submitOrder = vi.fn((req: unknown) => {
      api.calls.push(req);
      return throwError(() => new HttpErrorResponse({ status: 503, statusText: 'Service Unavailable' }));
    });
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
    fixture.detectChanges();

    // Retry — same form values, same id expected.
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
    const id1 = (api.calls[0] as { clientOrderId: string }).clientOrderId;
    const id2 = (api.calls[1] as { clientOrderId: string }).clientOrderId;
    expect(id2).toBe(id1);
  });

  it('on a 4xx (validation) error the clientOrderId is dropped — next click mints fresh', () => {
    const { fixture, api } = makeFixture();
    setControl(fixture, 'price', '180.50');
    setControl(fixture, 'quantity', '100');

    api.submitOrder = vi.fn((req: unknown) => {
      api.calls.push(req);
      return throwError(() => new HttpErrorResponse({
        error: { code: 'VALIDATION_FAILED', message: 'price required for LIMIT' },
        status: 400,
        statusText: 'Bad Request',
      }));
    });

    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
    fixture.detectChanges();
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));

    const id1 = (api.calls[0] as { clientOrderId: string }).clientOrderId;
    const id2 = (api.calls[1] as { clientOrderId: string }).clientOrderId;
    expect(id2).not.toBe(id1);
  });

  it('surfaces backend §4.11 envelope message inline on rejection', () => {
    const { fixture, api } = makeFixture();
    setControl(fixture, 'price', '180.50');
    setControl(fixture, 'quantity', '100');

    api.submitOrder = vi.fn(() => throwError(() => new HttpErrorResponse({
      error: { code: 'VALIDATION_FAILED', message: 'qty must be >= 1' },
      status: 400,
      statusText: 'Bad Request',
    })));

    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
    fixture.detectChanges();
    const errorEl = fixture.nativeElement.querySelector('[role="alert"]') as HTMLElement;
    expect(errorEl.textContent).toContain('qty must be >= 1');
  });

  it('disables the submit button while a request is in flight (double-click defense)', () => {
    const { fixture, api } = makeFixture();
    api.responses = new Subject<SubmitOrderResponse>();
    setControl(fixture, 'price', '180.50');
    setControl(fixture, 'quantity', '100');
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
    fixture.detectChanges();
    const button = fixture.nativeElement.querySelector(
      'button[type="submit"]',
    ) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect(button.textContent).toContain('Submitting');
  });
});
