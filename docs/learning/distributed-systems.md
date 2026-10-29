# Distributed Systems Handbook

**Last updated:** 2026-10-29

Related: [HLD architecture](../hld/architecture.md) · [ADRs](../hld/architecture-decisions.md) · [Sequence diagrams](../lld/sequence-diagrams.md) · [Database design](../lld/database-design.md) · [Database fundamentals](database-fundamentals.md) · [Observability](observability-and-reliability.md) · [Failure scenarios](../debugging/failure-scenarios.md) · [HLD questions](../interviews/hld-questions.md)

> **Nothing in this handbook is implemented in the repo.** There is no Kafka dependency, no outbox, no saga, no retry/circuit-breaker library in [`pom.xml`](../../pom.xml). Everything is **[PLANNED]** (Phases 4–5) or **[ILLUSTRATIVE]**. Each concept lists *crash scenarios and transaction boundaries*, not just definitions. Kafka behaviour is described from general knowledge; confirm against the Kafka/Spring Kafka version you adopt.

Each section: *Plain language → Why it matters → Crash scenarios & transaction boundaries → Example → Common mistakes → Debugging exercise → Interview questions.*

## Contents
1. [Synchronous vs asynchronous communication](#synchronous-vs-asynchronous-communication)
2. [Kafka: producers, consumers, partitions, consumer groups](#kafka-producers-consumers-partitions-consumer-groups)
3. [Ordering and delivery semantics](#ordering-and-delivery-semantics)
4. [Idempotency and duplicate handling](#idempotency-and-duplicate-handling)
5. [Transactional outbox and the dual-write problem](#transactional-outbox-and-the-dual-write-problem)
6. [Eventual consistency and reconciliation](#eventual-consistency-and-reconciliation)
7. [Saga and compensation](#saga-and-compensation)
8. [Retries, backoff, jitter, and timeouts](#retries-backoff-jitter-and-timeouts)
9. [Circuit breakers and bulkheads](#circuit-breakers-and-bulkheads)
10. [Backpressure and overload protection](#backpressure-and-overload-protection)
11. [Database concurrency and consistency guarantees](#database-concurrency-and-consistency-guarantees)
12. [Failure recovery and observability](#failure-recovery-and-observability)
13. [Crash scenario table](#crash-scenario-table)

---

## Synchronous vs asynchronous communication

**Plain language.** *Synchronous* (HTTP/gRPC): caller waits for the answer. *Asynchronous* (messaging): caller hands a message to a broker and moves on; processing happens later, possibly elsewhere.

**Why it matters in production.** Sync calls couple **availability** (if downstream is down, you're down; availability multiplies: 99.9% × 99.9% ≈ 99.8%) and **latency** (sum along the chain). Async decouples them at the cost of eventual consistency, more complex failure handling, and harder debugging.

**Crash scenarios & transaction boundaries.**
- Sync: client sends request, server commits, server dies before responding → client can't tell if it succeeded → **must retry safely** (idempotency key). No transaction spans the network.
- Async: producer commits locally, dies before publishing → event lost (dual-write; see [outbox](#transactional-outbox-and-the-dual-write-problem)). Consumer processes, dies before ack → redelivery → duplicate (see [idempotency](#idempotency-and-duplicate-handling)).

**Example.** Order creation → inventory reservation: sync REST (simple; order fails if inventory is down; temporal coupling) vs events (`OrderCreated` → `InventoryReserved`; order shows `CREATED` until the result arrives). Rule of thumb: sync when the user needs the answer to proceed *and* the dependency is highly available; async for workflows, fan-out, and anything that can complete later.

**Common mistakes.** Chains of sync calls (distributed monolith); async without idempotency; no timeouts on sync calls; pretending an async result is immediate in the UX; using events as remote procedure calls (request/reply with correlation, no timeouts).

**Debugging exercise.** Simulate a downstream that sleeps 10 s with no client timeout; observe thread exhaustion in the caller via thread dump; add timeout + bulkhead.

**Interview questions.**
1. When would you choose async messaging over a REST call between services? What new problems do you take on?
2. How does a synchronous call chain affect availability and latency? Quantify.

---

## Kafka: producers, consumers, partitions, consumer groups

**Plain language.** A **topic** is an append-only, replicated log split into **partitions**. A **producer** appends records (optional key; same key → same partition). A **consumer** reads sequentially, tracking an **offset** per partition. A **consumer group** shares the partitions among its members: each partition is read by **at most one consumer in a group**, so max parallelism = partition count; separate groups each get all messages. Adding/removing members triggers a **rebalance** (partition reassignment, brief pause).

**Why it matters in production.** Key choice determines ordering and load distribution; partition count caps parallelism; offset commit timing determines delivery semantics; rebalances and slow consumers (`max.poll.interval.ms` exceeded) cause duplicate processing and lag.

**Crash scenarios & transaction boundaries.**
- Broker leader dies: with RF=3, `acks=all`, `min.insync.replicas=2` → acknowledged records survive; producer retries transparently (idempotent producer prevents duplicates from its own retries within a session).
- Consumer dies after processing, before offset commit → another consumer in the group resumes from the last committed offset → **reprocessing**.
- Consumer commits offset *before* processing and dies → record **skipped** (at-most-once).
- Slow handler exceeds `max.poll.interval.ms` → consumer kicked from the group → rebalance → record reprocessed by another member while the slow one may still finish → **concurrent duplicate** processing.

**Example.** **[ILLUSTRATIVE]** — requires adding `spring-kafka` (not in pom).
```java
// Producer: key = orderId so events for one order are ordered
kafkaTemplate.send("order-events", order.id().toString(), payload);

// Consumer: group id gives horizontal scaling; offsets committed after the handler returns
@KafkaListener(topics = "order-events", groupId = "inventory")
void on(ConsumerRecord<String, String> rec) { handler.handle(rec); }
```
```properties
# [ILLUSTRATIVE] producer safety
spring.kafka.producer.acks=all
spring.kafka.producer.properties.enable.idempotence=true
```
Local inspection: `kafka-consumer-groups.sh --describe --group inventory` shows per-partition offsets and **lag**.

**Common mistakes.** Null/low-cardinality keys (hot partitions); too few partitions (can't scale consumers) or too many (overhead); long processing inside the poll loop; auto-commit offsets before processing finishes; assuming ordering across partitions; changing partition count and breaking key-based ordering; unbounded retries blocking a partition (poison message).

**Debugging exercise.** Run two consumers in one group on a 3-partition topic; kill one; observe rebalance and which messages are redelivered. Introduce a 60 s handler to trigger `max.poll.interval.ms` rebalances. (Needs a local broker; see [no-Docker options](../../LEARNING_ROADMAP.md#environment-constraint-no-docker).)

**Interview questions.**
1. Explain topics, partitions, offsets, and consumer groups. How does a consumer group scale and what limits it?
2. How do you choose a partition key for order events? What happens with a hot key?

---

## Ordering and delivery semantics

**Plain language.** Kafka guarantees order **per partition only**. Delivery semantics: **at-most-once** (may lose), **at-least-once** (may duplicate — the practical default), **exactly-once** (Kafka supports it for Kafka-to-Kafka read-process-write using transactions; for external side effects you simulate it with *idempotent processing* — "effectively-once").

**Why it matters in production.** Reordering and duplicates are normal in distributed systems: retries, rebalances, multiple producers. Business logic must tolerate them or prevent them explicitly.

**Crash scenarios & transaction boundaries.**

| When offset is committed | Crash window | Result |
|---|---|---|
| Before processing | after commit, before effect | Message lost (at-most-once) |
| After processing | after effect, before commit | Message reprocessed (at-least-once) |
| Same transaction as the effect (offset stored in the DB) | none | Effectively-once, but couples consumer to DB offset management |

Out-of-order example: `OrderCancelled` processed before `OrderConfirmed` is applied (different topics/partitions). Defence: **aggregate version** on events and state-machine checks (ignore stale/illegal transitions), per-key partitioning within one topic.

**Example.** **[ILLUSTRATIVE]**
```java
void apply(OrderEvent e, Order o) {
    if (e.aggregateVersion() <= o.lastAppliedVersion()) return;     // stale/duplicate: ignore
    if (!o.status().canTransitionTo(e.targetStatus())) { alertOutOfOrder(e); return; }
    o.apply(e);
}
```

**Common mistakes.** "Exactly-once" claimed because Kafka offers it somewhere; assuming total order across topics; retries with `max.in.flight.requests` > 1 and idempotence disabled (can reorder); relying on timestamps for ordering (clock skew).

**Debugging exercise.** Publish `Created`, `Cancelled`, `Confirmed` for one order to *different* partitions/topics in shuffled order; show the final state is wrong without version/state checks and right with them.

**Interview questions.**
1. Compare at-most-once, at-least-once, and exactly-once. How do you get "exactly-once effects" with Kafka and a database?
2. How would you guarantee that all events of one order are processed in order, and what does that cost in parallelism?

---

## Idempotency and duplicate handling

**Plain language.** An operation is idempotent if repeating it has the same effect as doing it once. Two tools: make the operation *naturally* idempotent (set status = X, not "add 1") or *deduplicate* using a unique key (`Idempotency-Key`, `eventId`) recorded **atomically with the effect**.

**Why it matters in production.** Clients, gateways, brokers, and retry libraries all re-send. Without idempotency, retries produce double charges and duplicate shipments. Dedupe in process memory is insufficient (restarts, multiple instances).

**Crash scenarios & transaction boundaries.** The dedupe record and the business effect must be in **one local transaction**.

| Design | Crash between steps | Result |
|---|---|---|
| Check "seen?" → do effect → record "seen" (separate steps) | after effect, before record | Duplicate effect on retry ✗ |
| Record "seen" → do effect (separate transactions) | after record, before effect | Effect never happens, retries skipped ✗ |
| **Insert dedupe row + effect in one DB transaction** | any | Either both happened or neither ✓ |
| Effect on an external system (payment provider) | after provider call, before local record | Use the provider's idempotency key; reconcile |

**Example.** **[ILLUSTRATIVE]**
```java
@Transactional
public void handle(OrderCreated e) {
    try {
        processed.insert(CONSUMER, e.eventId());      // PK (consumer, event_id)
    } catch (DuplicateKeyException dup) {
        return;                                      // already handled -> ack and skip
    }
    inventory.reserve(e.orderId(), e.items());       // same transaction
}
```
Schema: [processed_events](../lld/database-design.md#outbox-and-inbox-tables-phases-45). API-level idempotency: [API contracts](../lld/api-contracts.md#idempotency).

**Common mistakes.** Check-then-act without a unique constraint (race: two concurrent duplicates both pass the check); idempotency key not scoped (per client/consumer); storing the key *after* the effect in a different transaction; same key + different payload silently accepted; dedupe table never pruned; keys that expire before max retry horizon.

**Debugging exercise.** [FS-02](../debugging/failure-scenarios.md): deliver the same event three times, concurrently, to a non-idempotent handler; count effects; fix with the unique-constraint inbox; prove with a concurrent test.

**Interview questions.**
1. How do you make `POST /payments` safe to retry? What do you store, where, and for how long?
2. A consumer processed a message and crashed before committing its offset. What happens and how is the system correct?

---

## Transactional outbox and the dual-write problem

**Plain language.** **Dual write:** updating the DB *and* publishing to a broker in the same code path. They are two systems with no shared transaction, so one can succeed and the other fail. **Outbox:** write the event into an `outbox` table in the *same DB transaction* as the state change; a separate **relay** reads unpublished rows and publishes them, marking them done.

**Why it matters in production.** Without it you get silent data divergence: orders saved but never announced (downstream never reserves stock) or announced but rolled back (phantom reservations). These bugs are rare, expensive, and found by customers.

**Crash scenarios & transaction boundaries.**

| Approach | Crash point | Outcome |
|---|---|---|
| Save then publish | after commit, before publish | Order exists, **event lost** ✗ |
| Publish then save | after publish, before commit/rollback | **Phantom event**, no order ✗ |
| Publish inside the DB transaction | publish OK, commit fails | Phantom event ✗ |
| **Outbox** | before commit | Nothing happened ✓ |
| Outbox | after commit, before relay | Relay publishes later ✓ |
| Outbox relay | after publish, before marking published | **Duplicate** event → consumers dedupe ✓ |

The outbox converts "lost or phantom events" into "possible duplicates" — which idempotent consumers handle. Sequence: [diagram 4](../lld/sequence-diagrams.md#4-create-order-with-outbox-and-relay-phase-5).

**Example.** **[ILLUSTRATIVE]**
```java
@Transactional
public Order create(CreateOrderCommand c) {
    Order o = orders.save(Order.create(c));
    outbox.save(new OutboxEvent(UUID.randomUUID(), o.id(), o.version(), "OrderCreated", json(o)));
    return o;                                         // single commit covers both
}

@Scheduled(fixedDelay = 200)                          // polling relay
void relay() {
    List<OutboxEvent> batch = outbox.lockPendingBatch(100);          // FOR UPDATE SKIP LOCKED
    for (var e : batch) { kafka.send("order-events", e.aggregateId().toString(), e.payload()).get(5, SECONDS); e.markPublished(); }
}
```
(Note: relay should publish then mark; for ordering, publish per aggregate in sequence; handle failures by leaving rows pending.)

**Common mistakes.** Publishing from `@TransactionalEventListener(AFTER_COMMIT)` and calling it an outbox (it's still a dual write: crash after commit loses the event); relay that deletes before publishing; no ordering by aggregate; unbounded outbox growth; relay with no monitoring (age of oldest pending row); consumers that aren't idempotent.

**Debugging exercise.** Implement the naive save-then-publish; inject a crash (`Runtime.halt` or a thrown exception) between steps; count orders vs events. Then implement the outbox and repeat; measure that no events are lost, and that duplicates appear when crashing inside the relay.

**Interview questions.**
1. What is the dual-write problem? How does the transactional outbox solve it and what does it *not* solve?
2. Polling relay vs CDC (Debezium) for outbox: trade-offs?

---

## Eventual consistency and reconciliation

**Plain language.** Different components hold copies of the truth that converge over time rather than instantly. **Reconciliation** is the process of comparing states, detecting drift/stuck workflows, and repairing them.

**Why it matters in production.** Messages get lost, consumers have bugs, humans edit data. A system without reconciliation can only hope; with it, "eventual" has a bound and an alert.

**Crash scenarios & transaction boundaries.** Any step can fail permanently (poison message, bug, lost event); there's no transaction to roll everything back. The reconciler queries by *state + age* (e.g., `CREATED` > 5 min) and either re-drives (idempotently) or compensates. Sequence: [diagram 8](../lld/sequence-diagrams.md#8-reconciliation-of-a-stuck-order-phase-5).

**Example.** **[ILLUSTRATIVE]**
```sql
SELECT id FROM orders WHERE status = 'CREATED' AND updated_at < now() - interval '5 minutes' LIMIT 500;
```
UX: show intermediate states honestly ("processing") and provide read-your-writes for the creator.

**Common mistakes.** No deadlines on non-terminal states; reconciler that isn't idempotent or races with the live flow (use versions); only fixing data without root-causing; no metric on stuck counts.

**Debugging exercise.** Drop an `InventoryReserved` event deliberately; verify the order is stuck; have the reconciler detect and repair it; add the stuck-order metric and alert.

**Interview questions.**
1. What does "eventually consistent" promise? How do you bound and monitor it?
2. How would you detect and repair orders stuck between payment and fulfilment?

---

## Saga and compensation

**Plain language.** A **saga** is a sequence of local transactions, each publishing an event/command for the next; if a step fails, **compensating transactions** semantically undo prior steps (release stock, void authorization). **Choreography:** services react to each other's events. **Orchestration:** a coordinator tells each participant what to do and tracks state. Compensation is *semantic undo*, not rollback; sagas lack isolation (intermediate states are visible).

**Why it matters in production.** It's how you get multi-service business transactions without 2PC. The complexity is in failures: timeouts, duplicate/out-of-order messages, and compensations that themselves fail.

**Crash scenarios & transaction boundaries.** Each step = one local ACID transaction + outbox event. Examples:
- Inventory reserved, then payment fails → `PaymentFailed` triggers release (compensation must be idempotent and retried until success).
- Release arrives **before** the reserve was applied (reordering) → store a tombstone; a late reserve must be ignored.
- Orchestrator crashes mid-saga → it must persist saga state before each command (so it can resume); commands must be idempotent.
- Compensation impossible (email already sent) → design the step order so non-compensable steps go last ("pivot transaction").

**Example.** **[ILLUSTRATIVE]** orchestrated saga state:
```java
enum SagaState { STARTED, STOCK_RESERVED, PAYMENT_AUTHORIZED, COMPLETED, COMPENSATING, FAILED }
// persisted per order; every transition in one DB tx with the outbox command
```
Choice rationale: [ADR-0006](../hld/architecture-decisions.md#adr-0006--saga-style-choreography-first-orchestration-considered). Flow: [diagram 6](../lld/sequence-diagrams.md#6-saga-with-compensation-phase-5).

**Common mistakes.** Treating compensation as a rollback (not always possible); non-idempotent compensation; no timeout for missing replies; hidden circular event dependencies; ignoring "dirty reads" (other users seeing intermediate states); using 2PC across a message broker.

**Debugging exercise.** Fail payment 50% of the time; verify no stock leaks (invariant: reserved stock = sum over non-terminal/confirmed orders); inject duplicate and reordered messages and re-check the invariant.

**Interview questions.**
1. Choreography vs orchestration: when would you choose each?
2. Design the compensation flow for "payment authorized, then shipment creation fails."

---

## Retries, backoff, jitter, and timeouts

**Plain language.** **Timeouts** bound how long you wait. **Retries** re-attempt transient failures. **Exponential backoff** spaces them (e.g. 100 ms, 200, 400…). **Jitter** randomises delays so clients don't retry in lockstep. A **retry budget** caps total extra load.

**Why it matters in production.** Naive retries convert a brief blip into a **retry storm**: N clients × M attempts × several layers each retrying (multiplicative!) overloads a recovering service and prevents recovery. Missing timeouts leave threads stuck forever.

**Crash scenarios & transaction boundaries.** A timeout means *unknown outcome*: the operation may have succeeded. Therefore only retry **idempotent** operations (or ones guarded by an idempotency key). Set deadlines end-to-end: the caller's timeout must exceed the sum of its callee's retries — or better, propagate a deadline.

**Example.** **[ILLUSTRATIVE]** (Resilience4j or Spring Retry would be added with an ADR)
```java
long delay = Math.min(cap, base * (1L << attempt));
long jittered = ThreadLocalRandom.current().nextLong(0, delay + 1);   // "full jitter"
Thread.sleep(jittered);
```
Layered retries: gateway (3) × service (3) × client (3) = 27 attempts per user request — retry at **one** layer, usually the closest to the failure or the outermost, deliberately.

**Common mistakes.** Retrying non-idempotent calls; retrying on 4xx/validation errors; no jitter; no cap/total timeout; retry inside a transaction holding locks; defaults of "infinite"; retrying when the system is overloaded (should shed instead).

**Debugging exercise.** [FS-05](../debugging/failure-scenarios.md): make a downstream fail for 10 s, with 3 callers each retrying 3× with fixed 100 ms delay; plot request rates at the downstream; add backoff+jitter+budget; compare.

**Interview questions.**
1. What is a retry storm and how do you prevent it? Why jitter?
2. A call times out. Did it succeed? How does your design handle both possibilities?

---

## Circuit breakers and bulkheads

**Plain language.** A **circuit breaker** wraps calls to a dependency: **CLOSED** (normal) → **OPEN** when failure/slow-call rate exceeds a threshold (fail fast, no calls) → **HALF_OPEN** after a wait (a few probe calls) → CLOSED if healthy. A **bulkhead** isolates resources (separate thread pools or semaphores per dependency) so one slow dependency can't consume all capacity.

**Why it matters in production.** They contain failure: a slow payment provider shouldn't exhaust threads and take down order reads. Failing fast preserves capacity and gives the dependency room to recover.

**Crash scenarios & transaction boundaries.** When the breaker is OPEN, define the *business* fallback: reject (503), queue for later (outbox), or degrade (return cached/partial data). Never fallback to "pretend success" on writes. State is per-instance; thresholds need tuning against real traffic.

**Example.** **[ILLUSTRATIVE]**
```java
@CircuitBreaker(name = "payments", fallbackMethod = "paymentsUnavailable")
@Bulkhead(name = "payments")                  // limit concurrent calls
PaymentResult authorize(...) { ... }
```
```properties
resilience4j.circuitbreaker.instances.payments.failureRateThreshold=50
resilience4j.circuitbreaker.instances.payments.slidingWindowSize=20
```

**Common mistakes.** Breaker without timeouts (slow calls never count as failures); one shared bulkhead for everything; thresholds copied from blog posts; fallback hides failures from monitoring; not alerting when OPEN.

**Debugging exercise.** [FS-08](../debugging/failure-scenarios.md): kill the simulated downstream; watch breaker states in metrics; verify the service stays responsive for unrelated endpoints; restore and watch HALF_OPEN → CLOSED.

**Interview questions.**
1. Explain the circuit-breaker states and what to do when it's open.
2. How do bulkheads differ from circuit breakers and from rate limiting?

---

## Backpressure and overload protection

**Plain language.** When arrival rate exceeds capacity, queues grow, latency explodes, memory runs out. **Backpressure** signals producers to slow down; **load shedding** deliberately rejects excess work (429/503) to protect the rest; **rate limiting** caps per-client usage; **bounded queues** make overload visible.

**Why it matters in production.** An overloaded system without shedding collapses (throughput *drops* as load rises). Fast rejection of some requests beats slow failure of all.

**Crash scenarios & transaction boundaries.** Overload during processing can leave work half-done; handlers must be idempotent/resumable. For Kafka consumers, backpressure is natural (pull model: slow consumer → lag grows → alert; pause partitions or scale out). For HTTP, protect with limits (Tomcat max threads + accept queue, bounded executor with rejection policy, rate limiter).

**Example.** **[ILLUSTRATIVE]**
```java
// Bounded executor, reject instead of queueing forever
new ThreadPoolExecutor(8, 8, 0, SECONDS, new ArrayBlockingQueue<>(50), new AbortPolicy());
// -> catch RejectedExecutionException -> return 503 + Retry-After
```
Token bucket: refill R tokens/s up to capacity B; each request takes one; allows bursts ≤ B, long-run rate ≤ R. Little's law: `queue length = arrival rate × wait`.

**Common mistakes.** Unbounded queues; "autoscale will fix it" (scale lag, DB limits); no per-client limits (one noisy tenant); retries from clients amplifying overload; shedding randomly instead of by priority (prefer protecting writes of existing orders over new-session browsing).

**Debugging exercise.** Load the endpoint beyond capacity (Phase 7); chart p99 and error rate with and without a bounded queue; identify the knee.

**Interview questions.**
1. How do you protect a service from overload? Compare rate limiting, load shedding, and backpressure.
2. Design a token-bucket rate limiter. How does it work across multiple instances?

---

## Database concurrency and consistency guarantees

**Plain language.** Within one database: transactions, isolation levels, locks, constraints (see [database fundamentals](database-fundamentals.md)). **Across** components there are no ACID guarantees — only what you build with outbox, idempotency, versioning, and sagas. Consistency models: strong (linearizable), read-your-writes, monotonic reads, eventual.

**Why it matters in production.** Decide per operation what's required: stock decrement needs atomicity; an order-history page can be stale by seconds. Replica reads add staleness.

**Crash scenarios & transaction boundaries.** DB crash mid-transaction → uncommitted work discarded (WAL recovery); client sees an exception or timeout and doesn't know whether commit occurred (if the crash hit during commit) → idempotency again. Failover to an async replica can **lose acknowledged commits** (RPO > 0). Distributed locks (Redis `SETNX`) can fail on pauses/expiry ("fencing tokens" are needed) — prefer DB constraints/conditional updates for correctness.

**Example.** **[ILLUSTRATIVE]** Read-your-writes with replicas: after `POST`, return the created resource in the response; for follow-up `GET`, route to primary for N seconds or pass a version token and wait until the replica reaches it.

**Common mistakes.** Using a distributed lock as the only guard for correctness; assuming replica reads are current; ignoring clock skew in "last write wins"; multi-row invariants enforced only in app code; assuming Kafka transactions cover DB writes.

**Debugging exercise.** Compare three solutions for "don't oversell the last item" under 50 concurrent requests: optimistic version, `FOR UPDATE`, conditional atomic update. Measure correctness, throughput, and failure behaviour (on a real PostgreSQL if available).

**Interview questions.**
1. What consistency guarantees can you give across two microservices with separate databases? How?
2. Why are Redis-based distributed locks risky for correctness? What do you use instead?

---

## Failure recovery and observability

**Plain language.** Recovery = restoring a consistent state after failure: restart, replay, repair. Observability = being able to answer "what happened to order X?" from telemetry: correlation IDs across HTTP and Kafka, metrics for lag/age/stuck counts, traces across hops, structured logs.

**Why it matters in production.** In distributed flows no single stack trace exists; correlation IDs and event IDs are the only thread through the system. Recovery tooling (replay, DLT re-drive, reconcilers) must exist *before* the incident.

**Crash scenarios & transaction boundaries.** For every step list: (1) what is durable, (2) what is lost on crash, (3) how it is retried, (4) how it's detected if retries fail. Poison messages → bounded retries → **dead-letter topic** + alert + runbook to re-drive after a fix. Deploy safety: consumers must tolerate old and new event versions during rolling upgrades.

**Example.** **[ILLUSTRATIVE]** Minimum telemetry per message: `eventId`, `orderId`, `traceId` in headers/logs; metrics: `outbox_oldest_pending_age_seconds`, `consumer_lag`, `dlt_messages_total`, `stuck_orders`. More: [observability handbook](observability-and-reliability.md).

**Common mistakes.** Logging without IDs; DLT with no alerts or owner; replaying into non-idempotent consumers; no tooling to answer "where is order X?"; alerting on symptoms with no runbook.

**Debugging exercise.** Take a single order through a failure (lost event) and answer "where is it stuck and why?" using only logs/metrics; log it as an [incident](../debugging/incident-log.md) once real.

**Interview questions.**
1. A customer says their order is "stuck." How do you find out what happened in an event-driven system?
2. How do you safely re-drive messages from a dead-letter topic?

---

## Crash scenario table

Reference matrix used across docs. "Commit point" = the first durable decision.

| # | Flow | Crash point | What's durable | Result without protection | Protection |
|---|---|---|---|---|---|
| 1 | Create order (sync) | before DB commit | nothing | none; client retries | idempotency key |
| 2 | Create order (sync) | after commit, before response | order | client retries → duplicate order | idempotency key returns original |
| 3 | Save then publish | after commit, before publish | order | lost event | outbox |
| 4 | Outbox relay | after publish, before mark | event in Kafka, row pending | duplicate event | idempotent consumers |
| 5 | Consumer | after effect, before offset commit | effect committed | reprocess → duplicate effect | inbox dedupe in same tx |
| 6 | Consumer | before effect | nothing | redelivery processes normally | — |
| 7 | Consumer | commit offset before effect | offset | lost message | commit after effect |
| 8 | Consumer slow > `max.poll.interval.ms` | mid-processing | partial | rebalance → concurrent duplicate | idempotency + shorter work |
| 9 | Saga step | step N done, N+1 never triggered | N's effect | stuck workflow | reconciler + timeouts |
| 10 | Compensation | arrives before action | nothing/early | late action not undone | tombstone / version check |
| 11 | Retry on timeout | op succeeded but response lost | effect | duplicate effect | idempotency key |
| 12 | DB failover (async replica) | primary dies | replica lag | lost acked commits | sync replication / accept RPO |
