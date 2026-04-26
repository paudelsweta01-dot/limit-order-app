import { Component, OnInit, inject, input, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { uuidv7 } from 'uuidv7';

import { ApiService } from '../core/api.service';
import { errorMessageOf } from '../core/http.interceptor';
import type { OrderSide, OrderType } from '../core/models';
import { ToastService } from '../shared/toast.service';

/**
 * §6.3 Place Order form. Reactive form with cross-field semantics:
 *
 * <ul>
 *   <li>Price input is disabled when {@code type === MARKET} and required
 *       when LIMIT. The HTML disabled state mirrors the form-control
 *       disabled state, so submitting without a price for LIMIT keeps
 *       the button disabled at the form level.</li>
 *   <li>Quantity must be a positive integer.</li>
 *   <li>Submit mints a fresh {@code clientOrderId = uuidv7()} per
 *       successful round-trip. If a submit fails with a transient
 *       error (5xx / 0-status network) the same id is reused on the
 *       next click — backend idempotency (architecture §4.6) collapses
 *       a duplicate POST onto the original order. 4xx errors clear
 *       the id (the request was rejected on its own merits, retrying
 *       with the same id would just re-trigger the same rejection).</li>
 *   <li>While the request is in flight the submit button is disabled,
 *       defending against double-clicks at the browser level.</li>
 * </ul>
 */
@Component({
  selector: 'app-place-order-form',
  imports: [ReactiveFormsModule],
  template: `
    <section class="place-order">
      <h3>Place Order</h3>
      <form [formGroup]="form" (ngSubmit)="onSubmit()" novalidate>
        <fieldset>
          <legend>Side</legend>
          <label><input type="radio" formControlName="side" value="BUY"  />BUY</label>
          <label><input type="radio" formControlName="side" value="SELL" />SELL</label>
        </fieldset>

        <fieldset>
          <legend>Type</legend>
          <label><input type="radio" formControlName="type" value="LIMIT"  />LIMIT</label>
          <label><input type="radio" formControlName="type" value="MARKET" />MARKET</label>
        </fieldset>

        <label class="row">
          Price
          <!-- type=text (not type=number) to preserve trailing zeros: a
               number input would coerce "180.50" → 180.5 and break the
               BigDecimal-on-the-wire contract (architecture §9.2). -->
          <input
            type="text"
            inputmode="decimal"
            formControlName="price"
            [attr.aria-disabled]="form.controls.price.disabled" />
        </label>

        <label class="row">
          Quantity
          <input type="number" inputmode="numeric" step="1" min="1" formControlName="quantity" />
        </label>

        @if (errorMessage()) {
          <p role="alert" class="error">{{ errorMessage() }}</p>
        }

        <button type="submit" [disabled]="form.invalid || pending()">
          {{ pending() ? 'Submitting…' : 'Submit' }}
        </button>
      </form>
    </section>
  `,
  styles: [`
    .place-order { max-width: 30rem; }
    h3 { margin: 0 0 0.6rem; }
    fieldset { border: 0; padding: 0; margin: 0 0 0.6rem; display: flex; gap: 1rem; }
    fieldset legend { font-weight: 600; padding: 0; }
    .row { display: flex; align-items: center; gap: 0.5rem; margin: 0.4rem 0; }
    .row input { padding: 0.3rem 0.5rem; }
    button { margin-top: 0.6rem; padding: 0.4rem 1rem; }
    .error { color: #b00020; }
  `],
})
export class PlaceOrderForm implements OnInit {
  readonly symbol = input.required<string>();

  private readonly fb = inject(FormBuilder);
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);

  protected readonly pending = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  /** Persisted across submit attempts that fail transiently — see the class doc. */
  private clientOrderId: string | null = null;

  protected readonly form = this.fb.nonNullable.group({
    side:     ['BUY' as OrderSide,  Validators.required],
    type:     ['LIMIT' as OrderType, Validators.required],
    price:    ['', [Validators.required, Validators.pattern(/^\d+(\.\d{1,4})?$/)]],
    quantity: [1, [Validators.required, Validators.min(1)]],
  });

  ngOnInit(): void {
    // Sync price control state with the LIMIT/MARKET selection. Disabled
    // controls are skipped by Validators, so a MARKET order with no
    // price will not be flagged as invalid here.
    this.form.controls.type.valueChanges.subscribe((t) => this.applyTypeChange(t));
    this.applyTypeChange(this.form.controls.type.value);
  }

  private applyTypeChange(type: OrderType): void {
    const price = this.form.controls.price;
    if (type === 'MARKET') {
      price.disable({ emitEvent: false });
      price.setValue('', { emitEvent: false });
    } else {
      price.enable({ emitEvent: false });
    }
  }

  protected onSubmit(): void {
    if (this.form.invalid || this.pending()) return;

    if (this.clientOrderId === null) this.clientOrderId = uuidv7();
    const id = this.clientOrderId;

    const v = this.form.getRawValue();
    this.pending.set(true);
    this.errorMessage.set(null);

    this.api.submitOrder({
      clientOrderId: id,
      symbol: this.symbol(),
      side: v.side,
      type: v.type,
      price: v.type === 'LIMIT' ? v.price : null,
      quantity: Number(v.quantity),
    }).subscribe({
      next: () => {
        this.pending.set(false);
        this.clientOrderId = null;
        this.toast.show('Order placed');
        // Reset only the inputs the user populates per submission; the
        // side/type radios stay sticky for follow-up orders.
        this.form.controls.price.setValue('');
        this.form.controls.quantity.setValue(1);
      },
      error: (err: HttpErrorResponse) => {
        this.pending.set(false);
        if (err.status >= 400 && err.status < 500) {
          // Validation / business-rule rejection — a retry with the same
          // id would just hit the same wall; mint a fresh id next click.
          this.clientOrderId = null;
        }
        this.errorMessage.set(errorMessageOf(err, 'Order rejected'));
        this.toast.show(errorMessageOf(err, 'Order rejected'), 'error');
      },
    });
  }
}
