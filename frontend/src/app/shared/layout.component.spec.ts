import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';

import { LayoutComponent } from './layout.component';
import { AuthService } from '../core/auth.service';
import { ToastService } from './toast.service';

class FakeAuth {
  currentUser = signal<{ userId: string; name: string } | null>({
    userId: 'u1',
    name: 'Alice',
  });
  logout = vi.fn();
}

class FakeToast {
  current = signal<{ id: number; kind: string; message: string } | null>(null);
  dismiss = vi.fn(() => this.current.set(null));
  show = vi.fn();
}

describe('LayoutComponent', () => {
  let auth: FakeAuth;
  let toast: FakeToast;

  beforeEach(() => {
    auth = new FakeAuth();
    toast = new FakeToast();
    TestBed.configureTestingModule({
      imports: [LayoutComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: auth },
        { provide: ToastService, useValue: toast },
      ],
    });
  });

  it('renders app brand, current user name, and a Log out button', () => {
    const fixture = TestBed.createComponent(LayoutComponent);
    fixture.detectChanges();

    const html = fixture.nativeElement as HTMLElement;
    expect(html.querySelector('.brand')?.textContent).toContain('Limit Order App');
    expect(html.querySelector('.user-name')?.textContent).toContain('Alice');
    expect(html.querySelector('button')?.textContent).toContain('Log out');
  });

  it('hides the user-controls when no user is signed in (defensive)', () => {
    auth.currentUser.set(null);
    const fixture = TestBed.createComponent(LayoutComponent);
    fixture.detectChanges();

    const html = fixture.nativeElement as HTMLElement;
    expect(html.querySelector('.user-name')).toBeNull();
    expect(html.querySelector('button')).toBeNull();
  });

  it('clicking Log out calls AuthService.logout()', () => {
    const fixture = TestBed.createComponent(LayoutComponent);
    fixture.detectChanges();

    const button = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    button.click();

    expect(auth.logout).toHaveBeenCalledTimes(1);
  });

  it('hosts a <router-outlet>', () => {
    const fixture = TestBed.createComponent(LayoutComponent);
    fixture.detectChanges();

    const html = (fixture.nativeElement as HTMLElement).innerHTML;
    expect(html).toContain('router-outlet');
  });

  it('renders the current toast inside the chrome with the right kind class', () => {
    toast.current.set({ id: 1, kind: 'success', message: 'Welcome, Alice' });
    const fixture = TestBed.createComponent(LayoutComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement.querySelector('.toast') as HTMLElement;
    expect(el.textContent).toContain('Welcome, Alice');
    expect(el.classList.contains('toast-error')).toBe(false);

    toast.current.set({ id: 2, kind: 'error', message: 'oh no' });
    fixture.detectChanges();
    const err = fixture.nativeElement.querySelector('.toast') as HTMLElement;
    expect(err.classList.contains('toast-error')).toBe(true);
  });

  it('ESC dismisses a visible toast', () => {
    toast.current.set({ id: 1, kind: 'success', message: 'hi' });
    const fixture = TestBed.createComponent(LayoutComponent);
    fixture.detectChanges();

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));

    expect(toast.dismiss).toHaveBeenCalledTimes(1);
  });

  it('ESC is a no-op when no toast is visible', () => {
    const fixture = TestBed.createComponent(LayoutComponent);
    fixture.detectChanges();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    expect(toast.dismiss).not.toHaveBeenCalled();
  });
});
