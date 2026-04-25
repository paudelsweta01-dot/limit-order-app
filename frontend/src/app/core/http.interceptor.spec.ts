import { TestBed } from '@angular/core/testing';
import {
  HttpClient,
  HttpErrorResponse,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';

import { AuthService } from './auth.service';
import { authInterceptor, errorMessageOf } from './http.interceptor';

class FakeAuth {
  private current: string | null = 'fake-token';
  token() { return this.current; }
  setToken(t: string | null) { this.current = t; }
  logout = vi.fn();
}

describe('authInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let auth: FakeAuth;

  beforeEach(() => {
    auth = new FakeAuth();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AuthService, useValue: auth },
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('attaches Bearer token to outgoing authenticated requests', () => {
    http.get('/api/symbols').subscribe();
    const req = httpMock.expectOne('/api/symbols');
    expect(req.request.headers.get('Authorization')).toBe('Bearer fake-token');
    req.flush([]);
  });

  it('does NOT attach a token to /api/auth/login (no token loop)', () => {
    http.post('/api/auth/login', { username: 'a', password: 'b' }).subscribe();
    const req = httpMock.expectOne('/api/auth/login');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({ token: 't', userId: 'u', name: 'n' });
  });

  it('does NOT attach a header when there is no token', () => {
    auth.setToken(null);
    http.get('/api/symbols').subscribe();
    const req = httpMock.expectOne('/api/symbols');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush([]);
  });

  it('on 401 from a non-login call, calls AuthService.logout() and re-throws', () => {
    let caught: unknown;
    http.get('/api/symbols').subscribe({
      error: (e) => (caught = e),
    });

    httpMock.expectOne('/api/symbols').flush(
      { code: 'UNAUTHORIZED', message: 'invalid token' },
      { status: 401, statusText: 'Unauthorized' },
    );

    expect(auth.logout).toHaveBeenCalledTimes(1);
    expect(caught).toBeInstanceOf(HttpErrorResponse);
  });

  it('on 401 from /api/auth/login (bad credentials), does NOT call logout', () => {
    http.post('/api/auth/login', {}).subscribe({ error: () => {} });
    httpMock.expectOne('/api/auth/login').flush(
      { code: 'UNAUTHORIZED', message: 'invalid username or password' },
      { status: 401, statusText: 'Unauthorized' },
    );
    expect(auth.logout).not.toHaveBeenCalled();
  });
});

describe('errorMessageOf', () => {
  it('returns the architecture §4.11 envelope message', () => {
    const err = new HttpErrorResponse({
      error: { code: 'VALIDATION_FAILED', message: 'price required for LIMIT' },
      status: 400,
      statusText: 'Bad Request',
    });
    expect(errorMessageOf(err)).toBe('price required for LIMIT');
  });

  it('falls back to statusText when there is no envelope', () => {
    const err = new HttpErrorResponse({
      error: null,
      status: 500,
      statusText: 'Internal Server Error',
    });
    expect(errorMessageOf(err)).toBe('Internal Server Error');
  });
});
