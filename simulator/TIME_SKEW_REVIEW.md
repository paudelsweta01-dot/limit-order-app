# Time-skew tolerance — simulator code review (plan §9.3)

> Acceptance per the simulator plan: "don't rely on JVM clock for
> ordering — use server-issued `created_at` and `executed_at` timestamps."
> This file is the code-review pass plus the rule we hold the line on
> in future contributions.

## Rule

The simulator is allowed to use its own JVM clock for **timing only**
(measuring how long a thing took, deciding when a run ends, formatting
report timestamps). It is **never** allowed to use its own clock for
**ordering** (deciding which fill came first, comparing two events for
sequence, identifying a trade by its timestamp).

For ordering decisions the simulator must use the server-issued
fields:
- `MyOrderResponse.createdAt` / `MyOrderResponse.updatedAt`
- `MyFillResponse.executedAt`

## Why it matters

Two backend instances can run on hosts whose clocks drift apart by
hundreds of milliseconds. The architecture (§9.1) pins time priority
on Postgres' `now()` so all events are stamped against a single
shared clock — the DB's. If the simulator (running on yet a third
host) used `Instant.now()` to assign sequence to fills it observed,
its conclusion would change with NTP drift.

This becomes load-bearing for:
- **Multi-instance mode** — comparing books snapshot-from-both-nodes.
- **Consistency-check mode** — invariants like "filled SELL totals
  match filled BUY totals" must use server-stamped quantities, not
  reconstructed-from-the-wire-time-of-arrival sequences.

## Code-review findings (2026-04-26)

Surveyed every JVM-clock callsite in `simulator/src/main`:

| Site | Use | Verdict |
|---|---|---|
| `LoadRunner.java:84,97,139,150,171,201` | `Instant.now()` for `RunReport.{startedAt,finishedAt}` and the `runEnd` deadline + the worker / wait poll loops. | ✅ timing-only — never compared to server timestamps. |
| `LoadRunner.java:203,207,215` | `System.nanoTime()` for per-request latency. | ✅ relative within the same JVM; never cross-machine. |
| `MultiInstanceRunner.java:86,102,120,132,154,183,185,189,197` | Same pattern — `Instant.now()` for run windowing, `System.nanoTime()` for latency. | ✅ timing-only. |
| `ScenarioRunner.java:61,72` | `Instant.now()` for report bookends. | ✅ timing-only; book equality uses server-returned `BookSnapshot.bids/asks` field-by-field — no time fields involved. |
| `ConsistencyCheckRunner.java:64,85` | Same pattern. | ✅ timing-only. The §4.3 invariants compare `filled_qty` totals returned by the backend — server-stamped, never wire-time-reconstructed. |
| `RunContext.java:40` | `Instant.now()` for the run's `startedAt`. | ✅ display only — appears in the JSON report's metadata. |
| `RunReport.java:48` | `Instant.now()` as a fallback for `finishedAt` when `duration()` is called before the run completes (defensive). | ✅ timing-only. |

**No simulator code uses the JVM clock for ordering or correctness.**

## DTOs are server-issued

Both ordering-relevant DTOs round-trip the server's timestamp:

- [`MyOrderResponse.java`](src/main/java/com/sweta/limitorder/simulator/api/dto/MyOrderResponse.java) carries `createdAt` and `updatedAt` typed as `java.time.Instant` — Jackson deserialises them from the wire JSON without the JVM clock interfering.
- [`MyFillResponse.java`](src/main/java/com/sweta/limitorder/simulator/api/dto/MyFillResponse.java) carries `executedAt` similarly.

Backend invariant ([`backend/src/main/.../OrderRepository.java:108-125`](../backend/src/main/java/com/sweta/limitorder/persistence/OrderRepository.java#L108)): the `INSERT` deliberately omits the `created_at` column, so Postgres' `DEFAULT now()` fires. Backend Phase 10.3 added `OrderControllerIntegrationTest#createdAtComesFromDbClockNotJvm` to enforce this; the simulator inherits the property by construction.

## Test guard against regression

If a future contributor introduces a path like
"sort fills by the wire-arrival time of the WS frame" or
"compare two events using their `Instant.now()` capture", the rule
above is violated. The defence is two-fold:

1. **This document** — code review + PR comments.
2. **Test surface** — `multi-instance` mode's `books-equal-across-nodes`
   assertion already exercises the cross-node comparison path on
   server-issued data; if a regression added clock-based ordering, the
   assertion would flake under sustained load.

No code changes were required from the §9.3 review pass — the
simulator was already correct.
