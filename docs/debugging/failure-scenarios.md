# Failure Scenario Catalogue

**Last updated:** 2026-10-29

Related: [Debugging playbook](debugging-playbook.md) · [Incident log](incident-log.md) · [Database fundamentals](../learning/database-fundamentals.md) · [Distributed systems](../learning/distributed-systems.md) · [Roadmap](../../LEARNING_ROADMAP.md)

> **Every scenario below is `PLANNED`.** None has been implemented, reproduced, or verified. A scenario becomes `IMPLEMENTED` when the faulty code/injector exists in the repo, and `VERIFIED` when an [incident-log](incident-log.md) entry with real evidence, a root cause, a fix, and a regression test exists. Details are design sketches **[ILLUSTRATIVE]**.

## Contents
- [Status table](#status-table)
- [FS-01 Race condition / lost update](#fs-01-race-condition--lost-update)
- [FS-02 Duplicate events](#fs-02-duplicate-events)
- [FS-03 Slow SQL / N+1](#fs-03-slow-sql--n1)
- [FS-04 Connection-pool exhaustion](#fs-04-connection-pool-exhaustion)
- [FS-05 Retry storm](#fs-05-retry-storm)
- [FS-06 Deadlock](#fs-06-deadlock)
- [FS-07 Memory leak](#fs-07-memory-leak)
- [FS-08 Downstream service outage](#fs-08-downstream-service-outage)
- [FS-09 Poison message and rebalance storm](#fs-09-poison-message-and-rebalance-storm)
- [Scenario template](#scenario-template)

## Status table

| ID | Scenario | Roadmap phase | Prereqs | Status | Incident |
|---|---|---|---|---|---|
| FS-01 | Race condition / lost update | 2 | JPA entities, concurrent test harness | PLANNED | — |
| FS-02 | Duplicate events | 4 | Kafka (or in-memory queue stand-in), consumer | PLANNED | — |
| FS-03 | Slow SQL / N+1 | 2 | Entities with relations, data seeding | PLANNED | — |
| FS-04 | Connection-pool exhaustion | 2/6 | JPA, pool metrics, load tool | PLANNED | — |
| FS-05 | Retry storm | 5 | Simulated flaky downstream, retry layer | PLANNED | — |
| FS-06 | Deadlock | 2/5 | Multi-row reservation logic, real DB preferred | PLANNED | — |
| FS-07 | Memory leak | 6 | Actuator metrics, heap tools | PLANNED | — |
| FS-08 | Downstream outage | 5 | Circuit breaker, simulated payment service | PLANNED | — |
| FS-09 | Poison message / rebalance storm | 4 | Kafka consumer | PLANNED | — |

Injectors should be **opt-in** (profile `chaos` or a test-only class), off by default, and never present in a production build.

---

## FS-01 Race condition / lost update
- **Concepts:** read-modify-write, isolation levels, optimistic/pessimistic locking, atomic updates. See [DB fundamentals](../learning/database-fundamentals.md#isolation-levels-and-anomalies).
- **Setup:** two threads/requests update the same order (or decrement the same stock) using load→modify→save with no version.
- **Fault injection:** a latch forces both threads to read before either writes.
- **Symptom to observe:** final value inconsistent with the number of successful requests (e.g. stock 1000 after 200 decrements → 850 instead of 800), no error logged.
- **Investigation path:** reproduce with test; log versions/values; read SQL log; hypothesise interleaving; confirm via deterministic latch.
- **Fixes to compare:** `@Version`; `SELECT … FOR UPDATE`; atomic conditional `UPDATE`.
- **Regression test:** N-thread test asserting exact final value / exactly-one winner, repeated ≥ 20×.
- **Measure:** correctness and throughput of each fix (state DB engine; H2 is not representative).
- **Interview use:** "How do you prevent overselling?"

## FS-02 Duplicate events
- **Concepts:** at-least-once delivery, idempotent consumers, inbox table. See [distributed systems](../learning/distributed-systems.md#idempotency-and-duplicate-handling).
- **Setup:** a consumer that reserves stock per `OrderCreated`.
- **Fault injection:** deliver the same event 3× sequentially and concurrently; kill consumer between effect and offset commit.
- **Symptom:** stock reserved multiple times for one order.
- **Investigation:** group effects by `eventId`/`orderId`; inspect offsets and logs for redelivery; reason about where the dedupe record must be.
- **Fix:** inbox `(consumer,event_id)` unique insert in the same transaction as the effect.
- **Regression test:** concurrent duplicate delivery → exactly one effect.
- **Interview use:** "Consumer crashed after processing but before commit—what happens?"

## FS-03 Slow SQL / N+1
- **Concepts:** lazy loading, fetch strategies, indexes, query plans. See [N+1](../learning/database-fundamentals.md#the-n1-query-problem), [query plans](../learning/database-fundamentals.md#query-plans).
- **Setup:** list-orders endpoint touching lazy items; large seeded table; missing index on `(customer_id, created_at)`.
- **Symptom:** latency grows with page size/data volume; SQL log shows 1+N statements; seq scan in `EXPLAIN`.
- **Investigation:** SQL log counting, Hibernate statistics, `EXPLAIN ANALYZE`, slow-query log.
- **Fix:** fetch join/entity graph/batch size; composite index; keyset pagination.
- **Regression test:** statement-count assertion; (optional) plan assertion on a real DB.
- **Measure:** latency percentiles and statement count before/after (state data volume and engine).

## FS-04 Connection-pool exhaustion
- **Concepts:** pools, Little's law, transaction scope. See [connection pools](../learning/database-fundamentals.md#connection-pools), [capacity](../hld/scalability-and-capacity.md#littles-law-and-connection-pools).
- **Setup:** pool size 2–5; a handler holds a transaction while sleeping/calling a slow service.
- **Fault injection:** concurrent requests > pool size.
- **Symptom:** all endpoints time out ("Connection is not available, request timed out"), CPU low.
- **Investigation:** pool metrics (`active`=max, `pending` rising), thread dump (threads in `getConnection`), find the holder threads.
- **Fix:** shorten transactions, remove remote calls from them, timeouts, right-size pool — not simply a larger pool.
- **Regression test:** test with small pool that proves the endpoint doesn't hold connections across the remote call.
- **Interview use:** "Everything times out but CPU is idle."

## FS-05 Retry storm
- **Concepts:** retries, backoff, jitter, budgets, amplification. See [retries](../learning/distributed-systems.md#retries-backoff-jitter-and-timeouts).
- **Setup:** simulated downstream that fails for a window; callers retry at two layers.
- **Fault injection:** downstream returns 503 for 10 s, then recovers.
- **Symptom:** request rate at the downstream is a multiple of the original rate; recovery delayed after the fault clears.
- **Investigation:** downstream request-rate chart, attempt counts per request ID, layers of retry.
- **Fix:** retry at one layer, exponential backoff + full jitter, retry budget, circuit breaker.
- **Regression test:** attempts per logical request ≤ limit; backoff delays within bounds (inject `Clock`/sleeper).
- **Measure:** peak downstream RPS and time-to-recovery before/after.

## FS-06 Deadlock
- **Concepts:** lock ordering, transaction retry. See [deadlocks](../learning/database-fundamentals.md#deadlocks).
- **Setup:** inventory reservation locks product rows in the order given by the request.
- **Fault injection:** concurrent orders for (A,B) and (B,A) with a latch between locks. Use a DB with real row locking (PostgreSQL preferred).
- **Symptom:** intermittent "deadlock detected" errors; one request fails.
- **Investigation:** DB log of the deadlock; the two statements and lock order; thread dump showing both threads in JDBC.
- **Fix:** canonical lock ordering (sort IDs), shorter transactions, retry victim.
- **Regression test:** repeated concurrent reversed-order reservations → zero failures.

## FS-07 Memory leak
- **Concepts:** heap, GC, dominators, static caches, ThreadLocals. See [JVM memory](../learning/java-backend.md#jvm-memory).
- **Setup:** an endpoint adds entries to an unbounded static map (or `ThreadLocal` never cleared).
- **Fault injection:** sustained load with small `-Xmx`.
- **Symptom:** heap-after-GC trends upward; GC frequency rises; eventually `OutOfMemoryError`.
- **Investigation:** GC log, `jcmd GC.class_histogram`, heap dump → dominator tree.
- **Fix:** bounded cache (e.g. size/TTL), cleanup, correct scope.
- **Regression test:** soak test or a unit test asserting cache bound; alert on heap-after-GC growth.

## FS-08 Downstream service outage
- **Concepts:** timeouts, circuit breaker, bulkhead, fallback, outbox buffering. See [circuit breakers](../learning/distributed-systems.md#circuit-breakers-and-bulkheads).
- **Setup:** simulated payment service that can be turned off.
- **Fault injection:** stop it; later restore.
- **Symptom (without protection):** threads pile up on payment calls; unrelated endpoints slow or fail.
- **Investigation:** thread dump, latency by endpoint, breaker metrics.
- **Fix:** timeouts, breaker + bulkhead, defined fallback (reject with 503 or queue), reconciler for stragglers.
- **Regression test:** with downstream down, unrelated endpoints stay within latency bounds; orders reach a defined state.

## FS-09 Poison message and rebalance storm
- **Concepts:** consumer error handling, DLT, `max.poll.interval.ms`. See [Kafka](../learning/distributed-systems.md#kafka-producers-consumers-partitions-consumer-groups).
- **Setup:** a malformed event, and a handler slower than the poll interval.
- **Symptom:** partition stuck on one offset (lag grows) or repeated rebalances with duplicate processing.
- **Investigation:** consumer-group describe, logs for repeated failure at the same offset, rebalance logs.
- **Fix:** bounded retries then dead-letter topic with alert; shorter processing or tuned poll settings; idempotent handler.
- **Regression test:** poison record goes to DLT and later records proceed.

---

## Scenario template

```markdown
## FS-NN <title>
- Concepts / handbook links:
- Setup:
- Fault injection:
- Symptom to observe:
- Investigation path:
- Fix(es):
- Regression test:
- Measurements to capture:
- Interview use:
- Status: PLANNED | IMPLEMENTED | VERIFIED  (+ link to INC-NNN)
```
