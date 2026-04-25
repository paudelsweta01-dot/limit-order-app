import { Component, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';

import { AuthService } from '../core/auth.service';
import { errorMessageOf } from '../core/http.interceptor';

/**
 * §6.1 Login. Reactive form, two fields, single "Log in" button.
 *
 * <ul>
 *   <li>On 401 we show a stable, friendly message ("Wrong username or
 *       password") rather than the raw envelope text — the backend
 *       deliberately uses a unified failure string for both unknown-user
 *       and bad-password to prevent username enumeration.</li>
 *   <li>On success we honour the {@code returnUrl} query param the auth
 *       guard threads through, defaulting to {@code /} (Market Overview).</li>
 *   <li>The submit button stays disabled while the request is in flight to
 *       block double-submits.</li>
 * </ul>
 */
@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule],
  template: `
    <main class="login-card">
      <h1>Limit Order App</h1>
      <form [formGroup]="form" (ngSubmit)="onSubmit()" novalidate>
        <label>
          Username
          <input formControlName="username" autocomplete="username" autofocus />
        </label>
        <label>
          Password
          <input type="password" formControlName="password" autocomplete="current-password" />
        </label>
        @if (errorMessage()) {
          <p role="alert" class="error">{{ errorMessage() }}</p>
        }
        <button type="submit" [disabled]="pending() || form.invalid">
          {{ pending() ? 'Logging in…' : 'Log in' }}
        </button>
      </form>
    </main>
  `,
  styles: [`
    .login-card { max-width: 22rem; margin: 4rem auto; padding: 1.5rem; }
    .login-card label { display: block; margin: 0.75rem 0; }
    .login-card input { display: block; width: 100%; padding: 0.5rem; }
    .login-card button { margin-top: 1rem; padding: 0.5rem 1rem; }
    .error { color: #b00020; margin-top: 0.75rem; }
  `],
})
export class LoginPage {
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly pending = signal(false);
  protected readonly errorMessage = signal<string | null>(null);

  protected readonly form = this.fb.nonNullable.group({
    username: ['', Validators.required],
    password: ['', Validators.required],
  });

  protected onSubmit(): void {
    if (this.form.invalid || this.pending()) return;
    this.pending.set(true);
    this.errorMessage.set(null);

    this.auth.login(this.form.getRawValue()).subscribe({
      next: () => {
        const target = this.resolveReturnUrl();
        void this.router.navigateByUrl(target);
      },
      error: (err: HttpErrorResponse) => {
        this.pending.set(false);
        this.errorMessage.set(
          err.status === 401
            ? 'Wrong username or password'
            : errorMessageOf(err, 'Login failed'),
        );
      },
    });
  }

  /** Avoid `?returnUrl=/login` loops: bounce back to root in that case. */
  private resolveReturnUrl(): string {
    const raw = this.route.snapshot.queryParamMap.get('returnUrl');
    if (!raw) return '/';
    return raw.startsWith('/login') ? '/' : raw;
  }
}
