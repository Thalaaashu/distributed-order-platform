# Backend Interview Notes

**Last updated:** 2026-10-29

Related: [HLD questions](hld-questions.md) · [LLD questions](lld-questions.md) · [Mock interview log (evidence)](mock-interview-log.md) · [Java handbook](../learning/java-backend.md) · [Spring handbook](../learning/spring-boot.md) · [DB handbook](../learning/database-fundamentals.md) · [Distributed handbook](../learning/distributed-systems.md)

Covers Java concurrency, databases, distributed systems, Spring, debugging, and behavioural/project storytelling. Status values and evidence rules: [mock log](mock-interview-log.md#status-definitions). **All questions below are `NOT PRACTISED`.** Each question has the same fields as the HLD/LLD files; "Answer framework" is a skeleton to *speak from*, not a script.

## Contents
- [Status tracker](#status-tracker)
- [Java concurrency](#java-concurrency): [BE-01](#be-01-what-does-volatile-guarantee) · [BE-02](#be-02-size-a-thread-pool) · [BE-03](#be-03-diagnose-a-deadlock-vs-pool-starvation)
- [Databases](#databases): [BE-04](#be-04-design-an-index-for-a-query) · [BE-05](#be-05-prevent-lost-updates-and-write-skew) · [BE-06](#be-06-an-endpoint-got-slow-as-data-grew)
- [Distributed systems](#distributed-systems): [BE-07](#be-07-exactly-once-processing) · [BE-08](#be-08-dual-write-and-the-outbox) · [BE-09](#be-09-saga-failure-handling)
- [Spring](#spring): [BE-10](#be-10-transactional-not-working) · [BE-11](#be-11-n1-queries-in-production)
- [Debugging](#debugging): [BE-12](#be-12-service-is-up-but-everything-is-slow) · [BE-13](#be-13-suspected-memory-leak)
- [Behavioural and project story](#behavioural-and-project-story): [BE-14](#be-14-describe-a-production-incident-you-handled) · [BE-15](#be-15-walk-me-through-this-project-honestly)

## Status tracker

| ID | Topic | Difficulty | Status |
|---|---|---|---|
| BE-01 | `volatile` / happens-before | Medium | NOT PRACTISED |
| BE-02 | Thread-pool sizing | Medium | NOT PRACTISED |
| BE-03 | Deadlock vs starvation | Medium–Hard | NOT PRACTISED |
| BE-04 | Index design | Medium | NOT PRACTISED |
| BE-05 | Lost update / write skew | Hard | NOT PRACTISED |
| BE-06 | Query slow as data grows | Medium | NOT PRACTISED |
| BE-07 | Exactly-once processing | Hard | NOT PRACTISED |
| BE-08 | Dual write & outbox | Medium–Hard | NOT PRACTISED |
| BE-09 | Saga failure handling | Hard | NOT PRACTISED |
| BE-10 | `@Transactional` not working | Medium | NOT PRACTISED |
| BE-11 | N+1 in production | Medium | NOT PRACTISED |
| BE-12 | Service up but slow | Medium–Hard | NOT PRACTISED |
| BE-13 | Memory leak | Medium–Hard | NOT PRACTISED |
| BE-14 | Production incident story | Medium | NOT PRACTISED |
| BE-15 | This project, honestly | Medium | NOT PRACTISED |

---

## Java concurrency

### BE-01 What does `volatile` guarantee?
- **Difficulty:** Medium · **Concepts:** Java Memory Model, happens-before, visibility vs atomicity, double-checked locking.
- **Hints:** Is `volatile int x; x++` safe? What does a volatile write publish?
- **Framework:** visibility problem (cache/reordering) → what volatile adds (visibility + ordering for that variable and prior writes) → what it doesn't (compound atomicity) → alternatives (`Atomic*`, `synchronized`, `LongAdder`) → example (stop flag; DCL singleton).
- **Trade-offs:** volatile vs lock vs atomic; contention cost of atomics vs `LongAdder`.
- **Follow-ups:** Explain happens-before edges (monitor unlock→lock, thread start/join, volatile). Is `long` assignment atomic?
- **Project links:** [Synchronization](../learning/java-backend.md#synchronization-and-memory-visibility).

### BE-02 Size a thread pool
- **Difficulty:** Medium · **Concepts:** CPU- vs I/O-bound, Little's law, queues, rejection policy, virtual threads.
- **Hints:** What is the downstream limit (DB pool)? What happens when the queue is full?
- **Framework:** classify workload → starting heuristics (CPU-bound ≈ cores; I/O-bound ≈ cores × (1 + wait/compute)) → cap by downstream limits → bounded queue + rejection policy → measure → separate pools per dependency (bulkhead) → virtual threads for blocking I/O but still limit concurrency to protect dependencies.
- **Trade-offs:** large pool (latency under contention, memory) vs small (underutilisation); unbounded queue hides overload.
- **Follow-ups:** How does Tomcat's pool interact with yours? What metrics tell you it's wrong?
- **Project links:** [Threads & executors](../learning/java-backend.md#threads-and-executors) · [Little's law](../hld/scalability-and-capacity.md#littles-law-and-connection-pools).

### BE-03 Diagnose a deadlock vs pool starvation
- **Difficulty:** Medium–Hard · **Concepts:** thread dumps, lock ownership, thread states, nested executor submissions.
- **Hints:** What do `BLOCKED` vs `WAITING` stacks show? Does the JVM report deadlocks automatically?
- **Framework:** take 3 dumps → look for "Found one Java-level deadlock" → else group threads by stack; many parked waiting on the same pool/future ⇒ starvation (task waits on subtask queued behind it) → fix: lock ordering/timeouts (`tryLock`) vs separate pools/no blocking on same pool.
- **Trade-offs:** `tryLock` with timeout vs ordering; async composition vs blocking.
- **Follow-ups:** Livelock? How to prevent in design?
- **Project links:** [Thread dumps](../learning/java-backend.md#thread-dumps) · [FS-04](../debugging/failure-scenarios.md#fs-04-connection-pool-exhaustion) · [FS-06](../debugging/failure-scenarios.md#fs-06-deadlock).

## Databases

### BE-04 Design an index for a query
- **Difficulty:** Medium · **Concepts:** B-tree, composite order, selectivity, covering/partial indexes, write cost.
- **Hints:** equality columns first, then range/sort; does the planner use it?
- **Framework:** query shape → candidate index → verify with `EXPLAIN` → measure write impact → monitor usage → drop unused.
- **Trade-offs:** read speed vs write/storage; one wide index vs several narrow.
- **Follow-ups:** Why not index every column? `LIKE '%x'`? Index on a low-cardinality column?
- **Project links:** [Indexes](../lld/database-design.md#indexes) · [SQL indexes](../learning/database-fundamentals.md#sql-indexes).

### BE-05 Prevent lost updates and write skew
- **Difficulty:** Hard · **Concepts:** isolation levels, snapshot isolation, optimistic/pessimistic locking, serializable retries, constraints.
- **Hints:** Two doctors both go off-call; two orders both take the "last" unit.
- **Framework:** define anomaly with timeline → which isolation level prevents it in *your* engine → options: atomic conditional update, `FOR UPDATE`, version check, serializable + retry, unique/exclusion constraints → pick based on contention.
- **Trade-offs:** throughput vs simplicity; retries; hot-row contention.
- **Follow-ups:** Does `@Transactional` alone fix it? What about across two services?
- **Project links:** [Isolation](../learning/database-fundamentals.md#isolation-levels-and-anomalies) · [Locks](../learning/database-fundamentals.md#locks-optimistic-and-pessimistic) · [FS-01](../debugging/failure-scenarios.md#fs-01-race-condition--lost-update).

### BE-06 An endpoint got slow as data grew
- **Difficulty:** Medium · **Concepts:** query plans, missing indexes, N+1, pagination, statistics.
- **Hints:** what changed — data volume, plan, or traffic?
- **Framework:** find the query (APM/slow log) → `EXPLAIN ANALYZE` → seq scan? estimates off? → fix (index, rewrite, keyset pagination) → verify with same data volume → prevent (query-count tests, alerts).
- **Trade-offs:** index vs denormalise vs cache vs archive.
- **Follow-ups:** Plan flips after growth? Table bloat/vacuum?
- **Project links:** [Query plans](../learning/database-fundamentals.md#query-plans) · [FS-03](../debugging/failure-scenarios.md#fs-03-slow-sql--n1).

## Distributed systems

### BE-07 Exactly-once processing
- **Difficulty:** Hard · **Concepts:** delivery semantics, idempotency, offsets, Kafka transactions, inbox.
- **Hints:** Where can a crash occur between "effect" and "ack"?
- **Framework:** say "exactly-once *delivery* is impossible in general; exactly-once *effects* achievable" → enumerate crash points → dedupe key in same transaction as effect → Kafka EOS limits (Kafka→Kafka only) → naturally idempotent ops.
- **Trade-offs:** dedupe storage growth; latency; complexity.
- **Follow-ups:** Dedupe window? Reordering?
- **Project links:** [Delivery semantics](../learning/distributed-systems.md#ordering-and-delivery-semantics) · [Idempotency](../learning/distributed-systems.md#idempotency-and-duplicate-handling) · [Crash table](../learning/distributed-systems.md#crash-scenario-table).

### BE-08 Dual write and the outbox
- **Difficulty:** Medium–Hard · **Concepts:** dual write, transactional outbox, CDC, relay failure modes.
- **Hints:** What if you publish first? Commit first?
- **Framework:** enumerate 3 naive orderings & failures → outbox in same tx → relay semantics (at-least-once) → consumers idempotent → polling vs CDC → monitoring oldest pending age.
- **Trade-offs:** polling latency/load vs CDC infra; ordering per aggregate.
- **Follow-ups:** `@TransactionalEventListener(AFTER_COMMIT)` — is that sufficient? (No: crash after commit loses event.)
- **Project links:** [Outbox](../learning/distributed-systems.md#transactional-outbox-and-the-dual-write-problem) · [ADR-0005](../hld/architecture-decisions.md#adr-0005--transactional-outbox-for-event-publication) · [Sequence 4](../lld/sequence-diagrams.md#4-create-order-with-outbox-and-relay-phase-5).

### BE-09 Saga failure handling
- **Difficulty:** Hard · **Concepts:** compensation, idempotency, ordering, timeouts, orchestration vs choreography.
- **Hints:** What if the compensation fails? What if it arrives before the action?
- **Framework:** list steps and their compensations → pivot step → persist saga state → idempotent steps → retry compensations until success → timeouts + reconciler → visibility/monitoring.
- **Trade-offs:** choreography simplicity vs orchestration clarity; isolation anomalies.
- **Follow-ups:** How to avoid exposing intermediate state? How to test?
- **Project links:** [Saga](../learning/distributed-systems.md#saga-and-compensation) · [ADR-0006](../hld/architecture-decisions.md#adr-0006--saga-style-choreography-first-orchestration-considered) · [Sequence 6](../lld/sequence-diagrams.md#6-saga-with-compensation-phase-5).

## Spring

### BE-10 `@Transactional` not working
- **Difficulty:** Medium · **Concepts:** proxies, self-invocation, rollback rules, propagation, thread binding.
- **Hints:** Who calls the method? Which exception type? Which thread?
- **Framework:** proxy mechanics → common causes (self-invocation, checked exception, non-public/final, swallowed exception, wrong bean, async) → how to prove (transaction TRACE logs) → fix.
- **Trade-offs:** `REQUIRES_NEW` vs restructure; programmatic `TransactionTemplate`.
- **Follow-ups:** What does `readOnly=true` do? How do you test transaction behaviour?
- **Project links:** [Transactional pitfalls](../learning/spring-boot.md#transactional-proxies-and-pitfalls) · [Transaction boundaries](../lld/order-service.md#transaction-boundaries).

### BE-11 N+1 queries in production
- **Difficulty:** Medium · **Concepts:** lazy loading, fetch joins, batch fetching, projections, query-count tests.
- **Hints:** How would you notice? How do you fix without eager-fetching everything?
- **Framework:** symptoms → detection (SQL log, statistics, APM) → fixes (fetch join/entity graph/batch size/DTO projection) → pitfalls (paging + collection fetch) → regression test.
- **Trade-offs:** over-fetching vs round-trips; cartesian products.
- **Follow-ups:** OSIV on/off consequences?
- **Project links:** [N+1](../learning/database-fundamentals.md#the-n1-query-problem) · [FS-03](../debugging/failure-scenarios.md#fs-03-slow-sql--n1).

## Debugging

### BE-12 Service is up but everything is slow
- **Difficulty:** Medium–Hard · **Concepts:** golden signals, saturation, thread dump, pool metrics, GC, dependency latency.
- **Hints:** CPU low or high? Which endpoints? What changed?
- **Framework:** scope & timeline → golden signals → CPU high? (profile/GC) vs low? (waiting: thread dump → pools/locks/downstream) → mitigate (rollback/shed/scale) → root cause → prevention.
- **Trade-offs:** mitigate vs investigate; restart destroys evidence (capture first).
- **Follow-ups:** What if only one instance is slow?
- **Project links:** [Playbook](../debugging/debugging-playbook.md#first-five-minutes-triage-checklist) · [Symptom table](../debugging/debugging-playbook.md#symptom--likely-area--first-tool).

### BE-13 Suspected memory leak
- **Difficulty:** Medium–Hard · **Concepts:** heap vs RSS, GC logs, histograms, heap dumps, dominators, containers.
- **Hints:** Does heap-after-GC trend upward? Heap or native?
- **Framework:** confirm trend → histogram diff over time → heap dump → dominator tree → root path → fix → soak-test verify; check native/metaspace/threads if heap is flat.
- **Trade-offs:** dump pauses the app; sampling vs full dumps.
- **Follow-ups:** Pod OOMKilled with a flat heap?
- **Project links:** [JVM memory](../learning/java-backend.md#jvm-memory) · [FS-07](../debugging/failure-scenarios.md#fs-07-memory-leak).

## Behavioural and project story

### BE-14 Describe a production incident you handled
- **Difficulty:** Medium · **Concepts:** structured storytelling, ownership, mitigation vs root cause, learning.
- **Hints:** Prefer a *real* incident from your own experience (non-confidential details). Use the [incident template](../debugging/debugging-playbook.md#incident-template) fields as the outline.
- **Framework:** situation → impact → detection → mitigation → investigation → root cause → fix/prevention → what you'd do differently (≈ 3–4 minutes).
- **Trade-offs:** depth vs brevity; blamelessness.
- **Follow-ups:** What would have prevented it? What did you measure? Who else was involved?
- **Project links:** [Incident log](../debugging/incident-log.md) (empty; only use entries that actually exist). **Do not present exercises as real production incidents.**

### BE-15 Walk me through this project, honestly
- **Difficulty:** Medium · **Concepts:** scoping claims, design vs implementation, trade-offs, learning narrative.
- **Hints:** Say precisely what is built, what is designed, and what you learned. Interviewers probe overclaiming.
- **Framework:** goal (learn distributed order lifecycle) → current verified state (from the [snapshot](../README.md#verified-project-snapshot) — update as it changes) → key design decisions and *why* ([ADRs](../hld/architecture-decisions.md)) → a failure you reproduced and fixed (only if logged) → what you'd do next.
- **Trade-offs:** modular monolith first; Kafka later; limits of H2 testing.
- **Follow-ups:** What would break at 100×? What's the weakest part of the design? What did you decide *not* to build?
- **Project links:** [Roadmap](../../LEARNING_ROADMAP.md) · [Architecture](../hld/architecture.md).
