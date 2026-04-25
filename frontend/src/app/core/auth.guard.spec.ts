import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  Router,
  RouterStateSnapshot,
  UrlTree,
  provideRouter,
} from '@angular/router';
import { runInInjectionContext } from '@angular/core';

import { AuthService } from './auth.service';
import { authGuard } from './auth.guard';

class FakeAuth {
  authed = false;
  isAuthenticated() { return this.authed; }
}

function runGuard(url: string): boolean | UrlTree {
  return runInInjectionContext(TestBed.inject(EnvInjectorMarker), () =>
    authGuard(
      {} as ActivatedRouteSnapshot,
      { url } as RouterStateSnapshot,
    ),
  ) as boolean | UrlTree;
}

// Marker token so we can grab the root EnvironmentInjector.
import { EnvironmentInjector, InjectionToken } from '@angular/core';
const EnvInjectorMarker = new InjectionToken<EnvironmentInjector>('env-marker');

describe('authGuard', () => {
  let auth: FakeAuth;

  beforeEach(() => {
    auth = new FakeAuth();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: auth },
        { provide: EnvInjectorMarker, useFactory: () => TestBed.inject(EnvironmentInjector) },
      ],
    });
  });

  it('returns true when authenticated', () => {
    auth.authed = true;
    const result = runGuard('/symbol/AAPL');
    expect(result).toBe(true);
  });

  it('returns a UrlTree to /login with returnUrl preserved when not authenticated', () => {
    auth.authed = false;
    const result = runGuard('/symbol/AAPL') as UrlTree;
    const router = TestBed.inject(Router);
    expect(router.serializeUrl(result)).toBe('/login?returnUrl=%2Fsymbol%2FAAPL');
  });

  it('preserves nested URLs verbatim', () => {
    auth.authed = false;
    const result = runGuard('/me?tab=fills') as UrlTree;
    const router = TestBed.inject(Router);
    expect(router.serializeUrl(result)).toBe('/login?returnUrl=%2Fme%3Ftab%3Dfills');
  });
});
