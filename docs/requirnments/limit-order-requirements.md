# Limit Order Matching App

## Contents

1. Overview
2. Functional Requirements
3. Non-Functional Requirements
4. Simulator
5. Sample Data
6. Screen Layouts
7. API Sketch
8. Deliverables
9. Stretch Goals

---

## 1. Overview

Use Microsoft Visual Studio Code and GitHub Copilot to build a simplified limit-order matching platform (stock-market style) where multiple users concurrently place buy and sell orders for a small set of products (symbols). A continuously running matching engine pairs demand (buy orders) with supply (sell orders), produces trades, and streams updated order-book state back to every connected user.

The app must be:

- **Multi-user** — many users log in and place orders at the same time.
- **Multi-threaded** — the matching engine and API request handlers run concurrently without corrupting the order book.
- **Multi-instance** — the backend must be runnable as ≥ 2 instances behind a load balancer. Shared state lives in a datastore (Postgres / Redis / in-memory grid — candidate's choice), not in the JVM heap of a single node.

**Stack:**

- Editor / Al assistant: Microsoft Visual Studio Code + GitHub Copilot (or equivalent Al coding assistant driven from within VS Code).
- Frontend: Angular (latest LTS).
- Backend: Java 17+ / Spring Boot 3.x.
- Persistence: candidate's choice (Postgres / H2 / Redis). Justify the choice.
- Build: Maven or Gradle.
- Simulator: a separate runnable (CLI, Spring Boot profile, or standalone main) that drives load and verifies correctness. It must not be the same process as the main backend

### Expectations on Al-generated code

We expect the code to be primarily machine-generated. The point of this exercise is not to test typing speed — it is to see how well a candidate can drive, review, and own Al-produced code.

During the interview the candidate will:

- give a 5-minute functional overview of the product,
- give a 10-minute technical design walkthrough, and then
- take ~30 minutes of Q&A in which the interviewers will pick random areas of the code and ask for deep dives.

Candidates are expected to have complete familiarity with the generated code. For any piece of code the interviewers point at, the candidate should be able to:

- explain what it does,
- explain why it is written that way (design choices, trade-offs, alternatives considered),
- speak to the merits of the code — what is good about it, what they would improve, what risks remain.

"The tool wrote it" is not an acceptable answer. If the candidate cannot defend a section of the codebase, that section effectively does not

---

## 2. Functional Requirements

### 2.1 Orders

Each order has:

| Field     | Notes                                     |
| --------- | ----------------------------------------- |
| orderId   | Server-assigned, globally unique.         |
| userId    | From the authenticated session.           |
| symbol    | e.g. AAPL, MSFT. Pre-configured list.     |
| side      | BUY Or SELL .                             |
| type      | LIMIT Or MARKET.                          |
| price     | Required for LIMIT; ignored for MARKET,   |
| quantity  | Total requested quantity.                 |
| filledQty | Running total, updated as fills happen.   |
| status    | OPEN. PARTIAL, FILLED. CANCELLED          |
| createdAt | Server timestamp, used for time priority. |

**Rules:**

- Users can cancel their own OPEN / PARTIAL orders.
- A MARKET order must attempt to Til immediately against the best avallable opposite side. If supply is insufficient, it partially fills and the remainder is rejected (not left on the book) — status - CANCELLED with reason INSUFFICIENT_LIQUIDITY,
- A LIMIT order rests on the book until matched, cancelled, or tilled.

### 2.2 Matching Engine

- Runs continuously on the backend (scheduled task, dedicated thread, or triggered per incoming order — candidate's call; justify it).
- For each symbol, maintain two sorted books:
  - Bids (BUY): highest price first, then earliest time first.
  - Asks (SELL): lowest price first, then earliest time first.
- A match occurs when bestBid.price >= bestask.price. Trade price = resting order's price (the one that was on the book first). Trade quantity = min(remainingBid, remainingAsk) •
- Partial fills are allowed; update filledoty and status on both sides and emit a Trade record.
- The engine must be safe under concurrent order submission from multiple API nodes — this is the core concurrency challenge. Candidate should discuss their locking / single-writer / queue approach.

### 2.3 User-Facing Views

Every logged-in user can see, updated in near real-time (WebSocket or short polling):

- Market snapshot per symbol: top 5 bids, top 5 asks, last trade price.
- Total demand (sum of open BUY quantity) and total supply (sum of open SELL quantity) per symbol.
- My orders — the user's own open and historical orders with their current status.
- My fills -trades the user participated in.

### 2.4 Authentication

Keep it simple: hard-coded user list or basic signup + JWT. No password reset, no roles. At least 4 pre-seeded users so the reviewer can open multiple browser tabs.

---

## 3. Non-Functional Requirements

- **Correctness under concurrency** is the #1 grading criterion. No lost orders, no duplicate fills, no negative filledoty, no trade for more than the resting quantity.
- **Multi-instance readiness:** run 2+ backend instances against the same datastore. A trade executed on node A must be visible on node B within 1 second.
- **Observability:** structured logs for every order accepted / rejected / matched; a /actuator/health endpoint; basic metrics orders/sec, matches/sec) exposed somehow.
- **Idempotency:** clients may send a clientOrderId; the server must de-dupe so a retried POST does not create two orders.

---

## 4. Simulator (Separate Deliverable)

A standalone program the candidate writes to exercise the system. Must support:

1. **Load mode** — spawn N virtual users, each submitting random BUY / SELL / MARKET orders at a configurable rate for a configurable duration.
2. **Scenario mode** — replay a deterministic sequence from a JSON/CSV file (see sample data below) and assert expected trades occurred.
3. **Consistency check** — at the end of a run, verify:
   - sum(filledty on BUY side) -- sum(filledty on SELL side) for every symbol.
   - No order has filledoty > quantity.
   - Every Trade references two valid orders on opposite sides.
4. **Multi-instance test** — submit orders to both backend nodes in parallel and confirm the book converges to the same state regardless of which node received which order.

---

# 5. Sample Data

## 5.1 Products

```json
[
  { "symbol": "AAPL", "name": "Apple Inc.", "refPrice": 180.0 },
  { "symbol": "MSFT", "name": "Microsoft Corp.", "refPrice": 420.0 },
  { "symbol": "GOOGL", "name": "Alphabet Inc.", "refPrice": 155.0 },
  { "symbol": "TSLA", "name": "Tesla Inc.", "refPrice": 240.0 },
  { "symbol": "AMZN", "name": "Amazon.com Inc.", "refPrice": 190.0 }
]
```

## 5.2 Users (seed)

```json
[
  { "userId": "u1", "name": "Alice", "password": "alice123" },
  { "userId": "u2", "name": "Bob", "password": "bob123" },
  { "userId": "u3", "name": "Charlie", "password": "charlie123" },
  { "userId": "u4", "name": "Diana", "password": "diana123" }
]
```

## 5.3 Seed Orders â crosses immediately in a known way

Use this to verify the matching engine on startup.

```
clientOrderId,userId,symbol,side,type,price,quantity
c001,u1,AAPL,SELL,LIMIT,181.00,100
c002,u2,AAPL,SELL,LIMIT,180.50,200
c003,u3,AAPL,SELL,LIMIT,182.00,150
c004,u4,AAPL,BUY,LIMIT,180.00,50
c005,u1,AAPL,BUY,LIMIT,180.50,120
c006,u2,MSFT,SELL,LIMIT,421.00,80
c007,u3,MSFT,BUY,LIMIT,421.50,50
c008,u4,TSLA,SELL,LIMIT,239.00,200
c009,u1,TSLA,BUY,MARKET,,100
c010,u2,GOOGL,SELL,LIMIT,155.25,300
```

Expected outcome after processing in order:

- `c005` crosses `c002` at **180.50**, qty **120** â both partially/fully filled ( `c002` remaining 80, `c005` FILLED).
- `c007` crosses `c006` at **421.00**, qty **50** â `c007` FILLED, `c006` remaining 30.
- `c009` (MARKET BUY 100 on TSLA) crosses `c008` at **239.00**, qty **100** â `c009` FILLED, `c008` remaining 100.
- `c001` , `c003` , `c004` , `c010` , and the remainders of `c002` / `c006` / `c008` rest on the book.

## 5.4 Snapshot after seed â expected book

```
AAPL    BID                 ASK
        u4 180.00 x50       u2 180.50 x80       <-- remaining after c005 hit
                            u1 181.00 x100
                            u3 182.00 x150

MSFT    BID                 ASK
                            u2 421.00 x30       <-- remaining after c007 hit

TSLA    BID                 ASK
        (empty)             u4 239.00 x100      <-- remaining after c009 hit

GOOGL   BID                 ASK
                            u2 155.25 x300
```

## 5.5 Stress scenario for simulator

- 4 users, 5 symbols, **5000 orders** over 60 seconds.
- 70% LIMIT / 30% MARKET. 50% BUY / 50% SELL.
- Prices sampled as `refPrice * (1 + N(0, 0.01))`, quantities uniform [10, 500].
- Run simulator against **2 backend instances** simultaneously.
- Assert consistency rules from Â§4.3 at the end.

# 6. Screen Layouts

Simple, not pretty. Candidate may restyle but must cover these four views.

## 6.1 Login

```
+----------------------------------------+
|          Limit Order App               |
+----------------------------------------+
|                                        |
|   Username: [_______________]          |
|   Password: [_______________]          |
|                                        |
|          [ Log in ]                    |
|                                        |
+----------------------------------------+
```

## 6.2 Market Overview (landing page after login)

```
+------------------------------------------------------------------------+
|  Limit Order App                          Alice | [Log out]            |
+------------------------------------------------------------------------+
|  Market Overview                                                       |
+--------+--------+----------+----------+--------+---------+------------+
| Symbol | Last   | Best Bid | Best Ask | Demand | Supply  | View       |
+--------+--------+----------+----------+--------+---------+------------+
| AAPL   | 180.50 | 180.00   | 180.50   | 50     | 330     | [Open]     |
| MSFT   | 421.00 | -        | 421.00   | 0      |  30     | [Open]     |
| GOOGL  | -      | -        | 155.25   | 0      | 300     | [Open]     |
| TSLA   | 239.00 | -        | 239.00   | 0      | 100     | [Open]     |
| AMZN   | -      | -        | -        | 0      |   0     | [Open]     |
+--------+--------+----------+----------+--------+---------+------------+
|  (auto-refresh indicator; â live)                                      |
+------------------------------------------------------------------------+
```

## 6.3 Symbol Detail â Order Book + Place Order

```
+------------------------------------------------------------------------+
|  AAPL â Apple Inc.                                    [< Back]         |
+------------------------------------------------------------------------+
|                                                                        |
|   BIDS (Demand)              ASKS (Supply)                             |
|   +------+---------+-------+ +-------+---------+-------+              |
|   | Qty  | Price   | Users | | Qty   | Price   | Users |              |
|   +------+---------+-------+ +-------+---------+-------+              |
|   | 50   | 180.00  |   1   | | 80    | 180.50  |   1   |              |
|   |      |         |       | | 100   | 181.00  |   1   |              |
|   |      |         |       | | 150   | 182.00  |   1   |              |
|   +------+---------+-------+ +-------+---------+-------+              |
|                                                                        |
|   Total Demand: 50       Total Supply: 330      Last: 180.50          |
|                                                                        |
+------------------------------------------------------------------------+
|  Place Order                                                           |
|   Side:       ( ) BUY    ( ) SELL                                      |
|   Type:       ( ) LIMIT  ( ) MARKET                                    |
|   Price:      [________]   (disabled if MARKET)                        |
|   Quantity:   [________]                                               |
|               [ Submit ]                                               |
+------------------------------------------------------------------------+
```

## 6.4 My Orders & Fills

```
+------------------------------------------------------------------------+
|  My Orders                                                             |
+----------+--------+------+--------+--------+-----+--------+-----------+
| OrderId  | Symbol | Side | Type   | Price  | Qty | Filled | Status    |
+----------+--------+------+--------+--------+-----+--------+-----------+
| o-00015  | AAPL   | BUY  | LIMIT  | 180.50 | 120 | 120    | FILLED    |
| o-00021  | MSFT   | BUY  | LIMIT  | 419.00 | 100 |   0    | OPEN [X]  |
| o-00028  | TSLA   | BUY  | MARKET|   -    | 100 | 100    | FILLED    |
+----------+--------+------+--------+--------+-----+--------+-----------+
|  [X] = cancel button on open/partial rows                              |
+------------------------------------------------------------------------+
|  My Fills                                                              |
|                                                                        |
+----------+--------+------+--------+-----+----------------------+------+
| TradeId  | Symbol | Side | Price  | Qty | Time                 | Counter|
+----------+--------+------+--------+-----+----------------------+------+
| t-00007  | AAPL   | BUY  | 180.50 | 120 | 2026-04-21 10:14:02 | u2    |
| t-00011  | TSLA   | BUY  | 239.00 | 100 | 2026-04-21 10:17:45 | u4    |
+----------+--------+------+--------+-----+----------------------+------+
```

# 7. API Sketch (suggested, not prescriptive)

```
POST    /api/auth/login                              -> { token }
GET     /api/symbols                                 -> [ { symbol, name, refPrice } ]
GET     /api/book/{symbol}                           -> { bids:[...], asks:[...], last }
GET     /api/book/{symbol}/totals                    -> { demand, supply }
POST    /api/orders          { clientOrderId,
                               symbol, side,
                               type, price, qty }    -> { orderId, status }
DELETE  /api/orders/{orderId}                        -> { status }
GET     /api/orders/mine                             -> [ ...orders ]
GET     /api/fills/mine                              -> [ ...fills ]
WS      /ws/book/{symbol}                            -> streaming book updates
WS      /ws/orders/mine                              -> streaming order-status updates
```

---

## 8. Deliverables

1. Git repo with backend/, frontend/, simulator/ folders and a top-level README.d.
2. docker-compose.yml (or equivalent) that starts: datastore + 2 backend instances + nginx/haproxy in front + Angular dev server (or static build served by nginx). A reviewer should run docker compose up and have a working system.
3. README.md covering:
   - How to run locally and in compose.
   - Architecture diagram (hand-drawn is fine).
   - Concurrency strategy - how you prevent lost updates across instances.
   - Trade-offs you made and what you'd do with more time.
4. Simulator with at least the scenario-replay mode and the consistency assertions.
5. Tests — a handful of JUnit tests around the matching engine covering price-time priority, partial fills, market-order insufficient liquidity, and concurrent submission.
6. Agent chat history export — the full conversation transcripts) with the Al coding assistant (Copilot Chat or equivalent), exported as markdown / text / JSON and committed to the repo under does/ai-chat/. Reviewers will read this to understand how the solution was produced.
7. Submission - push the complete solution to a public GitHub repository and share the repo URL with the interview panel at least one (1) calendar day before the interview. Late submissions will not be reviewed in depth; the interview will proceed against whatever is in the repo at the cut-off. You may continue committing fixes after that, but the reviewers' read-through happens before the interview starts.
