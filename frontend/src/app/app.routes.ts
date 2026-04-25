import { Routes } from '@angular/router';

import { authGuard } from './core/auth.guard';

/**
 * Architecture §5.2 — four pages, three behind the auth guard. Page
 * components land in Phases 2/4/5/6; the routes are wired here now so
 * the guard's return-url behaviour can be exercised against real
 * navigations from Phase 1's tests onward.
 */
export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./auth/login.page').then((m) => m.LoginPage),
  },
  {
    path: '',
    pathMatch: 'full',
    canActivate: [authGuard],
    loadComponent: () => import('./market/overview.page').then((m) => m.OverviewPage),
  },
  {
    path: 'symbol/:symbol',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./symbol/symbol-detail.page').then((m) => m.SymbolDetailPage),
  },
  {
    path: 'me',
    canActivate: [authGuard],
    loadComponent: () => import('./me/my-account.page').then((m) => m.MyAccountPage),
  },
  { path: '**', redirectTo: '' },
];
