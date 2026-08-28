# Load testing

Closes Tier D of the Load Readiness Plan: this platform had correctness-testing
rigor (TDD, sabotage verification) but nothing that answered "what happens at N
concurrent shoppers" — every capacity claim was a reasonable inference, not a
measured fact. `capacity_test.js` measures it directly.

## What it measures

Two real traffic patterns, at four fixed concurrency levels (100/300/600/1000
concurrent shoppers) tested in isolation, not as one continuous ramp — a ramp
blends stage-transition noise (VUs starting/stopping mid-measurement) into the
numbers and produces misleadingly bad results. Isolated levels answer the
actual question cleanly.

- **`browse`** — `shoppingProductList`, the highest-volume real traffic, with
  1-3s think-time between requests (so a VU represents one real concurrent
  shopper, not a tight-loop throughput hammer).
- **`checkout`** — `POST /api/orders`, the highest-stakes write, at roughly 8%
  of browse concurrency (checkout is naturally rarer than browsing at any
  given moment). A 422 (out of stock) counts as a correct rejection, not a
  failure — only 5xx/429/timeout count against the error rate.

## Running it

Requires a running backend pointed at a real Postgres + Redis with a seeded
product catalogue — point it at a throwaway environment, never a live one.

```bash
BASE_URL=http://localhost:8080 k6 run capacity_test.js
```

Or via Docker, if k6 isn't installed locally:

```bash
docker run --rm -i --add-host=host.docker.internal:host-gateway \
  -e BASE_URL=http://host.docker.internal:8080 \
  -v "$(pwd)":/scripts -w /scripts grafana/k6 run capacity_test.js
```

`setup()` fetches real variant ids from `shoppingProductList` — no fixture
file to keep in sync. It needs at least one product in the catalogue; a
larger, more realistic catalogue size gives a more representative result.

## Capacity target (Tier D2)

Stated target, set 2026-08-27 from the first real measurement on this stack:
**500 concurrent shoppers, checkout p95 under 500ms, error rate under 0.1%.**
Encoded as k6 thresholds checked at the 600-concurrent-shopper level (the
smallest tested level above the target) — `k6 run` exits non-zero if either
is violated.

Revisit this number if the platform's real traffic profile changes materially
(a client with a much larger catalogue or customer base) rather than treating
it as permanent.

## What this already caught

First real run (2026-08-27) found `quarkus.redis.max-pool-size` defaulting to
6 connections — every checkout hits Redis for rate limiting, and at 600+
concurrent shoppers the pool's wait queue filled and started rejecting with
`ConnectionPoolTooBusyException`. Fixed in `application.properties`
(`%prod.quarkus.redis.max-pool-size`/`max-pool-waiting`, configurable per
client). Postgres and the app itself held up cleanly through 1000 concurrent
shoppers on the same run — this was a real, specific, previously-unaudited
gap the load test caught directly, not a generic capacity ceiling.
