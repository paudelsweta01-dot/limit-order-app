// Tiny presentation helpers. Pages display values through these so the
// formatting story is consistent (architecture §9.2: BigDecimals stay
// strings on the wire and only get cosmetic treatment at the leaf).

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

/**
 * BigDecimal-as-string display. Returns `'-'` for null/undefined/empty
 * so MARKET-order rows and empty book sides get the wireframe's dash.
 * The value itself is preserved verbatim — we never reparse through
 * Number, which would silently lose trailing-zero precision (architecture
 * §9.2). Plan §7.4 originally said "4dp" but the §6.x wireframes
 * consistently show 2dp values, and the seed data is already
 * 2dp-precise; trusting the backend's exact string is the simplest
 * correct choice and round-trips losslessly.
 */
export function formatPrice(price: string | null | undefined): string {
  if (price === null || price === undefined || price === '') return '-';
  return price;
}

/** Quantity with thousands separator. `100000` → `'100,000'`. */
export function formatQty(qty: number | null | undefined): string {
  if (qty === null || qty === undefined) return '-';
  return qty.toLocaleString('en-US');
}
