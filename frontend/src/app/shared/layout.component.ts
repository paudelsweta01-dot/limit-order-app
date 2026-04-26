import { Component, HostListener, inject } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';

import { AuthService } from '../core/auth.service';
import { ToastService } from './toast.service';

/**
 * Authenticated shell. Header per §6.2 wireframe — app name on the left,
 * `<currentUser> | [Log out]` on the right — plus a router-outlet that
 * each authed route fills in. Phase 7 polishes the styling; here we only
 * need the structure (and the working logout button) so 2.4 is satisfied.
 */
@Component({
  selector: 'app-layout',
  imports: [RouterOutlet, RouterLink],
  template: `
    <header class="app-header">
      <a routerLink="/" class="brand">Limit Order App</a>
      <nav class="user-controls">
        @if (currentUser(); as user) {
          <span class="user-name">{{ user.name }}</span>
          <span aria-hidden="true">|</span>
          <button type="button" (click)="logout()">Log out</button>
        }
      </nav>
    </header>
    <main class="app-content">
      <router-outlet />
    </main>
    @if (toast.current(); as t) {
      <div class="toast" [class.toast-error]="t.kind === 'error'" role="status" aria-live="polite">
        {{ t.message }}
      </div>
    }
  `,
  styles: [`
    .app-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 0.75rem 1.25rem;
      border-bottom: 1px solid #ddd;
    }
    .brand { font-weight: 600; text-decoration: none; color: inherit; }
    .user-controls { display: flex; align-items: center; gap: 0.5rem; }
    .app-content { padding: 1.25rem; }
    .toast {
      position: fixed; right: 1rem; bottom: 1rem;
      padding: 0.6rem 1rem; border-radius: 4px;
      background: #2a7a2a; color: white;
      box-shadow: 0 2px 6px rgba(0,0,0,0.15);
    }
    .toast-error { background: #b00020; }
  `],
})
export class LayoutComponent {
  private readonly auth = inject(AuthService);
  protected readonly toast = inject(ToastService);
  protected readonly currentUser = this.auth.currentUser;

  protected logout(): void {
    this.auth.logout();
  }

  /** Plan §7.5 a11y: ESC dismisses any visible toast. */
  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.toast.current()) this.toast.dismiss();
  }
}
