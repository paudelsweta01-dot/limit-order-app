import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([])],
    }).compileComponents();
  });

  it('mounts and renders a <router-outlet>', async () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.componentInstance).toBeTruthy();
    // Angular renders <router-outlet> as a comment anchor in the DOM,
    // so the host's innerHTML carries the marker even when the outlet
    // hasn't activated a child yet.
    const hostHtml = (fixture.nativeElement as HTMLElement).innerHTML;
    expect(hostHtml).toContain('router-outlet');
  });
});
