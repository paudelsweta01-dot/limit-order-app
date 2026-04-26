import { formatTimestamp, shortId } from './format';

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
