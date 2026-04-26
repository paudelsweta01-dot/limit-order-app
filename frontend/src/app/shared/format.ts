// Tiny presentation helpers. Phase 7.4 polishes (BigDecimal price 4dp,
// thousands-separator qty, etc.). Phase 6 uses just the two below.

/**
 * `2026-04-21T10:14:02Z` → `2026-04-21 10:14:02`. Returns the input
 * untouched if it can't be parsed (defensive — server can change shape
 * later without breaking the table render).
 */
export function formatTimestamp(iso: string | null | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  );
}

/** First 8 chars of a UUID — keeps tables readable without losing identity. */
export function shortId(id: string): string {
  return id.length > 8 ? id.slice(0, 8) : id;
}
