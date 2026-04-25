import { TestBed, ComponentFixture } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { Subject, throwError } from 'rxjs';

import { LoginPage } from './login.page';
import { AuthService } from '../core/auth.service';
import type { LoginResponse } from '../core/models';
import { HttpErrorResponse } from '@angular/common/http';

class FakeAuth {
  loginSubject = new Subject<LoginResponse>();
  loginCalls: { username: string; password: string }[] = [];
  login = vi.fn((req: { username: string; password: string }) => {
    this.loginCalls.push(req);
    return this.loginSubject.asObservable();
  });
}

function makeFixture(returnUrl?: string): {
  fixture: ComponentFixture<LoginPage>;
  auth: FakeAuth;
  navigateByUrl: ReturnType<typeof vi.fn>;
} {
  const auth = new FakeAuth();
  const navigateByUrl = vi.fn().mockResolvedValue(true);
  const queryParams = returnUrl ? { returnUrl } : {};

  TestBed.configureTestingModule({
    imports: [LoginPage],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: AuthService, useValue: auth },
      {
        provide: ActivatedRoute,
        useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } },
      },
    ],
  });
  const router = TestBed.inject(Router);
  router.navigateByUrl = navigateByUrl as never;

  const fixture = TestBed.createComponent(LoginPage);
  fixture.detectChanges();
  return { fixture, auth, navigateByUrl };
}

function setField(fixture: ComponentFixture<LoginPage>, name: string, value: string) {
  const input = fixture.nativeElement.querySelector(
    `input[formControlName="${name}"]`,
  ) as HTMLInputElement;
  input.value = value;
  input.dispatchEvent(new Event('input'));
  fixture.detectChanges();
}

describe('LoginPage', () => {
  it('renders the §6.1 wireframe (title, two fields, single submit button)', () => {
    const { fixture } = makeFixture();
    const html = fixture.nativeElement as HTMLElement;
    expect(html.querySelector('h1')?.textContent).toContain('Limit Order App');
    expect(html.querySelector('input[formControlName="username"]')).toBeTruthy();
    expect(html.querySelector('input[formControlName="password"]')).toBeTruthy();
    expect(html.querySelectorAll('button[type="submit"]').length).toBe(1);
  });

  it('submit is disabled while the form is invalid (empty)', () => {
    const { fixture } = makeFixture();
    const button = fixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement;
    expect(button.disabled).toBe(true);
  });

  it('submit calls AuthService.login with the form values', () => {
    const { fixture, auth } = makeFixture();
    setField(fixture, 'username', 'alice');
    setField(fixture, 'password', 'pw');
    const form = fixture.nativeElement.querySelector('form') as HTMLFormElement;
    form.dispatchEvent(new Event('submit'));
    fixture.detectChanges();

    expect(auth.login).toHaveBeenCalledWith({ username: 'alice', password: 'pw' });
  });

  it('on success without returnUrl navigates to /', async () => {
    const { fixture, auth, navigateByUrl } = makeFixture();
    setField(fixture, 'username', 'alice');
    setField(fixture, 'password', 'pw');
    fixture.nativeElement.querySelector('form')!.dispatchEvent(new Event('submit'));

    auth.loginSubject.next({ token: 't', userId: 'u', name: 'Alice' });
    await fixture.whenStable();

    expect(navigateByUrl).toHaveBeenCalledWith('/');
  });

  it('on success with returnUrl navigates back to it', async () => {
    const { fixture, auth, navigateByUrl } = makeFixture('/symbol/AAPL');
    setField(fixture, 'username', 'a');
    setField(fixture, 'password', 'b');
    fixture.nativeElement.querySelector('form')!.dispatchEvent(new Event('submit'));

    auth.loginSubject.next({ token: 't', userId: 'u', name: 'Alice' });
    await fixture.whenStable();

    expect(navigateByUrl).toHaveBeenCalledWith('/symbol/AAPL');
  });

  it('refuses to bounce back to /login (avoids redirect loops)', async () => {
    const { fixture, auth, navigateByUrl } = makeFixture('/login?returnUrl=%2F');
    setField(fixture, 'username', 'a');
    setField(fixture, 'password', 'b');
    fixture.nativeElement.querySelector('form')!.dispatchEvent(new Event('submit'));

    auth.loginSubject.next({ token: 't', userId: 'u', name: 'Alice' });
    await fixture.whenStable();

    expect(navigateByUrl).toHaveBeenCalledWith('/');
  });

  it('on 401 shows the friendly "Wrong username or password" message', () => {
    const { fixture, auth } = makeFixture();
    setField(fixture, 'username', 'a');
    setField(fixture, 'password', 'b');
    auth.login = vi.fn(() => throwError(() => new HttpErrorResponse({
      error: { code: 'UNAUTHORIZED', message: 'invalid username or password' },
      status: 401,
      statusText: 'Unauthorized',
    })));
    fixture.nativeElement.querySelector('form')!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();

    const errorEl = fixture.nativeElement.querySelector('[role="alert"]') as HTMLElement;
    expect(errorEl?.textContent).toContain('Wrong username or password');
  });

  it('on non-401 errors surfaces the §4.11 envelope message', () => {
    const { fixture, auth } = makeFixture();
    setField(fixture, 'username', 'a');
    setField(fixture, 'password', 'b');
    auth.login = vi.fn(() => throwError(() => new HttpErrorResponse({
      error: { code: 'INTERNAL', message: 'engine down' },
      status: 500,
      statusText: 'Internal Server Error',
    })));
    fixture.nativeElement.querySelector('form')!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();

    const errorEl = fixture.nativeElement.querySelector('[role="alert"]') as HTMLElement;
    expect(errorEl?.textContent).toContain('engine down');
  });

  it('disables the submit button while the request is pending', () => {
    const { fixture } = makeFixture();
    setField(fixture, 'username', 'a');
    setField(fixture, 'password', 'b');
    fixture.nativeElement.querySelector('form')!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();

    const button = fixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect(button.textContent).toContain('Logging in');
  });
});
