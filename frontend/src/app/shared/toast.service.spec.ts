import { TestBed } from '@angular/core/testing';

import { ToastService } from './toast.service';

describe('ToastService', () => {
  let svc: ToastService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    svc = TestBed.inject(ToastService);
  });

  it('show() sets the current toast with default kind=success', () => {
    svc.show('Order placed');
    expect(svc.current()?.message).toBe('Order placed');
    expect(svc.current()?.kind).toBe('success');
  });

  it('show() with kind=error sets error toast', () => {
    svc.show('Boom', 'error');
    expect(svc.current()?.kind).toBe('error');
  });

  it('toast auto-clears after the configured duration', () => {
    vi.useFakeTimers();
    try {
      svc.show('hi', 'success', 1000);
      expect(svc.current()).not.toBeNull();
      vi.advanceTimersByTime(1001);
      expect(svc.current()).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('a newer show() supersedes the older one and resets the timer', () => {
    vi.useFakeTimers();
    try {
      svc.show('first', 'success', 500);
      vi.advanceTimersByTime(300);
      svc.show('second', 'success', 500);
      // 250ms after the second show — first toast's timer would have fired
      // by now (550ms total), but the second's hasn't (250ms < 500ms).
      vi.advanceTimersByTime(250);
      expect(svc.current()?.message).toBe('second');
    } finally {
      vi.useRealTimers();
    }
  });

  it('dismiss() clears the toast immediately', () => {
    svc.show('hi');
    svc.dismiss();
    expect(svc.current()).toBeNull();
  });
});
