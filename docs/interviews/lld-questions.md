# LLD Interview Questions

**Last updated:** 2026-10-29

Related: [HLD questions](hld-questions.md) · [Backend notes](backend-interview-notes.md) · [Mock interview log (evidence)](mock-interview-log.md) · [Class design](../lld/class-design.md) · [Order service LLD](../lld/order-service.md)

## Contents
- [How to use this file](#how-to-use-this-file)
- [Universal LLD framework (45 minutes)](#universal-lld-framework-45-minutes)
- [Status tracker](#status-tracker)
- [Questions](#questions): [01](#lld-01-order-state-machine) · [02](#lld-02-idempotency-key-store) · [03](#lld-03-token-bucket-rate-limiter) · [04](#lld-04-thread-safe-lru-cache) · [05](#lld-05-inventory-reservation-service) · [06](#lld-06-pluggable-pricing-and-discount-engine) · [07](#lld-07-retry-policy-and-circuit-breaker-library) · [08](#lld-08-bounded-producerconsumer-pipeline)

## How to use this file

Practise by **writing real compilable Java** (JUnit tests included) in a scratch project or the repo, timed at 45–60 minutes, then log it in the [mock interview log](mock-interview-log.md). Statuses: `NOT PRACTISED` → `PRACTISED` → `MASTERED` (evidence rules in the [log](mock-interview-log.md#status-definitions)). **All questions are currently NOT PRACTISED.** Project links point to planned designs unless noted; the repository currently contains only the Spring Boot skeleton.

## Universal LLD framework (45 minutes)

1. **Clarify** requirements, scale, concurrency needs, extension points (5 min). State assumptions.
2. **Identify entities and use cases**; write the public API (interfaces/method signatures) first.
3. **Model**: classes, relationships, invariants, immutability, error handling.
4. **Choose patterns only with a stated force** (strategy for variants, state for lifecycle, etc.).
5. **Concurrency:** what is shared and mutable? Which lock/atomic/immutable approach? Where are check-then-act races?
6. **Write core code** for the main flow; keep it compilable and readable.
7. **Test plan:** unit tests, edge cases, concurrency test.
8. **Extensions:** what changes if requirements grow? SOLID check.

Evaluation rubric (self-score 1–5): requirements clarity · API/class design · correctness incl. edge cases · concurrency safety · extensibility without over-engineering · testing · communication.

## Status tracker

| ID | Question | Difficulty | Status |
|---|---|---|---|
| LLD-01 | Order state machine | Easy–Medium | NOT PRACTISED |
| LLD-02 | Idempotency key store | Medium | NOT PRACTISED |
| LLD-03 | Token-bucket rate limiter | Medium | NOT PRACTISED |
| LLD-04 | Thread-safe LRU cache | Medium | NOT PRACTISED |
| LLD-05 | Inventory reservation service | Medium–Hard | NOT PRACTISED |
| LLD-06 | Pricing and discount engine | Medium | NOT PRACTISED |
| LLD-07 | Retry policy / circuit breaker | Hard | NOT PRACTISED |
| LLD-08 | Bounded producer/consumer pipeline | Medium | NOT PRACTISED |

## Questions

### LLD-01 Order state machine
- **Difficulty:** Easy–Medium
- **Concepts tested:** enums with behaviour, State pattern vs transition table, invariants, exceptions, testability, idempotent transitions.
- **Hints:** Where do illegal transitions get rejected? How do you test *all* transition pairs? How do you add `RETURNED` later?
- **Answer framework:** list states/events → transition table → `Order` aggregate owns transitions → domain exception → unit tests over the matrix → extension: audit trail, hooks (events on transition).
- **Trade-offs:** enum+map vs State classes vs library (Spring Statemachine); mutable aggregate vs returning new immutable instances; no-op vs error on repeated transition.
- **Follow-ups:** Make it safe under concurrent transitions. Add per-state side effects without a giant `switch`. Persist history.
- **Project links:** [State machine](../lld/order-service.md#state-machine) · [Illustrative code](../lld/class-design.md#illustrative-code) · [Test design](../lld/class-design.md#test-design).

### LLD-02 Idempotency key store
- **Difficulty:** Medium
- **Concepts tested:** interfaces/ports, atomic check-and-set, unique constraints, TTL, request hashing, thread safety, in-memory fake vs DB implementation.
- **Hints:** What does `begin(key)` return for in-flight vs completed vs new? How is atomicity guaranteed?
- **Answer framework:** API (`Outcome begin(key, hash)`, `complete(key, response)`) → states (NEW, IN_PROGRESS, COMPLETED, CONFLICT) → in-memory impl with `ConcurrentHashMap.putIfAbsent` → DB impl with unique key → expiry → tests incl. N concurrent duplicates.
- **Trade-offs:** store response vs recompute; lock vs optimistic; TTL scanning vs lazy expiry.
- **Follow-ups:** Crash while IN_PROGRESS—how to recover? Multi-instance? Large responses?
- **Project links:** [API contracts: idempotency](../lld/api-contracts.md#idempotency) · [DB schema](../lld/database-design.md#schema-phase-2).

### LLD-03 Token-bucket rate limiter
- **Difficulty:** Medium
- **Concepts tested:** algorithm, time abstraction (`Clock`), concurrency (CAS/locks), per-key state, memory eviction, interface design for multiple algorithms (Strategy).
- **Hints:** Lazy refill on access vs timer thread? What's the precision/overflow story? How do you test without sleeping?
- **Answer framework:** `RateLimiter.tryAcquire(key)` → bucket state (tokens, lastRefill) → refill = elapsed × rate, capped → atomic update (synchronized per bucket or `AtomicReference` CAS on an immutable state) → map of buckets with eviction → inject `Clock` → tests with fake clock + concurrent hammer.
- **Trade-offs:** token vs leaky vs sliding window; per-key locks vs striped locks; accuracy vs memory.
- **Follow-ups:** Distribute across instances (Redis). Return `Retry-After`. Different limits per plan.
- **Project links:** [HLD-05](hld-questions.md#hld-05-rate-limiter--api-gateway) · [Backpressure](../learning/distributed-systems.md#backpressure-and-overload-protection).

### LLD-04 Thread-safe LRU cache
- **Difficulty:** Medium
- **Concepts tested:** `LinkedHashMap` access order, `removeEldestEntry`, synchronization, doubly-linked list + hash map from scratch, `ReadWriteLock`/striping, stampede handling.
- **Hints:** Is `get` a read or a write in an LRU? What's the complexity target (O(1))?
- **Answer framework:** API → data structure (HashMap + doubly linked list) → eviction policy → locking strategy (single lock first; justify) → stampede (per-key future) → tests incl. concurrent access → alternative: Caffeine.
- **Trade-offs:** coarse lock vs striped/segmented; exact LRU vs approximations (clock); TTL support.
- **Follow-ups:** Add TTL. Add metrics (hit ratio). Make it loading (`computeIfAbsent` semantics).
- **Project links:** [Collections](../learning/java-backend.md#collections) · [Concurrent collections](../learning/java-backend.md#concurrent-collections) · [Caching](../hld/scalability-and-capacity.md#caching).

### LLD-05 Inventory reservation service
- **Difficulty:** Medium–Hard
- **Concepts tested:** atomic conditional update, reservation lifecycle (RESERVED/CONFIRMED/RELEASED), expiry, idempotency by order ID, lock ordering/deadlocks, transaction boundaries.
- **Hints:** What if `reserve` is called twice for the same order? What releases stock when the order is cancelled or times out?
- **Answer framework:** entities (`StockItem`, `Reservation`) → operations (`reserve(orderId, items)`, `confirm`, `release`) → SQL `UPDATE … WHERE available >= qty` → unique `(orderId, sku)` for idempotency → sort SKUs to avoid deadlock → expiry job → tests (concurrency invariant: `available + reserved + sold = total`).
- **Trade-offs:** reserve-then-confirm vs decrement-on-pay; pessimistic vs atomic update; per-item vs all-or-nothing partial reservations.
- **Follow-ups:** Multi-warehouse allocation. Hot SKU. Compensation ordering.
- **Project links:** [DB locking](../lld/database-design.md#transaction-behaviour-and-locking) · [FS-01](../debugging/failure-scenarios.md#fs-01-race-condition--lost-update) · [FS-06](../debugging/failure-scenarios.md#fs-06-deadlock) · [Saga](../learning/distributed-systems.md#saga-and-compensation).

### LLD-06 Pluggable pricing and discount engine
- **Difficulty:** Medium
- **Concepts tested:** Strategy, Chain of Responsibility/pipeline, Open/Closed, money handling (minor units, rounding), immutability, ordering of discounts.
- **Hints:** Percent vs fixed vs buy-X-get-Y; stacking rules; rounding policy; how do you add a rule without editing existing classes?
- **Answer framework:** `Money` value object → `PricingRule` interface → ordered rule pipeline → explain result with line-level breakdown → config-driven registration → tests per rule + property tests (total ≥ 0).
- **Trade-offs:** rule engines (Drools) vs code; explicit ordering vs priority numbers; per-line vs per-cart discounts.
- **Follow-ups:** Coupons with usage limits (concurrency!). Audit why a price was computed.
- **Project links:** [SOLID table](../lld/class-design.md#solid-applied-to-this-design) · [Patterns](../lld/class-design.md#design-patterns-when-justified-when-not).

### LLD-07 Retry policy and circuit breaker library
- **Difficulty:** Hard
- **Concepts tested:** Decorator/higher-order functions, exponential backoff with jitter, retry budgets, circuit-breaker state machine, thread safety, time abstraction.
- **Hints:** What's retryable? How do you avoid sleeping in tests? How do you count failures in a sliding window safely?
- **Answer framework:** `Supplier<T>` decorators → `RetryPolicy` (max attempts, backoff, retry-on predicate, deadline) → `CircuitBreaker` (CLOSED/OPEN/HALF_OPEN, sliding window, thresholds) → composition order (bulkhead → breaker → retry → timeout, and why) → tests with fake clock/sleeper.
- **Trade-offs:** count-based vs time-based windows; per-instance state vs shared; fail-fast vs fallbacks.
- **Follow-ups:** Metrics/events. Async variant with `CompletableFuture`. Half-open concurrency limit.
- **Project links:** [Retries](../learning/distributed-systems.md#retries-backoff-jitter-and-timeouts) · [Breakers](../learning/distributed-systems.md#circuit-breakers-and-bulkheads) · [FS-05](../debugging/failure-scenarios.md#fs-05-retry-storm) · [FS-08](../debugging/failure-scenarios.md#fs-08-downstream-service-outage).

### LLD-08 Bounded producer/consumer pipeline
- **Difficulty:** Medium
- **Concepts tested:** `BlockingQueue`, thread lifecycle, graceful shutdown (poison pill / interrupt), backpressure, error handling in workers, executor configuration.
- **Hints:** What happens when consumers fall behind? How do you stop cleanly without losing in-flight work?
- **Answer framework:** API → bounded queue → worker pool → shutdown protocol → exception policy (retry/DLQ) → metrics (queue depth) → tests (slow consumer, shutdown during load).
- **Trade-offs:** block vs drop vs caller-runs on full; batch vs single; ordering requirements (single consumer per key).
- **Follow-ups:** Priorities. Exactly-once in-process? Persisting the queue (→ Kafka/outbox).
- **Project links:** [Threads & executors](../learning/java-backend.md#threads-and-executors) · [Synchronization](../learning/java-backend.md#synchronization-and-memory-visibility) · [Backpressure](../learning/distributed-systems.md#backpressure-and-overload-protection).
