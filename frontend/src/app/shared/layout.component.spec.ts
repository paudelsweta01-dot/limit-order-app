import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';

import { LayoutComponent } from './layout.component';
import { AuthService } from '../core/auth.service';

class FakeAuth {
  currentUser = signal<{ userId: string; name: string } | null>({
    userId: 'u1',
    name: 'Alice',
  });
  logout = vi.fn();
}

describe('LayoutComponent', () => {
  let auth: FakeAuth;

  beforeEach(() => {
    auth = new FakeAuth();
    TestBed.configureTestingModule({
      imports: [LayoutComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: auth },
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
});
