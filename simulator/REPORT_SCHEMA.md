# Simulator JSON report schema

Plan §7.2. Written when any mode is run with `--report=PATH`. The
`JsonReporter` produces the same data the `ConsoleReporter` shows on
stdout, in a stable key order so reviewers can diff reports across
runs without spurious noise.

```json5
{
  "runId":              "uuid v4",
  "mode":               "scenario | load | multi-instance | consistency-check",
  "startedAt":          "ISO-8601 instant",
  "finishedAt":         "ISO-8601 instant | null",
  "durationMs":         12345,

  "totals": {
    "submitted":         100,
    "accepted":           97,
    "rejected":            3,
    "idempotentReplays":   0,
    "tradesObserved":     12
  },

  "allAssertionsPassed": true,

  "assertions": [
    {
      "name":   "book:AAPL",
      "passed": true,
      "diffs":  []
    },
    {
      "name":   "buy-sell-net-zero",
      "passed": false,
      "diffs":  ["AAPL: Σ filledQty(BUY)=100 ≠ Σ filledQty(SELL)=50 (diff=50)"]
    }
  ],

  // Per-order detail. Empty array for modes that don't track this
  // (consistency-check, multi-instance — they're observation modes,
  // not submitting modes).
  "orders": [
    {
      "clientOrderId":  "01900000-0000-7000-8000-000000000001",
      "userId":         "u1",
      "symbol":         "AAPL",
      "side":           "BUY",
      "type":           "LIMIT",
      "price":          "180.50",     // BigDecimal-as-string; null for MARKET
      "quantity":       100,
      "orderId":        "uuid",       // null on submission failure
      "status":         "OPEN",       // OPEN/PARTIAL/FILLED/CANCELLED/REJECTED, or "ERROR"
      "filledQty":      0,
      "error":          null          // §4.11 envelope message on failure
    }
  ]
}
```

## Field semantics

| Field | Notes |
|---|---|
| `runId` | UUID v4 minted at start; correlates the report with any log lines emitted during the run. |
| `mode` | One of the four `--mode=…` values. |
| `durationMs` | `Duration.between(startedAt, finishedAt).toMillis()`. |
| `totals.submitted` | Includes attempts that errored out at the API layer. |
| `totals.accepted` | Server returned `OPEN`, `PARTIAL`, or `FILLED`. |
| `totals.rejected` | Server returned `CANCELLED`/`REJECTED` *or* the request 4xx-failed. |
| `totals.idempotentReplays` | Server returned `idempotentReplay: true` (architecture §4.6). |
| `assertions[].name` | Stable identifier per assertion type — see the assertion catalogue below. |
| `assertions[].diffs` | Plain-text diff lines, ready to print verbatim. Empty on PASS. |

## Assertion catalogue

| `name` | Source | Meaning |
|---|---|---|
| `book:<SYMBOL>` | scenario mode | Per-symbol top-of-book matches the expected JSON. |
| `buy-sell-net-zero` | consistency-check mode | Σ filled BUY == Σ filled SELL per symbol (§4.3 invariant 1). |
| `filled-le-quantity` | consistency-check mode | No order has `filled_qty > quantity` (§4.3 invariant 2). |
| `trade-counterparts` | consistency-check mode | Each fill has a matching opposite-side fill on the named counterparty (§4.3 invariant 3). |
| `walk:<USERNAME>` | consistency-check mode | The per-user `getMyOrders` + `getMyFills` walk succeeded for this user. |
| `load:duration-elapsed` | load mode | The load loop ran to its full `--duration`. |
| `load:bootstrap` | load mode | `/api/symbols` fetch on startup succeeded. |
| `books-equal-across-nodes` | multi-instance mode | After load + 1 s convergence window, both nodes' books match per-symbol (architecture §3 NFR). |
| `multi-instance:bootstrap` | multi-instance mode | Both `--nodeA` and `--nodeB` were reachable on startup. |

## Example

A populated example lives at
[`simulator/src/test/resources/example-report.json`](src/test/resources/example-report.json).
