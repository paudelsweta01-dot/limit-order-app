import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';

import { AuthService } from './auth.service';
import type { LoginResponse } from './models';

const STORAGE_KEY = 'lob.session';

/** Minimal HS256-shaped JWT (no real signature — service never verifies). */
function fakeJwt(payload: Record<string, unknown>): string {
  const head = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))
    .replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
  const body = btoa(JSON.stringify(payload))
    .replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
  return `${head}.${body}.signature-not-checked-clientside`;
}

describe('AuthService', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;
  let router: Router;

  beforeEach(() => {
    sessionStorage.clear();
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  afterEach(() => {
    httpMock.verify();
    vi.useRealTimers();
    sessionStorage.clear();
  });

  it('login persists the session and exposes currentUser', () => {
    const exp = Math.floor(Date.now() / 1000) + 60 * 60; // 1h ahead
    const token = fakeJwt({ sub: 'u1', name: 'Alice', exp });
    const body: LoginResponse = { token, userId: 'u1', name: 'Alice' };

    let response: LoginResponse | undefined;
    service.login({ username: 'alice', password: 'pw' }).subscribe((r) => (response = r));

    const req = httpMock.expectOne('/api/auth/login');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ username: 'alice', password: 'pw' });
    req.flush(body);

    expect(response).toEqual(body);
    expect(service.isAuthenticated()).toBe(true);
    expect(service.currentUser()).toEqual({ userId: 'u1', name: 'Alice' });
    expect(service.token()).toBe(token);
    expect(sessionStorage.getItem(STORAGE_KEY)).not.toBeNull();
  });

  it('login with a tampered (non-decodable) JWT does NOT authenticate', () => {
    const body: LoginResponse = {
      token: 'not.a.valid.jwt.shape',
      userId: 'u1',
      name: 'Alice',
    };

    service.login({ username: 'alice', password: 'pw' }).subscribe();
    httpMock.expectOne('/api/auth/login').flush(body);

    expect(service.isAuthenticated()).toBe(false);
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it('login with an exp claim already in the past does NOT authenticate', () => {
    const exp = Math.floor(Date.now() / 1000) - 1;
    const token = fakeJwt({ sub: 'u1', name: 'Alice', exp });
    const body: LoginResponse = { token, userId: 'u1', name: 'Alice' };

    service.login({ username: 'alice', password: 'pw' }).subscribe();
    httpMock.expectOne('/api/auth/login').flush(body);

    expect(service.isAuthenticated()).toBe(false);
  });

  it('auto-logs-out when the JWT exp passes', async () => {
    const exp = Math.floor(Date.now() / 1000) + 60; // 60s ahead
    const token = fakeJwt({ sub: 'u1', name: 'Alice', exp });
    const navSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    service.login({ username: 'alice', password: 'pw' }).subscribe();
    httpMock.expectOne('/api/auth/login').flush({ token, userId: 'u1', name: 'Alice' });

    expect(service.isAuthenticated()).toBe(true);

    // Advance past expiry; the scheduled timer must fire.
    vi.advanceTimersByTime(60_001);

    expect(service.isAuthenticated()).toBe(false);
    expect(navSpy).toHaveBeenCalledWith(['/login']);
  });

  it('logout clears storage and navigates to /login', () => {
    const exp = Math.floor(Date.now() / 1000) + 60 * 60;
    const token = fakeJwt({ sub: 'u1', name: 'Alice', exp });
    const navSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    service.login({ username: 'a', password: 'b' }).subscribe();
    httpMock.expectOne('/api/auth/login').flush({ token, userId: 'u1', name: 'Alice' });

    service.logout();

    expect(service.isAuthenticated()).toBe(false);
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
    expect(navSpy).toHaveBeenCalledWith(['/login']);
  });

  it('a stale session in sessionStorage is dropped on next instantiation', () => {
    sessionStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        token: 'whatever',
        userId: 'u1',
        name: 'Alice',
        expiresAtMs: Date.now() - 1000,
      }),
    );

    // Re-resolve a fresh AuthService from a fresh injector.
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
    const fresh = TestBed.inject(AuthService);

    expect(fresh.isAuthenticated()).toBe(false);
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
  });
});
