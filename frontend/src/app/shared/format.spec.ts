import { formatPrice, formatQty, formatTimestamp, shortId } from './format';

describe('formatTimestamp', () => {
  it('returns "" for null/undefined/empty inputs', () => {
    expect(formatTimestamp(null)).toBe('');
    expect(formatTimestamp(undefined)).toBe('');
    expect(formatTimestamp('')).toBe('');
  });

  it('formats an ISO timestamp into yyyy-MM-dd HH:mm:ss (local time)', () => {
    const iso = '2026-04-21T10:14:02.123Z';
    const out = formatTimestamp(iso);
    // Don't pin the wall-clock value (test runs in any TZ) — just shape.
    expect(out).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
  });

  it('returns the input unchanged when not parseable', () => {
    expect(formatTimestamp('not-a-date')).toBe('not-a-date');
  });
});

describe('shortId', () => {
  it('returns the first 8 chars of a UUID', () => {
    expect(shortId('01900000-0000-7000-8000-000000000001')).toBe('01900000');
  });
  it('returns short ids unchanged', () => {
    expect(shortId('abc')).toBe('abc');
  });
});

describe('formatPrice', () => {
  it('returns "-" for null/undefined/empty', () => {
    expect(formatPrice(null)).toBe('-');
    expect(formatPrice(undefined)).toBe('-');
    expect(formatPrice('')).toBe('-');
  });
  it('preserves the input string verbatim — never reparses through Number', () => {
    expect(formatPrice('180.50')).toBe('180.50');   // trailing zero kept
    expect(formatPrice('0.0001')).toBe('0.0001');   // 4dp kept
    expect(formatPrice('180')).toBe('180');         // no decimal point
  });
});

describe('formatQty', () => {
  it('returns "-" for null/undefined', () => {
    expect(formatQty(null)).toBe('-');
    expect(formatQty(undefined)).toBe('-');
  });
  it('renders zero as "0", small ints unchanged', () => {
    expect(formatQty(0)).toBe('0');
    expect(formatQty(50)).toBe('50');
  });
  it('inserts thousands separators on large quantities', () => {
    expect(formatQty(1000)).toBe('1,000');
    expect(formatQty(100000)).toBe('100,000');
    expect(formatQty(1234567)).toBe('1,234,567');
  });
});
