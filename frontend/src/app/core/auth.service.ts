import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';

import { environment } from '../../environments/environment';
import type { LoginRequest, LoginResponse } from './models';

const STORAGE_KEY = 'lob.session';

interface PersistedSession {
  readonly token: string;
  readonly userId: string;
  readonly name: string;
  readonly expiresAtMs: number;
}

interface JwtPayload {
  readonly sub?: string;
  readonly name?: string;
  readonly exp?: number;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  private readonly session = signal<PersistedSession | null>(this.readPersisted());
  private expiryTimer: ReturnType<typeof setTimeout> | null = null;

  readonly currentUser = computed(() => {
    const s = this.session();
    return s ? { userId: s.userId, name: s.name } : null;
  });

  readonly isAuthenticated = computed(() => this.session() !== null);

  constructor() {
    this.scheduleExpiry();
  }

  login(req: LoginRequest): Observable<LoginResponse> {
    return this.http
      .post<LoginResponse>(`${environment.apiBaseUrl}/api/auth/login`, req)
      .pipe(tap((res) => this.persist(res)));
  }

  logout(): void {
    this.clear();
    void this.router.navigate(['/login']);
  }

  /** Token getter for the HTTP interceptor and WS service. */
  token(): string | null {
    return this.session()?.token ?? null;
  }

  // ---------- internals ----------

  private persist(res: LoginResponse): void {
    const expiresAtMs = decodeExpiry(res.token);
    if (expiresAtMs === null || expiresAtMs <= Date.now()) {
      // Server gave us a token already past its exp claim, or one we
      // couldn't decode — treat as a failed login. Don't authenticate.
      this.clear();
      return;
    }
    const next: PersistedSession = {
      token: res.token,
      userId: res.userId,
      name: res.name,
      expiresAtMs,
    };
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    this.session.set(next);
    this.scheduleExpiry();
  }

  private clear(): void {
    sessionStorage.removeItem(STORAGE_KEY);
    this.session.set(null);
    if (this.expiryTimer !== null) {
      clearTimeout(this.expiryTimer);
      this.expiryTimer = null;
    }
  }

  private readPersisted(): PersistedSession | null {
    const raw = typeof sessionStorage === 'undefined'
      ? null
      : sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    try {
      const parsed = JSON.parse(raw) as PersistedSession;
      // Drop expired sessions on read so a tab opened after expiry
      // never appears authenticated.
      if (typeof parsed.expiresAtMs !== 'number' || parsed.expiresAtMs <= Date.now()) {
        sessionStorage.removeItem(STORAGE_KEY);
        return null;
      }
      return parsed;
    } catch {
      sessionStorage.removeItem(STORAGE_KEY);
      return null;
    }
  }

  private scheduleExpiry(): void {
    if (this.expiryTimer !== null) {
      clearTimeout(this.expiryTimer);
      this.expiryTimer = null;
    }
    const s = this.session();
    if (!s) return;
    const ms = s.expiresAtMs - Date.now();
    if (ms <= 0) {
      this.logout();
      return;
    }
    this.expiryTimer = setTimeout(() => this.logout(), ms);
  }
}

/**
 * Best-effort JWT decode. Returns expiry in **milliseconds** (or null on
 * malformed/tampered/missing-exp). Does NOT verify the signature — that's
 * the server's job; the client only needs the exp + name claims for UX.
 */
function decodeExpiry(token: string): number | null {
  const payload = decodePayload(token);
  if (!payload || typeof payload.exp !== 'number') return null;
  return payload.exp * 1000;
}

function decodePayload(token: string): JwtPayload | null {
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  try {
    const json = atob(parts[1].replace(/-/g, '+').replace(/_/g, '/'));
    return JSON.parse(json) as JwtPayload;
  } catch {
    return null;
  }
}
