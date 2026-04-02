# Enrolment Store Proxy — Throttling

## Overview

When a user submits multiple files in a single submission, every file triggers an
individual outbound call to `enrolment-store-proxy`. Without any control, a burst of
25 files would produce 25 simultaneous HTTP requests hitting the downstream service at
the same instant.

`ThrottlingService` prevents this by placing **two independent gates** in front of every
outbound call made through `EnrolmentStoreProxyConnector`. Both gates must be passed
before a request is dispatched.

---

## The Two Gates

```
Every call to EnrolmentStoreProxyConnector.sendFileNotification(...)
         |
         v
+-----------------------------------------------------+
|  GATE 1 - Token Bucket  (per-second rate limit)     |
|                                                     |
|  "How many new requests can START this second?"     |
|                                                     |
|  Config:  throttle.enrolment-store-proxy            |
|             .max-per-second = 2                     |
|                                                     |
|  Budget refills every 1 000 ms.                     |
|  If budget = 0  ->  caller BLOCKS until next sec.   |
+------------------------+----------------------------+
                         |  token acquired
                         v
+-----------------------------------------------------+
|  GATE 2 - Semaphore  (concurrency limit)            |
|                                                     |
|  "How many requests are in-flight RIGHT NOW?"       |
|                                                     |
|  Config:  throttle.enrolment-store-proxy            |
|             .max-concurrent = 2                     |
|                                                     |
|  If all permits taken -> caller BLOCKS until        |
|  a previous request finishes and releases a permit. |
+------------------------+----------------------------+
                         |  permit acquired
                         v
            HTTP GET -> enrolment-store-proxy
                         |
                         v  (response received)
                Semaphore permit released
                -> next queued request unblocks
```

---

## Gate 1 — Token Bucket (Rate Limiter)

### Concept

Think of the bucket as a **voucher dispenser** that issues a fixed number of vouchers per
second. Each new request must collect a voucher before it can proceed. When all vouchers
for the current second are taken, the next request waits for the dispenser to refill at
the start of the next 1-second window.

### How the implementation works

```
+------------------------------------------+
|  TokenBucket  (max-per-second = 2)       |
|                                          |
|  Second 1 window  [0ms - 999ms]          |
|  +----------+----------+----------+      |
|  | Token 1  | Token 2  |  empty   |      |
|  | req-A OK | req-B OK | req-C BLOCKS    |
|  +----------+----------+----------+      |
|                                          |
|  t = 1 000ms  ->  window resets          |
|                                          |
|  Second 2 window  [1000ms - 1999ms]      |
|  +----------+----------+                 |
|  | Token 1  | Token 2  |                 |
|  | req-C OK | req-D OK |                 |
|  +----------+----------+                 |
+------------------------------------------+
```

### Thread safety

The bucket uses `AtomicLong` (window timestamp) and `AtomicInteger` (tokens used) with
Compare-And-Swap (CAS) loops, so multiple threads can compete for tokens without explicit
locks and without race conditions.

```
Thread A reads tokensUsed = 1  ->  CAS(1->2) succeeds  ->  token granted
Thread B reads tokensUsed = 1  ->  CAS(1->2) fails     ->  retries -> reads 2 -> BLOCKS
```

### Setting to 0 disables rate limiting

```hocon
throttle.enrolment-store-proxy.max-per-second = 0   # unlimited - Gate 1 is bypassed
```

---

## Gate 2 — Semaphore (Concurrency Limiter)

### Concept

Think of the semaphore as a **car park with N spaces**. Each in-flight request occupies
one space. When the car park is full, the next request queues at the barrier until a
space is freed.

### How the implementation works

