# HLD Interview Questions

**Last updated:** 2026-10-29

Related: [LLD questions](lld-questions.md) · [Backend notes](backend-interview-notes.md) · [Mock interview log (evidence)](mock-interview-log.md) · [HLD docs](../hld/system-overview.md) · [Roadmap Phase 3 & 8](../../LEARNING_ROADMAP.md)

## Contents
- [How to use this file](#how-to-use-this-file)
- [Universal HLD framework (45 minutes)](#universal-hld-framework-45-minutes)
- [Status tracker](#status-tracker)
- [Questions](#questions): [HLD-01](#hld-01-design-an-order-management-platform) · [02](#hld-02-design-an-idempotent-order-or-payment-api) · [03](#hld-03-flash-sale-inventory-reservation) · [04](#hld-04-order-event-pipeline-and-notifications) · [05](#hld-05-rate-limiter--api-gateway) · [06](#hld-06-order-tracking-and-search-read-heavy) · [07](#hld-07-delayed-actions-auto-cancel-unpaid-orders)

## How to use this file

1. Attempt the question **timed, aloud or on paper, without these notes**. Then compare with the framework and the linked project docs.
2. Log the session in the [mock interview log](mock-interview-log.md) (date, duration, self-score, gaps). That log is the *only* evidence for status changes.
3. Statuses: `NOT PRACTISED` → `PRACTISED` → `MASTERED`. Definitions and evidence rules are in the [log](mock-interview-log.md#status-definitions). **Nothing is marked mastered here; none has been practised yet.**
4. "Project links" point to design docs in this repo. Those describe **planned** designs unless labelled otherwise; do not present planned components as built.

## Universal HLD framework (45 minutes)

| Min | Step | Output |
|---|---|---|
| 0–5 | Clarify functional scope, users, out-of-scope | Bulleted requirements ([example](../hld/requirements.md)) |
| 5–8 | Non-functional: scale, latency, availability, consistency, durability | Numbers and priorities |
| 8–12 | Estimates: QPS (avg/peak), storage, bandwidth ([method](../hld/scalability-and-capacity.md#estimation-technique-for-interviews)) | Table |
| 12–17 | API and core data model | Endpoints + entities |
| 17–27 | Baseline architecture, then data flow for the main use case | Diagram |
| 27–40 | Deep dives: bottleneck, consistency, failure modes, scaling | Trade-offs stated |
| 40–45 | Evolution, monitoring, security, what you'd do next | Summary |

Always: name the **commit point**, say what is **eventually vs strongly consistent**, say what happens when each component **fails**, and give **alternatives with trade-offs**.

## Status tracker

| ID | Question | Difficulty | Status |
|---|---|---|---|
| HLD-01 | Order management platform | Hard | NOT PRACTISED |
| HLD-02 | Idempotent order/payment API | Medium | NOT PRACTISED |
| HLD-03 | Flash-sale inventory reservation | Hard | NOT PRACTISED |
| HLD-04 | Order event pipeline & notifications | Medium | NOT PRACTISED |
| HLD-05 | Rate limiter / API gateway | Medium | NOT PRACTISED |
| HLD-06 | Order tracking & search | Medium | NOT PRACTISED |
| HLD-07 | Delayed actions: auto-cancel unpaid orders | Hard | NOT PRACTISED |

## Questions

### HLD-01 Design an order management platform
- **Difficulty:** Hard · **Status:** NOT PRACTISED
- **Concepts tested:** requirements scoping, API design, relational modelling, state machines, event-driven integration, outbox, saga, idempotency, scaling reads/writes, observability.
- **Hints:** Where is the commit point of "order accepted"? What if payment fails after inventory is reserved? What if the same request arrives twice?
- **Answer framework:** (1) scope: create/get/list/cancel, lifecycle; (2) 1M orders/day → ~12 avg / ~120 peak writes/s, 20:1 reads; (3) REST API + `Idempotency-Key`; (4) `orders`/`order_items` in a relational DB; (5) Order Service stateless behind LB; events via Kafka keyed by `orderId`; (6) deep dive: saga for inventory+payment with compensation, outbox for reliable publishing, idempotent consumers; (7) failure table; (8) scaling path: replicas/cache → partitioning → sharding.
- **Trade-offs to discuss:** monolith vs microservices; choreography vs orchestration; sync vs async confirmation; strong vs eventual consistency per operation; Kafka vs queue; SQL vs NoSQL.
- **Follow-ups:** How do you handle a payment provider timeout? How would you support 100× scale? How do you migrate the schema without downtime? How do you find "stuck" orders? How would multi-region change things?
- **Project links:** [System overview](../hld/system-overview.md) · [Architecture](../hld/architecture.md) · [Capacity](../hld/scalability-and-capacity.md) · [ADRs](../hld/architecture-decisions.md) · [Sequence diagrams](../lld/sequence-diagrams.md). *Implementation status: skeleton only.*

### HLD-02 Design an idempotent order or payment API
- **Difficulty:** Medium · **Status:** NOT PRACTISED
- **Concepts tested:** idempotency keys, unique constraints, request hashing, retries, timeouts with unknown outcome, HTTP semantics.
- **Hints:** What does the client do on timeout? Where must the key be stored relative to the business write? What if two identical requests arrive simultaneously?
- **Answer framework:** define the problem (unknown outcome → retries) → contract (`Idempotency-Key` header, scoped per client, TTL) → storage (unique `(client,key)` in the same transaction as the effect) → concurrent duplicate handling (block on unique index, then return stored result) → payload mismatch handling → external provider call (pass provider-level key; reconcile) → cleanup/TTL.
- **Trade-offs:** DB table vs Redis (durability vs speed); store full response vs recompute; key TTL length; return original status code vs 200.
- **Follow-ups:** What if the first request is still in flight? What if the effect is on an external system? How do you store large responses? What if the key store is down?
- **Project links:** [API contracts: idempotency](../lld/api-contracts.md#idempotency) · [Database design](../lld/database-design.md#schema-phase-2) · [Handbook](../learning/distributed-systems.md#idempotency-and-duplicate-handling).

### HLD-03 Flash-sale inventory reservation
- **Difficulty:** Hard · **Status:** NOT PRACTISED
- **Concepts tested:** hot-row contention, atomic updates, queues/admission control, caching limits, overselling prevention, backpressure.
- **Hints:** 100k users want 1,000 units. Which resource is the bottleneck? Can you reject early? Must every request touch the DB?
- **Answer framework:** estimate burst (e.g. 100k req/s for 1 s) → admission control/rate limit at the edge → pre-validated stock counter in a fast store as a *filter* but DB/authoritative counter as the *truth* → atomic conditional decrement (`WHERE available >= qty`) → reservation with TTL (released if unpaid) → queue/waiting-room for fairness → oversell invariant test.
- **Trade-offs:** optimistic vs pessimistic vs atomic update; Redis counter (fast, risk of drift on failure) vs DB; sharding stock across buckets (reduces contention, complicates "last unit"); fairness vs throughput.
- **Follow-ups:** What if Redis fails mid-sale? How to handle abandoned reservations? How to avoid bots? How to reconcile counters?
- **Project links:** [Concurrency control](../lld/order-service.md#concurrency-control) · [DB locking](../lld/database-design.md#transaction-behaviour-and-locking) · [FS-01](../debugging/failure-scenarios.md#fs-01-race-condition--lost-update) · [Backpressure](../learning/distributed-systems.md#backpressure-and-overload-protection).

### HLD-04 Order event pipeline and notifications
- **Difficulty:** Medium · **Status:** NOT PRACTISED
- **Concepts tested:** Kafka topics/partitions/groups, delivery semantics, fan-out, DLQ, schema evolution, ordering.
- **Hints:** What is the partition key? What happens when a consumer is slow or fails on one message? How do you add a new consumer later?
- **Answer framework:** events + envelope → topics per domain → key = `orderId` → consumer groups per downstream (notifications, analytics, inventory) → at-least-once + idempotent handlers → retries with backoff → DLT with alerting → schema compatibility rules → monitoring lag.
- **Trade-offs:** one topic vs many; partition count; ordering vs parallelism; Kafka vs managed queues; push vs pull; replay ability vs complexity.
- **Follow-ups:** How to replay for a new consumer? How do you deal with a poison message? How to handle a consumer that must not send duplicate emails?
- **Project links:** [Async flow](../hld/architecture.md#asynchronous-event-flow-order-confirmation-saga) · [Kafka handbook](../learning/distributed-systems.md#kafka-producers-consumers-partitions-consumer-groups) · [FS-09](../debugging/failure-scenarios.md#fs-09-poison-message-and-rebalance-storm).

### HLD-05 Rate limiter / API gateway
- **Difficulty:** Medium · **Status:** NOT PRACTISED
- **Concepts tested:** token bucket / leaky bucket / sliding window, distributed counters, gateway responsibilities, failure modes, fairness.
- **Hints:** Per-IP, per-user, per-API key? What happens if the limiter store is down — fail open or closed? How accurate must it be?
- **Answer framework:** requirements → algorithm choice (token bucket for bursts) → where it runs (gateway/sidecar/in-app) → state (local vs Redis with atomic Lua/INCR + TTL) → response (`429` + `Retry-After`) → config management → observability → failure policy.
- **Trade-offs:** accuracy vs latency (local approximations); fixed window boundary bursts; sliding window log memory cost; centralised vs per-instance limits.
- **Follow-ups:** How to rate-limit across regions? How to prevent one tenant starving others? How to test?
- **Project links:** [Edge concepts](../hld/architecture.md#edge-api-gateway-and-load-balancing) · [Backpressure](../learning/distributed-systems.md#backpressure-and-overload-protection) · [LLD-03](lld-questions.md#lld-03-token-bucket-rate-limiter).

### HLD-06 Order tracking and search (read-heavy)
- **Difficulty:** Medium · **Status:** NOT PRACTISED
- **Concepts tested:** read scaling, caching, CQRS/read models, replication lag, search indexing, pagination.
- **Hints:** 20:1 reads. What staleness is acceptable? How do you serve "my orders" fast? What about full-text/filter search?
- **Answer framework:** access patterns → primary store indexes + keyset pagination → cache terminal orders → read replicas with read-your-writes handling → denormalised read model/search index fed by events → invalidation/rebuild strategy.
- **Trade-offs:** cache vs replica vs read model; consistency lag; complexity of dual stores; search engine vs DB indexes.
- **Follow-ups:** How to rebuild the read model? How to handle cache stampede? How to paginate consistently while new orders arrive?
- **Project links:** [Caching](../hld/scalability-and-capacity.md#caching) · [Scaling the DB](../hld/scalability-and-capacity.md#scaling-the-database) · [Indexes](../lld/database-design.md#indexes) · [Pagination](../lld/api-contracts.md#pagination).

### HLD-07 Delayed actions: auto-cancel unpaid orders
- **Difficulty:** Hard · **Status:** NOT PRACTISED
- **Concepts tested:** timers at scale, scheduling, idempotency, race with payment, reconciliation, leader election/locking.
- **Hints:** Millions of pending orders with 30-minute deadlines. Polling vs delay queue vs timers in memory? What if the payment lands as the cancel fires?
- **Answer framework:** requirement and scale → options: DB poll by `expires_at` (indexed, `SKIP LOCKED`), delayed message (broker-specific), timer wheel (in-memory, lost on crash) → choose DB-poll for durability and simplicity → state-machine guard so cancel after payment is rejected (optimistic version) → idempotent cancel + compensation (release stock, void auth) → metrics on overdue backlog.
- **Trade-offs:** precision vs cost; poll interval vs load; Kafka lacks native delay; per-order timers don't survive restarts; multiple schedulers need claim semantics.
- **Follow-ups:** What if the scheduler is down for an hour? How to avoid thundering herd at the top of each minute? How to scale to 100M pending?
- **Project links:** [State machine](../lld/order-service.md#state-machine) · [Reconciliation](../learning/distributed-systems.md#eventual-consistency-and-reconciliation) · [Partial index for stuck orders](../lld/database-design.md#indexes).
