import { Routes } from '@angular/router';

import { authGuard } from './core/auth.guard';

/**
 * Architecture §5.2 — four pages. {@code /login} sits at the top level
 * (no chrome). The other three authed pages share a {@link LayoutComponent}
 * parent so the §6.2 header (app name + Log out button) renders once.
 */
export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./auth/login.page').then((m) => m.LoginPage),
  },
  {
    path: '',
    loadComponent: () =>
      import('./shared/layout.component').then((m) => m.LayoutComponent),
    canActivate: [authGuard],
    children: [
      {
        path: '',
        pathMatch: 'full',
        loadComponent: () =>
          import('./market/overview.page').then((m) => m.OverviewPage),
      },
      {
        path: 'symbol/:symbol',
        loadComponent: () =>
          import('./symbol/symbol-detail.page').then((m) => m.SymbolDetailPage),
      },
      {
        path: 'me',
        loadComponent: () =>
          import('./me/my-account.page').then((m) => m.MyAccountPage),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