```
+------------------------------------------------------+
|  Semaphore  (max-concurrent = 2)                     |
|                                                      |
|  t=0ms    req-A acquires permit  [ A | _ ]  1 free   |
|  t=0ms    req-B acquires permit  [ A | B ]  0 free   |
|  t=0ms    req-C tries to acquire -> BLOCKS (0 free)  |
|  t=0ms    req-D tries to acquire -> BLOCKS (0 free)  |
|                                                      |
|  t=300ms  req-A completes -> releases permit         |
|           [ _ | B ]  1 free                          |
|           req-C wakes, acquires  [ C | B ]  0 free   |
|                                                      |
|  t=500ms  req-B completes -> releases permit         |
|           [ C | _ ]  1 free                          |
|           req-D wakes, acquires  [ C | D ]  0 free   |
+------------------------------------------------------+
```

### Fair queuing

The semaphore is constructed with `fair = true`:

```scala
new Semaphore(appConfig.maxConcurrentEnrolmentStoreProxyRequests, true)
```

This guarantees FIFO ordering — threads are unblocked in the same order they arrived,
preventing starvation.

---

## How Both Gates Work Together

### Scenario — 6 files, max-per-second = 2, max-concurrent = 2

```
t = 0ms
  Requests 1-6 all arrive simultaneously from a single submission burst.

  +----------------------------------------------------------------+
  |  GATE 1 (Token Bucket - 2 tokens this second)                  |
  |                                                                |
  |  req-1 -> token OK    req-2 -> token OK                        |
  |  req-3 -> BLOCKS      req-4 -> BLOCKS                          |
  |  req-5 -> BLOCKS      req-6 -> BLOCKS                          |
  +-----------------------------+----------------------------------+
                                | req-1, req-2 pass through
                                v
  +----------------------------------------------------------------+
  |  GATE 2 (Semaphore - 2 permits)                               |
  |                                                                |
  |  req-1 -> permit OK   req-2 -> permit OK                       |
  |  Both now in-flight -> enrolment-store-proxy                   |
  +----------------------------------------------------------------+

t = 1 000ms  (second boundary - token bucket refills)
  req-3 -> token OK    req-4 -> token OK
  If req-1 or req-2 still in-flight, req-3/4 wait at Gate 2.

t = ~1 200ms  (req-1 finishes, semaphore permit released)
  req-3 acquires permit -> starts

... and so on until all 6 complete.
```

### Timeline view (6 requests, max-per-second=2, max-concurrent=2)

```
       t=0ms              t=1000ms           t=2000ms           t=3000ms
         |                    |                  |                  |
req-1   [=====IN-FLIGHT=====]
req-2   [=====IN-FLIGHT=====]
req-3   [--RATE BLOCKED------][=====IN-FLIGHT=====]
req-4   [--RATE BLOCKED------][=====IN-FLIGHT=====]
req-5   [--------RATE BLOCKED-----------][=====IN-FLIGHT=====]
req-6   [--------RATE BLOCKED-----------][=====IN-FLIGHT=====]
         ^                    ^                  ^
         2 tokens used        2 tokens used      2 tokens used
         (sec 1)              (sec 2)            (sec 3)
```

---

## Where in the Code This Lives

```
EnrolmentStoreProxyConnector.sendFileNotification(fileReference)
    |
    +-> throttlingService.throttleEnrolmentStoreProxyCall {
            |
            +-- rateLimiter.acquire("EnrolmentStoreProxy")   <- Gate 1 (blocks if needed)
            +-- semaphore.acquire()                          <- Gate 2 (blocks if needed)
            |
            +--> httpClient.get(url).execute[HttpResponse]   <- actual HTTP call
                    |
                    +--> semaphore.release()                 <- always released (finally block)
        }
```

The `throttle(...)` private method wraps the operation in `scala.concurrent.blocking {}`,
which signals to the Play/Pekko thread pool that a blocking operation is in progress so it
can allocate an extra thread rather than starving the pool.

---

## Configuration

```hocon
# conf/application.conf

throttle {
  enrolment-store-proxy {

    # Maximum number of simultaneous outbound requests to enrolment-store-proxy.
    # Further requests wait until one of the in-flight calls completes.
    max-concurrent = 2

    # Maximum number of NEW requests started per second (Token Bucket).
    # Set to 0 for unlimited (disables Gate 1 entirely).
    max-per-second = 2
  }
}
```

