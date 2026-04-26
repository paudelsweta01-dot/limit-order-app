import { TestBed } from '@angular/core/testing';

import { OrderBookComponent } from './order-book.component';
import type { BookLevel } from '../core/models';

describe('OrderBookComponent', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [OrderBookComponent] });
  });

  it('renders five rows per side, padding empties when level count < 5', () => {
    const fixture = TestBed.createComponent(OrderBookComponent);
    const bids: BookLevel[] = [{ price: '180.00', qty: 50, userCount: 1 }];
    fixture.componentRef.setInput('bids', bids);
    fixture.componentRef.setInput('asks', []);
    fixture.detectChanges();

    const sides = fixture.nativeElement.querySelectorAll('.side tbody');
    expect(sides[0].querySelectorAll('tr')).toHaveLength(5);
    expect(sides[1].querySelectorAll('tr')).toHaveLength(5);
    expect(sides[1].querySelectorAll('tr.empty')).toHaveLength(5);
  });

  it('renders the §6.3 wireframe values when populated', () => {
    const fixture = TestBed.createComponent(OrderBookComponent);
    fixture.componentRef.setInput('bids', [
      { price: '180.00', qty: 50, userCount: 1 },
    ] as BookLevel[]);
    fixture.componentRef.setInput('asks', [
      { price: '180.50', qty: 80,  userCount: 1 },
      { price: '181.00', qty: 100, userCount: 1 },
      { price: '182.00', qty: 150, userCount: 1 },
    ] as BookLevel[]);
    fixture.detectChanges();

    const sides = fixture.nativeElement.querySelectorAll('.side tbody');
    const bidCells = (sides[0].querySelectorAll('tr')[0] as HTMLTableRowElement)
      .querySelectorAll('td');
    expect(Array.from(bidCells).map((c) => c.textContent?.trim())).toEqual(
      ['50', '180.00', '1'],
    );

    const askRows = sides[1].querySelectorAll('tr');
    expect((askRows[0] as HTMLElement).querySelectorAll('td')[0].textContent?.trim()).toBe('80');
    expect((askRows[2] as HTMLElement).querySelectorAll('td')[1].textContent?.trim()).toBe('182.00');
    // 4th and 5th rows are empty.
    expect(Array.from(askRows as NodeListOf<HTMLElement>).filter((r) => r.classList.contains('empty'))).toHaveLength(2);
  });

  it('caps to top 5 levels even if more are passed in', () => {
    const fixture = TestBed.createComponent(OrderBookComponent);
    const many: BookLevel[] = Array.from({ length: 8 }, (_, i) => ({
      price: `180.0${i}`,
      qty: 10 * (i + 1),
      userCount: 1,
    }));
    fixture.componentRef.setInput('bids', many);
    fixture.componentRef.setInput('asks', []);
    fixture.detectChanges();

    const tbody = fixture.nativeElement.querySelectorAll('.side tbody')[0];
    expect(tbody.querySelectorAll('tr')).toHaveLength(5);
    expect(tbody.querySelectorAll('tr.empty')).toHaveLength(0);
  });
});
