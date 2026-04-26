import { Injectable, inject, signal } from '@angular/core';
import { DOCUMENT } from '@angular/common';

export type ToastKind = 'success' | 'error';

export interface Toast {
  readonly id: number;
  readonly kind: ToastKind;
  readonly message: string;
}

const DEFAULT_DURATION_MS = 3000;

/**
 * Tiny in-memory toast service. Components inject and call
 * {@link show}; one toast at a time renders in {@link LayoutComponent}.
 * Phase 7.1 polishes the surface (queueing, longer error toasts, ESC
 * to dismiss); the contract here is what every page can rely on.
 */
@Injectable({ providedIn: 'root' })
export class ToastService {
  private readonly doc = inject(DOCUMENT);
  private nextId = 1;
  private hideTimer: ReturnType<typeof setTimeout> | null = null;

  readonly current = signal<Toast | null>(null);

  show(message: string, kind: ToastKind = 'success', durationMs = DEFAULT_DURATION_MS): void {
    const id = this.nextId++;
    this.current.set({ id, kind, message });
    if (this.hideTimer !== null) clearTimeout(this.hideTimer);
    const win = this.doc.defaultView;
    const setter = win?.setTimeout ?? setTimeout;
    this.hideTimer = setter(() => {
      // Only clear if this is still the toast we scheduled — a newer
      // call to show() will have replaced it and re-armed the timer.
      if (this.current()?.id === id) this.current.set(null);
    }, durationMs) as ReturnType<typeof setTimeout>;
  }

  dismiss(): void {
    if (this.hideTimer !== null) clearTimeout(this.hideTimer);
    this.hideTimer = null;
    this.current.set(null);
  }
}
