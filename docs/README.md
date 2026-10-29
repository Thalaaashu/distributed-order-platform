# Distributed Order Platform — Engineering Documentation & Learning System

**Last updated:** 2026-10-29

This folder is four things at once:

1. Official engineering documentation for the project.
2. A backend learning handbook (Java, Spring Boot, databases, distributed systems).
3. HLD/LLD interview preparation material.
4. A debugging notebook and a record of architecture decisions and verified progress.

The learning plan lives in [`../LEARNING_ROADMAP.md`](../LEARNING_ROADMAP.md).

## Contents

- [Truth labels](#truth-labels-read-this-first)
- [Verified project snapshot](#verified-project-snapshot)
- [Document map](#document-map)
- [Suggested reading order](#suggested-reading-order)
- [How to maintain these docs](#how-to-maintain-these-docs)
- [Items that could not be verified](#items-that-could-not-be-verified)

## Truth labels (read this first)

Every claim about the system carries one of these labels, either inline or by section:

| Label | Meaning |
|---|---|
| **[IMPLEMENTED]** | Exists in the repository and was observed by reading the files. |
| **[PLANNED]** | A design intention. Not built. May change. |
| **[ILLUSTRATIVE]** | Example code or numbers for teaching. Does not exist in the repo. |
| **[UNVERIFIED]** | Believed true (e.g. reported by the author or inferred) but not confirmed from files or a run. |
| **[ASSUMPTION]** | A requirement or number chosen for design purposes; replace when real data exists. |

Rules for this documentation set:

- No test result, benchmark, incident, or production experience is recorded unless it actually happened and has evidence.
- A phase is `VERIFIED` and an interview question is `MASTERED` only with the evidence defined in the roadmap / interview docs.
- Planned components (Kafka, outbox, saga, gateway, Redis, ...) are never described as present.

## Verified project snapshot

Observed from the repository on 2026-10-29.

| Item | Value | Source |
|---|---|---|
| Spring Boot | **4.1.2** (parent POM) | [`pom.xml`](../pom.xml) |
| Java | 21 (`java.version`); local JDK is Temurin 21.0.13 | `pom.xml`; `java -version` |
| Build | Maven via wrapper (`./mvnw`) | [`mvnw`](../mvnw) |
| Group / artifact | `com.ashish:distributed-order-platform:0.0.1-SNAPSHOT` | `pom.xml` |
| Base package | `com.ashish.distributed_order_platform` (underscore; hyphen is invalid in Java) | [`HELP.md`](../HELP.md) |
| Compile dependencies | `spring-boot-starter-webmvc`, `-data-jpa`, `-validation`, `-actuator`, `spring-boot-h2console` | `pom.xml` |
| Runtime dependency | `h2` (runtime scope) | `pom.xml` |
| Test dependencies | `spring-boot-starter-{webmvc,data-jpa,validation,actuator}-test` | `pom.xml` |
| Build plugins | `spring-boot-maven-plugin`, `build-helper-maven-plugin`, `graphqlcodegen-maven-plugin` 1.61.5 (Netflix DGS codegen) | `pom.xml` |
| Application source | One class: [`DistributedOrderPlatformApplication`](../src/main/java/com/ashish/distributed_order_platform/DistributedOrderPlatformApplication.java) (`@SpringBootApplication` + `main`) | source |
| Configuration | One property: `spring.application.name=distributed-order-platform` | [`application.properties`](../src/main/resources/application.properties) |
| Tests | One: [`contextLoads`](../src/test/java/com/ashish/distributed_order_platform/DistributedOrderPlatformApplicationTests.java) (`@SpringBootTest`, empty body) | source |
| Controllers / entities / repositories / services | **None** | source tree |
| Messaging (Kafka), cache, security, gateway | **None** (no dependency declared) | `pom.xml` |
| Version control | Directory is **not a git repository** | `git` check |

**Locally cached versions [UNVERIFIED — inferred from `~/.m2`]:** Spring Framework 7.0.10, Hibernate ORM 7.4.13.Final. Confirm with `./mvnw dependency:tree` (this may need network access, so it was not run).

**Reported by the author [UNVERIFIED]:** the app started and `/actuator/health` returned `{"status":"UP"}`. This is consistent with the declared actuator dependency, but I did not start the app.

**Build observation:** `./mvnw -o test` failed because `maven-surefire-plugin:3.5.6` is not in the local Maven cache. So it appears **the test phase has not been run on this machine yet**, and the offline cache is incomplete. Run `./mvnw test` once with network access to establish a baseline (Phase 0 acceptance item).

**Noteworthy POM details**

- The DGS codegen plugin points at `src/main/resources/graphql-client`, which does not exist, and no GraphQL dependency is declared. This is Initializr scaffolding, not a feature. Decide whether to keep or remove it; see [ADR-0008](hld/architecture-decisions.md#adr-0008--remove-or-keep-the-dgs-codegen-plugin-open).
- `h2` is runtime scope, so with no datasource configured Spring Boot's documented default is an embedded in-memory H2 database. Data is lost on restart. **[UNVERIFIED]** until you inspect startup logs.
- Boot 4 split the auto-configuration and test-slice modules. Starter and package names differ from Boot 3 tutorials (e.g. `spring-boot-starter-webmvc` instead of `-web`). Always check imports against the 4.1.2 reference docs when pasting older examples.

## Document map

### HLD — what the system is and why it is shaped that way
| Doc | Purpose |
|---|---|
| [hld/system-overview.md](hld/system-overview.md) | Problem statement, business context, scope, current vs target state. Start here. |
| [hld/requirements.md](hld/requirements.md) | Functional and non-functional requirements, assumptions, expected scale. |
| [hld/architecture.md](hld/architecture.md) | Component responsibilities, Mermaid diagrams, request and async flows, data ownership, security/deployment/recovery. |
| [hld/scalability-and-capacity.md](hld/scalability-and-capacity.md) | Capacity math with labelled assumptions, caching, partitioning, replication, load balancing. |
| [hld/architecture-decisions.md](hld/architecture-decisions.md) | ADR log: problem, choice, alternatives, limitations, larger-scale evolution. |

### LLD — how the code should be structured
| Doc | Purpose |
|---|---|
| [lld/order-service.md](lld/order-service.md) | Order service internals: packages, responsibilities, state machine, transactions, validation, errors. |
| [lld/api-contracts.md](lld/api-contracts.md) | REST contracts, DTOs, status codes, error format, idempotency, versioning. |
| [lld/database-design.md](lld/database-design.md) | Tables, constraints, indexes, locking, outbox/inbox tables, migrations. |
| [lld/class-design.md](lld/class-design.md) | Class diagrams, SOLID, patterns, DI, test boundaries, illustrative Java. |
| [lld/sequence-diagrams.md](lld/sequence-diagrams.md) | Mermaid sequence diagrams of key flows including crash points. |

### Learning handbook
| Doc | Purpose |
|---|---|
| [learning/java-backend.md](learning/java-backend.md) | Java core, concurrency, JVM, plus Git/CI/code review. |
| [learning/spring-boot.md](learning/spring-boot.md) | DI, bean lifecycle, configuration, REST, validation, exception handling. |
| [learning/database-fundamentals.md](learning/database-fundamentals.md) | JPA/Hibernate, transactions, N+1, indexes, query plans, isolation, locks. |
| [learning/distributed-systems.md](learning/distributed-systems.md) | Messaging, Kafka, idempotency, outbox, saga, resilience, with crash scenarios. |
| [learning/testing-and-quality.md](learning/testing-and-quality.md) | Unit/integration/contract/E2E testing and quality practices. |
| [learning/observability-and-reliability.md](learning/observability-and-reliability.md) | Logs, metrics, traces, SLOs, health, load testing, reliability practice. |

### Debugging
| Doc | Purpose |
|---|---|
| [debugging/debugging-playbook.md](debugging/debugging-playbook.md) | Method, tool commands, and the reusable incident template. |
| [debugging/incident-log.md](debugging/incident-log.md) | Real incidents only. Currently empty. |
| [debugging/failure-scenarios.md](debugging/failure-scenarios.md) | Catalogue of planned exercises (status PLANNED until implemented and verified). |

### Interviews
| Doc | Purpose |
|---|---|
| [interviews/hld-questions.md](interviews/hld-questions.md) | HLD questions with frameworks, trade-offs, follow-ups. |
| [interviews/lld-questions.md](interviews/lld-questions.md) | LLD / machine-coding questions. |
| [interviews/backend-interview-notes.md](interviews/backend-interview-notes.md) | Java concurrency, database, distributed systems, debugging, Spring questions. |
| [interviews/mock-interview-log.md](interviews/mock-interview-log.md) | Evidence log; the only source of "mastered" claims. |

Root: [`../LEARNING_ROADMAP.md`](../LEARNING_ROADMAP.md) — phased plan, 8–10 h/week.

## Suggested reading order

1. [hld/system-overview.md](hld/system-overview.md) — what we are building.
2. [`../LEARNING_ROADMAP.md`](../LEARNING_ROADMAP.md) — what to do next, and how progress is verified.
3. [lld/order-service.md](lld/order-service.md) — the shape of the first real code.
4. Then the learning docs on demand, matching the current roadmap phase.

## How to maintain these docs

- Update the "Last updated" date when you change a doc materially.
- When code lands, flip labels `[PLANNED]` → `[IMPLEMENTED]` in the same change, and link to the real file.
- Record every design decision as an ADR (append-only; supersede, do not rewrite).
- Record every real bug in [incident-log](debugging/incident-log.md) using the template.
- Record every practice session in [mock-interview-log](interviews/mock-interview-log.md).
- Link to files that exist. Planned files are written as code spans (`like/this.java`), not links.
- Keep one canonical home per topic and link to it. For example, outbox is explained in [learning/distributed-systems.md](learning/distributed-systems.md); other docs link there.

## Items that could not be verified

- Actual runtime health (`UP`) — author-reported.
- Resolved dependency versions beyond the Boot parent — inferred from the local Maven cache.
- Whether `mvn test` passes — not run (see build observation above).
- Business domain, expected scale, and SLAs — no source in the repo; all are **[ASSUMPTION]**s in [requirements](hld/requirements.md).
- Target runtime (Azure/Kubernetes) and database engine for production — your background suggests Azure/Kubernetes; nothing in the repo confirms it.
