# Architecture Decisions (ADR Log)

**Last updated:** 2026-10-29

Related: [Architecture](architecture.md) · [Capacity](scalability-and-capacity.md) · [Roadmap](../../LEARNING_ROADMAP.md)

Append-only. To change a decision, add a new ADR that supersedes the old one; never rewrite history. Statuses: `ACCEPTED` (decided and reflected in the repo), `PROPOSED` (recommended, not yet adopted/built), `OPEN` (undecided), `SUPERSEDED`.

> Only ADR-0008 and part of ADR-0003/0009 reflect what is in the repo today (the Initializr defaults). The rest are **PROPOSED** design decisions; none are implemented.

## Contents
- [Index](#index)
- [ADR template](#adr-template)
- ADRs: [0001](#adr-0001--start-as-a-modular-monolith-not-microservices) · [0002](#adr-0002--rest-for-client-apis-kafka-for-internal-events) · [0003](#adr-0003--relational-database-as-system-of-record) · [0004](#adr-0004--optimistic-locking-and-idempotency-keys-for-write-correctness) · [0005](#adr-0005--transactional-outbox-for-event-publication) · [0006](#adr-0006--saga-style-choreography-first-orchestration-considered) · [0007](#adr-0007--at-least-once-delivery-with-idempotent-consumers) · [0008](#adr-0008--remove-or-keep-the-dgs-codegen-plugin-open) · [0009](#adr-0009--local-infrastructure-without-docker-open) · [0010](#adr-0010--defer-caching-gateway-and-sharding)

## Index

| ADR | Title | Status |
|---|---|---|
| 0001 | Modular monolith first | PROPOSED |
| 0002 | REST for client APIs, Kafka for internal events | PROPOSED |
| 0003 | Relational DB as system of record | PROPOSED (H2 in-memory is the only DB present) |
| 0004 | Optimistic locking + idempotency keys | PROPOSED |
| 0005 | Transactional outbox | PROPOSED |
| 0006 | Saga choreography first | PROPOSED |
| 0007 | At-least-once + idempotent consumers | PROPOSED |
| 0008 | DGS codegen plugin keep/remove | OPEN |
| 0009 | Local infrastructure without Docker | OPEN |
| 0010 | Defer caching, gateway, sharding | PROPOSED |

## ADR template

```markdown
## ADR-XXXX — Title (STATUS)
- Date / Deciders:
- Context and problem:
- Decision:
- Why this is appropriate:
- Alternatives considered:
- Limitations / consequences:
- How it changes at larger scale:
- Evidence / links (tests, commits, measurements):
```

---

## ADR-0001 — Start as a modular monolith, not microservices
**Status:** PROPOSED · **Date:** 2026-10-29

- **Problem:** Choose an initial deployment shape that teaches distributed-systems concepts without drowning in operational overhead on a single laptop with no Docker.
- **Decision:** One Spring Boot deployable with clearly separated modules/packages (`order`, later `inventory`, `payment`), each owning its data and talking via interfaces/events, not by sharing entities.
- **Why appropriate:** Single process = easy debug, one build, no network failure modes yet; module boundaries make later extraction mechanical; fits a 9 h/week budget.
- **Alternatives:** (a) Microservices from day one — realistic failure modes sooner, but heavy tooling/ops and slower learning; (b) a plain layered monolith with shared tables — fastest, but leads to a big ball of mud and teaches nothing about boundaries.
- **Limitations:** In-process calls hide network failures (latency, partial failure); you must *simulate* them (fault injection) and use Kafka even between in-process modules to exercise async semantics. Single deployable means one scaling unit.
- **At scale:** Extract modules with independent scaling/availability needs (e.g. payment due to compliance, inventory due to hot contention) into services; keep the event contracts; move each DB schema to its own instance.

## ADR-0002 — REST for client APIs, Kafka for internal events
**Status:** PROPOSED

- **Problem:** Clients need request/response; internal steps need decoupling and durability.
- **Decision:** JSON over HTTP (Spring MVC) at the edge; Kafka for state-change events between components.
- **Why:** REST is ubiquitous, easily tested (`MockMvc`, `curl`); Kafka gives durable ordered logs, replay, and consumer scaling, and is highly relevant to target roles.
- **Alternatives:** gRPC (efficient, strongly typed, harder to debug by hand; good internally); GraphQL (flexible reads; the repo contains DGS *codegen* only — see ADR-0008; adds query-cost control complexity); RabbitMQ/SQS/Azure Service Bus (simpler queue semantics, no replay/log semantics; Service Bus is plausible given Azure background); synchronous REST between services (simple but couples availability: temporal coupling).
- **Limitations:** Kafka operational complexity; ordering only per partition; at-least-once semantics force idempotent consumers; eventual consistency complicates UX (status "pending").
- **At scale:** schema registry + compatibility rules, topic governance, tiered storage, per-domain clusters.

## ADR-0003 — Relational database as system of record
**Status:** PROPOSED (H2 present; production engine OPEN)

- **Problem:** Orders require transactions, constraints, and flexible queries.
- **Decision:** Relational DB (PostgreSQL assumed for design) per component. For now **H2 in-memory** (present on the classpath **[IMPLEMENTED]**) as a development convenience only.
- **Why:** ACID, unique constraints (idempotency), row locks, mature tooling (`EXPLAIN`), strong ecosystem with JPA/Hibernate.
- **Alternatives:** document DB (flexible schema; weaker multi-row invariants/joins; fine for order *read* views); wide-column/Dynamo-style (horizontal scale, limited query flexibility, you design around access patterns); event sourcing (full history, replay; high complexity, steep learning curve).
- **Limitations:** vertical write scaling limit; H2 ≠ PostgreSQL in locking/isolation/SQL dialect, so concurrency findings from H2 may not transfer (see [database fundamentals](../learning/database-fundamentals.md)); data is lost on restart with in-memory H2.
- **At scale:** replicas → partitioning → sharding; CQRS read models. See [capacity](scalability-and-capacity.md#scaling-the-database).

## ADR-0004 — Optimistic locking and idempotency keys for write correctness
**Status:** PROPOSED

- **Problem:** Concurrent updates to the same order (cancel vs confirm) and client retries must not corrupt state or duplicate orders.
- **Decision:** `@Version` column on `orders` (optimistic concurrency) and a unique `(customer_id, idempotency_key)` constraint written in the same transaction as the order.
- **Why:** Contention on a single order is rare, so optimistic locking avoids holding locks; the unique constraint gives a race-free dedupe that application-level "check then insert" cannot.
- **Alternatives:** pessimistic locking `SELECT ... FOR UPDATE` (best for high-contention rows such as stock counters); serializable isolation (simple reasoning, retries on serialization failures, lower throughput); distributed locks (Redis/ZooKeeper — fragile; avoid for correctness).
- **Limitations:** optimistic failures must be mapped to 409 and retried by caller; hot rows (inventory) will livelock under optimistic scheme — use conditional updates (`UPDATE ... SET qty = qty - ? WHERE qty >= ?`).
- **At scale:** keys may need TTL/cleanup; idempotency store may move to Redis with durable fallback if the table becomes hot.

## ADR-0005 — Transactional outbox for event publication
**Status:** PROPOSED (Phase 5)

- **Problem:** *Dual write* — saving to DB and publishing to Kafka are two systems with no shared transaction. Crash between them → lost or phantom events.
- **Decision:** Write the event to an `outbox_events` table in the same DB transaction as the state change; a relay publishes rows to Kafka and marks them sent.
- **Why:** The DB transaction makes "state changed" and "event recorded" atomic; the relay provides at-least-once delivery.
- **Alternatives:** publish-then-commit or commit-then-publish (both lose consistency on crash); Kafka transactions (cover Kafka-to-Kafka, not the DB); CDC (Debezium) reading the DB log (removes polling, needs infra); event sourcing (event log *is* the DB; high complexity).
- **Limitations:** duplicates possible (relay crash after publish before marking) → consumers must dedupe; polling adds latency and DB load; outbox table needs cleanup; ordering requires care (per aggregate ordering by sequence).
- **At scale:** CDC relay, partitioned outbox, batch publishing, archiving.
- **Details:** [distributed systems handbook](../learning/distributed-systems.md#transactional-outbox-and-the-dual-write-problem).

## ADR-0006 — Saga style: choreography first, orchestration considered
**Status:** PROPOSED (Phase 5)

- **Problem:** Multi-step business transaction across components without distributed ACID.
- **Decision:** Start with **choreography** (components react to each other's events) for two participants; **re-evaluate orchestration** (an explicit saga state machine in Order Service) when steps exceed ~3 or compensation logic becomes hard to follow.
- **Why:** Choreography has no central coordinator and is the simplest way to learn event flows; two participants stay readable.
- **Alternatives:** orchestration (central workflow; clearer visibility and timeouts, coordinator coupling); 2PC/XA (strong atomicity, blocking, poor availability, not supported by Kafka); Temporal/workflow engines (durable orchestration, new infra).
- **Limitations:** hard to see the overall flow; cyclic dependencies; every compensation must be idempotent and tolerate out-of-order arrival; need timeouts (a lost event = stuck order → reconciler).
- **At scale:** orchestrated sagas with a durable workflow engine and saga dashboards.

## ADR-0007 — At-least-once delivery with idempotent consumers
**Status:** PROPOSED (Phase 4)

- **Problem:** Exactly-once delivery across a network is not achievable in general; what we need is exactly-once *effects*.
- **Decision:** Producers `acks=all`, consumers commit offsets after processing, and consumers record processed event IDs in the same DB transaction as their effect (inbox/dedupe table).
- **Why:** Simple, robust, testable; works with any broker.
- **Alternatives:** at-most-once (loses events); Kafka exactly-once semantics (transactions + `isolation.level=read_committed`) — valid for Kafka→Kafka pipelines but does not extend to external DB side effects without the inbox pattern; naturally idempotent operations (set-to-value) where possible.
- **Limitations:** dedupe table growth (retention/TTL); out-of-order events still need version checks.
- **At scale:** partition dedupe by key; bloom filters as a pre-filter; compact tables.

## ADR-0008 — Remove or keep the DGS codegen plugin (OPEN)
**Status:** OPEN — **repo-observed fact**

- **Context:** [`pom.xml`](../../pom.xml) configures `graphqlcodegen-maven-plugin` 1.61.5 (generating a GraphQL *client* from schemas in `src/main/resources/graphql-client`) plus `build-helper-maven-plugin`. That directory does not exist and no GraphQL dependency is declared. [`HELP.md`](../../HELP.md) confirms it was added by Initializr. It is not used by any feature.
- **Options:** (a) remove both plugins to reduce build surface and plugin-resolution risk (they must be downloadable even offline-first); (b) keep if you intend to call a remote GraphQL service.
- **Recommendation:** remove unless a concrete GraphQL use appears. Do this as a deliberate code change (not part of this documentation task), then run `./mvnw test`.
- **Consequence of keeping:** extra plugin downloads and an empty/failed generation step may confuse build debugging. Whether the generate goal currently succeeds with no schema directory is **[UNVERIFIED]**.

## ADR-0009 — Local infrastructure without Docker (OPEN)
**Status:** OPEN

- **Context:** Docker appears unavailable (possibly device policy) **[UNVERIFIED cause]**. Testcontainers and docker-compose are therefore out.
- **Options:** H2 (present) for DB; local PostgreSQL/Kafka binaries if policy allows; embedded Kafka for tests; personal cloud instances. See [roadmap note](../../LEARNING_ROADMAP.md#environment-constraint-no-docker).
- **Decision criteria:** fidelity to production behaviour (especially locking/isolation), installability under policy, test determinism, cost.
- **Consequence:** results from H2 or single-broker Kafka must be labelled with their fidelity limits.

## ADR-0010 — Defer caching, gateway, and sharding
**Status:** PROPOSED

- **Problem:** These components are interview-relevant but add complexity without a demonstrated need.
- **Decision:** Document them as concepts only; implement none until a measured bottleneck (Phase 7) justifies one.
- **Alternatives:** build them early for resume value — rejected: premature, hides core correctness lessons.
- **Limitations:** the project will not demonstrate them; interview answers must rely on concept knowledge and honest labelling.
- **At scale:** see [capacity](scalability-and-capacity.md#what-changes-at-10-and-100).
