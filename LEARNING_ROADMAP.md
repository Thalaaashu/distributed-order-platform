# Learning Roadmap

**Last updated:** 2026-10-29
**Time budget:** 8–10 hours/week → plan below assumes **9 h/week**, about **36–40 weeks** end to end (~330 h). Phases are sequential; durations are estimates and will drift. Adjust honestly.

Companion docs: [docs/README.md](docs/README.md) · [HLD overview](docs/hld/system-overview.md) · [LLD: order service](docs/lld/order-service.md) · [Failure scenarios](docs/debugging/failure-scenarios.md) · [Interview tracker](docs/interviews/mock-interview-log.md)

## Contents

- [Rules of engagement](#rules-of-engagement)
- [Weekly rhythm (9 h)](#weekly-rhythm-9-h)
- [Phase summary](#phase-summary)
- [Phase 0 – Environment and Spring Boot fundamentals](#phase-0--environment-and-spring-boot-fundamentals)
- [Phase 1 – Working Order REST API](#phase-1--working-order-rest-api)
- [Phase 2 – Persistence, transactions, database correctness](#phase-2--persistence-transactions-and-database-correctness)
- [Phase 3 – HLD and LLD of the order lifecycle](#phase-3--hld-and-lld-of-the-order-lifecycle)
- [Phase 4 – Event-driven processing with Kafka](#phase-4--event-driven-processing-with-kafka)
- [Phase 5 – Distributed consistency and failure recovery](#phase-5--distributed-consistency-and-failure-recovery)
- [Phase 6 – Observability and deliberate debugging](#phase-6--observability-and-deliberate-debugging)
- [Phase 7 – Load testing, performance, reliability](#phase-7--load-testing-performance-and-reliability)
- [Phase 8 – Senior design interviews and project polish](#phase-8--senior-level-design-interviews-and-project-polish)
- [Environment constraint: no Docker](#environment-constraint-no-docker)

## Rules of engagement

- **Status values:** `NOT STARTED`, `IN PROGRESS`, `VERIFIED`. A phase becomes `VERIFIED` only when *every* item under "Evidence required" exists and is linked here.
- **Evidence beats claims.** Evidence is a command output pasted into a doc, a passing test class name, a commit/PR link, an incident-log entry, or a mock-interview log entry. "I understand it" is not evidence.
- **Build first, then explain.** Each phase: read → implement a thin slice → test → break it deliberately → write it down.
- **Labels:** Docs use [IMPLEMENTED]/[PLANNED]/[ILLUSTRATIVE] ([definitions](docs/README.md#truth-labels-read-this-first)). Update them as code lands.
- **Git:** the directory is not currently a git repository. Run `git init` (or clone into a repo) in Phase 0; evidence like "commit X" requires it.

## Weekly rhythm (9 h)

| Block | Hours | Activity |
|---|---|---|
| Build | 4 | Implement the current phase's tasks. |
| Study | 2 | Read the matching handbook sections; do their debugging exercises. |
| Break & debug | 1 | Inject one failure (see [failure scenarios](docs/debugging/failure-scenarios.md)), investigate with the [playbook](docs/debugging/debugging-playbook.md), log it if real. |
| Design / interview | 1.5 | One HLD or LLD question, timed (45 min), then self-review and log it. |
| Docs & review | 0.5 | Update labels, ADRs, roadmap status. |

## Phase summary

| Phase | Focus | Est. weeks | Status |
|---|---|---|---|
| 0 | Environment & Spring Boot fundamentals | 3 | **IN PROGRESS** |
| 1 | Working Order REST API | 4 | NOT STARTED |
| 2 | Persistence, transactions, DB correctness | 5 | NOT STARTED |
| 3 | HLD/LLD of the order lifecycle | 3 | NOT STARTED |
| 4 | Event-driven processing with Kafka | 6 | NOT STARTED |
| 5 | Distributed consistency & failure recovery | 6 | NOT STARTED |
| 6 | Observability & deliberate debugging | 4 | NOT STARTED |
| 7 | Load testing, performance, reliability | 4 | NOT STARTED |
| 8 | Senior design interviews & polish | 4 | NOT STARTED |

Phase 0 is `IN PROGRESS` because the skeleton exists (see [snapshot](docs/README.md#verified-project-snapshot)) but the evidence below has not been collected.

---

## Phase 0 – Environment and Spring Boot fundamentals

**Status:** IN PROGRESS · **Est.:** 3 weeks

**Learning objectives**
- Explain what `@SpringBootApplication` does (component scan, auto-configuration, configuration) and how the app starts.
- Explain IoC/DI and bean lifecycle at interview depth ([Spring handbook](docs/learning/spring-boot.md)).
- Use Maven wrapper, run/debug from VS Code, read startup logs and the auto-configuration report.
- Java refresher: collections, generics, equality, exceptions ([Java handbook](docs/learning/java-backend.md)).

**Implementation tasks**
1. Initialise git; commit the generated skeleton; add this documentation.
2. Run `./mvnw test` with network access; record the result.
3. Start the app; hit `/actuator/health`; record actual output.
4. Add `spring.h2.console.enabled=true` only if you want the H2 console; observe which datasource URL Boot generates in logs.
5. Enable `debug=true` once and read the conditions report; note which auto-configurations matched.
6. Write a throwaway `@RestController` `GET /ping` + a `@WebMvcTest` for it, then delete or keep it deliberately. (Mind Boot 4 test-slice package names.)
7. Decide ADR-0008 (keep/remove DGS plugin).

**Required tests:** `contextLoads` passes; one slice test (`@WebMvcTest`) passes.

**HLD/LLD topics:** layered architecture; what a "service boundary" means (read [system overview](docs/hld/system-overview.md)).

**Debugging exercises:** (a) break a bean (two candidates for one type) and read `NoUniqueBeanDefinitionException`; (b) set an invalid property and read the failure analysis; (c) start the app on an occupied port.

**Acceptance criteria**
- App starts from CLI and VS Code; tests run in CLI.
- You can explain the startup sequence without notes.

**Evidence required to mark VERIFIED**
- Pasted `./mvnw test` output (BUILD SUCCESS) in a dated note under this phase.
- Pasted `/actuator/health` response from a run you performed.
- Git commit hash of the baseline.
- One entry in [mock-interview-log](docs/interviews/mock-interview-log.md) answering "How does Spring Boot start up and wire beans?" rated ≥ 3/5 by your own rubric.

**Evidence collected:** _none yet_

---

## Phase 1 – Working Order REST API

**Status:** NOT STARTED · **Est.:** 4 weeks

**Learning objectives**
- Design REST resources, DTOs, validation, and error responses ([API contracts](docs/lld/api-contracts.md)).
- Understand layering: controller → service → repository; keep controllers thin.
- Understand idempotency at the API level (design only; full implementation in Phase 2/5).

**Implementation tasks** (in-memory repository first, to isolate web concerns)
1. Package layout per [order-service LLD](docs/lld/order-service.md#package-layout).
2. `POST /api/v1/orders`, `GET /api/v1/orders/{id}`, `GET /api/v1/orders` (paged), `POST /api/v1/orders/{id}/cancel`.
3. Request/response DTOs as records with Bean Validation.
4. `@RestControllerAdvice` producing a single error format (`application/problem+json`).
5. Domain state machine class with unit tests (no Spring).

**Required tests**
- Unit: state-machine transitions (all legal + illegal), price calculation.
- `@WebMvcTest`: 201/400/404/409 mappings, validation messages, error body shape.
- Service unit tests with a fake repository.

**HLD/LLD topics:** API design, DTO vs entity, status codes, error model, SOLID in the service layer.

**Debugging exercises:** malformed JSON → 400 vs 500; validation not triggering (missing `@Valid`); leaking entity fields in JSON; `Optional.get()` NPE-like failure.

**Acceptance criteria**
- All four endpoints behave per contract; invalid transitions return 409; invalid bodies return 400 with field errors.
- No controller contains business rules.

**Evidence required to mark VERIFIED**
- `./mvnw test` output showing the named test classes passing.
- `curl` transcript (real) for each endpoint, including one error case, pasted into [api-contracts](docs/lld/api-contracts.md) under "Verified examples".
- Labels in LLD docs updated to [IMPLEMENTED] for what exists.
- ≥ 2 LLD practice sessions logged (e.g. state machine, validation chain).

---

## Phase 2 – Persistence, transactions, and database correctness

**Status:** NOT STARTED · **Est.:** 5 weeks

**Learning objectives**
- JPA/Hibernate mapping, persistence context, lazy loading, N+1 ([DB handbook](docs/learning/database-fundamentals.md)).
- Transaction boundaries, isolation, locking, optimistic concurrency.
- Schema migration discipline (Flyway or Liquibase — decision pending, ADR needed).

**Implementation tasks**
1. Replace in-memory repo with Spring Data JPA entities per [database design](docs/lld/database-design.md).
2. Migrations (add dependency deliberately; record in ADR). Switch from `ddl-auto` to migrations.
3. `@Version` optimistic locking on `orders`; map `ObjectOptimisticLockingFailureException` → 409.
4. Idempotency-Key support on `POST /orders` using a unique constraint.
5. Pagination with indexes matching the query.
6. Decide the real DB for non-test runs (PostgreSQL likely; see [no-Docker note](#environment-constraint-no-docker)).

**Required tests**
- `@DataJpaTest` for repository queries and constraints.
- Integration test: two concurrent updates → exactly one succeeds (real threads, `CountDownLatch`).
- Integration test: duplicate idempotency key returns the original order, one row only.
- A test that counts SQL statements for list-with-items (guards against N+1).

**HLD/LLD topics:** data ownership, constraints as invariants, index design, transaction scope.

**Debugging exercises:** [FS-01 lost update](docs/debugging/failure-scenarios.md), FS-03 N+1/slow SQL, FS-04 pool exhaustion (see catalogue).

**Acceptance criteria**
- Invariants are enforced by the database, not only by code.
- Concurrency tests are deterministic and pass repeatedly (run 20×).

**Evidence required to mark VERIFIED**
- Test output for the above tests; a `EXPLAIN` output for the main list query pasted into [database-design](docs/lld/database-design.md).
- At least one incident entry in [incident-log](docs/debugging/incident-log.md) (injected counts, labelled as exercise) with root cause and regression test.
- ADR for DB engine and migration tool.

---

## Phase 3 – HLD and LLD of the order lifecycle

**Status:** NOT STARTED · **Est.:** 3 weeks

**Learning objectives**
- Produce an HLD in 45 minutes: requirements → estimates → API → data → components → deep dive → failure modes.
- Produce a clean LLD (classes, state machine, patterns) on a whiteboard.

**Implementation tasks**
1. Reconcile docs with reality: every `[PLANNED]` in `lld/` that was built becomes `[IMPLEMENTED]`; fix drift.
2. Add missing ADRs for decisions you made in Phases 1–2.
3. Refactor one rough area found while documenting (e.g. extract pricing policy) with tests.
4. Add lifecycle events to the domain model (still in-process): `OrderCreated`, `OrderCancelled`.

**Required tests:** refactoring is covered by existing tests; add tests for any new domain rule.

**HLD/LLD topics:** [system overview](docs/hld/system-overview.md), [architecture](docs/hld/architecture.md), [capacity](docs/hld/scalability-and-capacity.md), [class design](docs/lld/class-design.md).

**Debugging exercises:** trace a request end-to-end from log lines only, no debugger.

**Acceptance criteria**
- A stranger can read the docs and predict the code layout; docs match code.
- You can redraw the architecture and state machine from memory.

**Evidence required to mark VERIFIED**
- Doc review diff showing drift fixed (commit link).
- ≥ 3 HLD mocks and ≥ 3 LLD mocks logged in [mock-interview-log](docs/interviews/mock-interview-log.md) with self-scores and gap lists.

---

## Phase 4 – Event-driven processing with Kafka

**Status:** NOT STARTED · **Est.:** 6 weeks

**Learning objectives**
- Kafka partitions, keys, consumer groups, offsets, delivery semantics ([distributed systems handbook](docs/learning/distributed-systems.md)).
- Idempotent consumers; poison messages and DLTs.

**Implementation tasks**
1. Choose a runnable Kafka option without Docker ([options](#environment-constraint-no-docker)); record ADR.
2. Add `spring-kafka` (deliberately, with ADR).
3. Publish `OrderCreated` keyed by `orderId` (naive publish first, to *see* the dual-write problem).
4. Add a second consumer component (initially in the same app, separate package/consumer group) simulating inventory: consumes `OrderCreated`, emits `InventoryReserved|InventoryRejected`.
5. Order service consumes results and updates order state.
6. Inbox/processed-events table for dedupe.

**Required tests**
- Consumer idempotency test: deliver same event 3× → one effect.
- Ordering test: events for one key processed in order.
- Embedded/local-broker integration test for produce→consume.

**HLD/LLD topics:** async flows, topics, partitioning key choice, schema evolution.

**Debugging exercises:** FS-02 duplicate events, consumer lag growth, rebalance during slow processing, poison message.

**Acceptance criteria**
- Happy path order → reserved → confirmed works end to end locally.
- Duplicate delivery does not duplicate effects.

**Evidence required to mark VERIFIED**
- Test output; a documented local run transcript; consumer-group describe output showing lag/offsets.
- ADRs for broker option, topic/key design, and event schema.

---

## Phase 5 – Distributed consistency and failure recovery

**Status:** NOT STARTED · **Est.:** 6 weeks

**Learning objectives**
- Fix the dual-write with a transactional outbox; reason about crash points.
- Saga orchestration vs choreography; compensation; reconciliation.
- Retries with backoff and jitter; timeouts; circuit breakers; bulkheads.

**Implementation tasks**
1. Outbox table + relay (polling publisher first).
2. Convert order creation to write order + outbox in one transaction.
3. Saga: reserve inventory → authorize payment (simulated) → confirm; compensations on failure.
4. Reconciliation job for orders stuck in non-terminal states past a deadline.
5. Resilience4j (or equivalent) on a simulated flaky downstream; ADR.

**Required tests**
- Crash-injection tests at each step (kill between DB commit and publish; between consume and ack).
- Saga failure-path tests: each step failing → correct compensation and terminal state.
- Property/invariant test: no order is both CONFIRMED and CANCELLED; no inventory leak.

**HLD/LLD topics:** consistency models, outbox, saga, state machines under failure.

**Debugging exercises:** FS-05 retry storm, FS-06 deadlock, FS-08 downstream outage with breaker.

**Acceptance criteria**
- Killing the process at any injected crash point leaves the system recoverable to a consistent terminal state.

**Evidence required to mark VERIFIED**
- Crash-injection test output; reconciliation job run transcript; sequence diagrams updated to [IMPLEMENTED]; ≥ 2 incidents logged with real evidence.

---

## Phase 6 – Observability and deliberate debugging

**Status:** NOT STARTED · **Est.:** 4 weeks

**Learning objectives**
- Structured logs with correlation IDs; RED/USE metrics; traces ([observability handbook](docs/learning/observability-and-reliability.md)).
- Thread dumps, heap dumps, JFR, slow-query diagnosis.

**Implementation tasks**
1. Correlation/trace ID propagation (HTTP → logs → Kafka headers).
2. Micrometer metrics: request latency, outbox lag, consumer lag, pool usage; expose via actuator.
3. Tracing via Micrometer Tracing/OpenTelemetry (local backend decision → ADR).
4. Dashboards/alerts defined as code or documented JSON (Grafana experience applies).
5. Run 3 deliberate failure scenarios end to end using only telemetry to diagnose.

**Required tests:** metric-existence tests; log-correlation test.

**HLD/LLD topics:** SLIs/SLOs, alert design, runbooks.

**Debugging exercises:** FS-07 memory leak (heap dump analysis), FS-04 pool exhaustion (thread dump), FS-03 slow SQL.

**Acceptance criteria:** each scenario diagnosed within a time-box using the playbook; incident logged.

**Evidence required to mark VERIFIED:** three completed incident-log entries with screenshots/outputs; dashboard definitions committed; SLO doc.

---

## Phase 7 – Load testing, performance, and reliability

**Status:** NOT STARTED · **Est.:** 4 weeks

**Learning objectives**
- Design a load test; coordinated omission; percentiles; saturation; Little's law.
- Tune pools, indexes, batch sizes; apply backpressure and load shedding.

**Implementation tasks**
1. Pick a tool (k6, Gatling, or JMeter); script create+read mix.
2. Baseline, then change one variable at a time; record before/after.
3. Add rate limiting / bulkhead / bounded queues; re-test.
4. Soak test for leaks (hours).

**Required tests:** load scripts committed; performance regression threshold documented.

**HLD/LLD topics:** compare measured numbers to [capacity assumptions](docs/hld/scalability-and-capacity.md) and update them.

**Debugging exercises:** FS-04, FS-05, FS-07 under real load.

**Acceptance criteria:** documented max sustainable throughput on *your* hardware with p95/p99, the bottleneck identified, and one measured improvement.

**Evidence required to mark VERIFIED:** raw result files/summaries, environment description (hardware, JVM flags, DB), before/after table, analysis. Numbers must be clearly labelled as local-machine results.

---

## Phase 8 – Senior-level design interviews and project polish

**Status:** NOT STARTED · **Est.:** 4 weeks

**Learning objectives**
- Deliver structured, trade-off-driven designs and debugging stories under time pressure.
- Tell the project story credibly: what is built, what is designed, what was learned.

**Implementation tasks**
1. README at repo root with accurate scope; architecture diagram reflects only what exists.
2. CI pipeline (build + tests); code-coverage and static analysis as agreed.
3. Clean up TODOs; final doc reconciliation; tag a release.
4. Prepare 5-minute and 20-minute project walkthroughs.

**Required tests:** CI green on a clean checkout.

**HLD/LLD topics:** full question bank in [interviews/](docs/interviews/hld-questions.md).

**Debugging exercises:** two unseen scenarios solved on a timer.

**Acceptance criteria:** ≥ 10 HLD, ≥ 8 LLD, ≥ 8 backend mocks logged; unseen-problem average self-score ≥ 4/5 across the last 5 sessions (ideally with a peer).

**Evidence required to mark VERIFIED:** mock-interview-log entries with dates, scores, feedback; CI run link; walkthrough notes.

---

## Environment constraint: no Docker

Docker is unavailable **[UNVERIFIED cause: possibly company-device policy]**. Consequences and options:

| Need | Docker-free option | Caveat |
|---|---|---|
| Relational DB for dev | H2 (already present) in PostgreSQL mode; or a local PostgreSQL install (Homebrew/EDB installer) | H2 behaviour differs from PostgreSQL (locking, isolation, SQL dialect). Do not draw concurrency conclusions from H2 alone. Check device policy before installing. |
| Kafka | Run Apache Kafka (KRaft mode) from the downloadable tarball (needs only Java); or `spring-kafka-test` `EmbeddedKafka` for tests | Single broker: no real replication behaviour. Check policy before downloading binaries. |
| Integration tests with real infra | Testcontainers needs a container runtime | Unavailable; use embedded variants or a shared dev/cloud instance if allowed. |
| Cloud-hosted alternatives | Managed PostgreSQL/Kafka in a personal cloud account | Never use company resources or data. Mind cost. |

Whatever you choose, record it in an ADR and state the fidelity limits wherever you quote results.
