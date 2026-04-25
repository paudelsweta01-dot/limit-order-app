import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';

import { AuthService } from './auth.service';
import type { ErrorResponseBody } from './models';

/**
 * Functional HTTP interceptor (Angular 21 style).
 *
 * <ul>
 *   <li>Attaches {@code Authorization: Bearer <token>} when authenticated,
 *       except on the login call itself.</li>
 *   <li>On 401, force-logs-out (which navigates to /login) and re-throws.</li>
 *   <li>On any error, surfaces the architecture §4.11 error envelope as
 *       {@code error.error} so callers can present field-level messages.</li>
 * </ul>
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  const isLogin = req.url.endsWith('/api/auth/login');
  const token = auth.token();

  const decorated = !isLogin && token
    ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : req;

  return next(decorated).pipe(
    catchError((err: HttpErrorResponse) => {
      if (err.status === 401 && !isLogin) {
        auth.logout(); // navigates to /login
      }
      return throwError(() => err);
    }),
  );
};

/**
 * Helper for callers: pull a user-presentable message out of an HTTP error.
 * Falls back through: error envelope → status text → generic.
 */
export function errorMessageOf(err: HttpErrorResponse, fallback = 'Something went wrong'): string {
  const body = err.error as ErrorResponseBody | string | null | undefined;
  if (body && typeof body === 'object' && typeof body.message === 'string') {
    return body.message;
  }
  if (typeof body === 'string' && body.trim().length > 0) {
    return body;
  }
  return err.statusText || fallback;
}