| Setting | Effect | Increase to... | Decrease to... |
|---|---|---|---|
| `max-concurrent` | Caps simultaneous in-flight requests | Allow more parallel calls | Protect a slower downstream |
| `max-per-second` | Caps how many NEW calls start each second | Increase throughput | Smooth out burst traffic |
| `max-per-second = 0` | Disables Gate 1 entirely | — | Set a positive integer to re-enable |

> **Defaults (when not configured):** `max-concurrent = 5`, `max-per-second = 0` (unlimited rate).

---

## Monitoring

The `DiagnosticsController` exposes `GET /eacd-file-processor/admin/throttle/status`
which returns a live snapshot while a burst is in-flight:

```json
{
  "enrolmentStoreProxy": {
    "maxConcurrent": 2,
    "availablePermits": 0,
    "currentlyProcessing": 2,
    "maxPerSecond": 2,
    "tokensRemainingThisSecond": 0
  }
}
```

| Field | Meaning |
|---|---|
| `maxConcurrent` | Configured concurrency cap |
| `availablePermits` | Semaphore slots free right now |
| `currentlyProcessing` | Requests in-flight right now (`maxConcurrent - availablePermits`) |
| `maxPerSecond` | Configured per-second rate cap (`0` = unlimited) |
| `tokensRemainingThisSecond` | Token Bucket tokens still available this second (`-1` = unlimited) |

### Reading the snapshot

```
availablePermits = 0  AND  currentlyProcessing = maxConcurrent
  -> Gate 2 (Semaphore) is the active bottleneck right now.

tokensRemainingThisSecond = 0
  -> Gate 1 (Token Bucket) is the active bottleneck right now.

Both = 0
  -> Both gates are saturated; requests are queued behind both.
```

---

## Testing the Throttle Locally

Start the service with the test-only router:

```bash
sbt "run -Dapplication.router=testOnlyDoNotUseInAppConf.Routes"
```

Trigger a burst of 25 calls via the simulation script:

```bash
scripts/simulate_enrolment_store_proxy_throttling.sh \
  --base-url http://localhost:9867 \
  --service-path /eacd-file-processor \
  --count 25
```

Expected output with `max-concurrent = 2` and `max-per-second = 2`:

```
Trigger response:
  status=200
  body={"triggered":25,"elapsedMs":12800}
  measuredElapsedSeconds=12

Observed throttling (last 5 samples):
  {"enrolmentStoreProxy":{"maxConcurrent":2,"availablePermits":0,"currentlyProcessing":2,"maxPerSecond":2,"tokensRemainingThisSecond":0}}
```

- `elapsedMs ~= 12 000` — 25 calls at 2/sec = ~12 seconds to drain the queue
- `currentlyProcessing = 2` — Gate 2 concurrency cap is holding
- `tokensRemainingThisSecond = 0` — Gate 1 rate cap is active

---

## Summary

```
+----------------------------------------------------------------------------+
|                                                                            |
|  25 files submitted simultaneously (no inbound throttle)                  |
|                          |                                                 |
|                          v                                                 |
|  EnrolmentStoreProxyConnector.sendFileNotification  x25 concurrent        |
|                          |                                                 |
|                          v                                                 |
|  +------------------------------------------------------------------------+|
|  |  ThrottlingService                                                     ||
|  |                                                                        ||
|  |  Gate 1 - Token Bucket    max-per-second  = 2  (2 new req/sec max)    ||
|  |  Gate 2 - Semaphore       max-concurrent  = 2  (2 in-flight max)      ||
|  |                                                                        ||
|  |  Result: enrolment-store-proxy receives at most                       ||
|  |          2 calls/second  AND  2 simultaneous in-flight calls           ||
|  +------------------------------------------------------------------------+|
|                          |                                                 |
|                          v                                                 |
|  enrolment-store-proxy  - receives a controlled, predictable rate          |
|                         - never bombarded regardless of submission size    |
|                                                                            |
+----------------------------------------------------------------------------+
```

