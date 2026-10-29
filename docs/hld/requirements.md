# Requirements

**Last updated:** 2026-10-29

Related: [System overview](system-overview.md) · [Capacity](scalability-and-capacity.md) · [API contracts](../lld/api-contracts.md)

> Everything here is a **[PLANNED]** requirement or **[ASSUMPTION]**. None is implemented. IDs (FR-, NFR-) are stable so tests, ADRs, and interview answers can reference them.

## Contents
- [Functional requirements](#functional-requirements)
- [Non-functional requirements](#non-functional-requirements)
- [Assumptions and expected scale](#assumptions-and-expected-scale)
- [Out of scope](#out-of-scope)
- [Open questions](#open-questions)
- [Interview technique: clarifying requirements](#interview-technique-clarifying-requirements)

## Functional requirements

| ID | Requirement | Priority | Phase |
|---|---|---|---|
| FR-1 | Create an order with ≥1 line items (productId, quantity, unit price at order time) for a customer. | Must | 1 |
| FR-2 | Retrieve an order by ID, including items and current status. | Must | 1 |
| FR-3 | List a customer's orders, newest first, paginated. | Must | 1–2 |
| FR-4 | Cancel an order if its state allows. Cancellation after confirmation releases reserved stock. | Must | 1, 5 |
| FR-5 | Orders move through a defined lifecycle; illegal transitions are rejected. | Must | 1 |
| FR-6 | Creating an order is **idempotent** given a client-supplied `Idempotency-Key`: retries do not create duplicates. | Must | 2 |
| FR-7 | Publish order lifecycle events for downstream consumers. | Should | 4 |
| FR-8 | Reserve inventory and authorize payment (simulated) as part of confirmation, with compensation on failure. | Should | 4–5 |
| FR-9 | Operators can find orders stuck in non-terminal states and re-drive/repair them. | Should | 5 |
| FR-10 | Audit trail: every state change records who/what/when. | Could | 3+ |

## Non-functional requirements

Numeric targets are **[ASSUMPTION]**s to practise SLO thinking; replace with measured/real values. No target has been measured.

| ID | Category | Requirement | How it will be verified |
|---|---|---|---|
| NFR-1 | Correctness | No lost orders: an acknowledged `201` order is durable. | Crash tests; DB commit before ack |
| NFR-2 | Correctness | No duplicate effects (double charge/reserve) under retries and redelivery. | Idempotency tests |
| NFR-3 | Consistency | Order state is strongly consistent within the order service; cross-component state is eventually consistent, converging within a bounded time (e.g. ≤ 60 s) or flagged for repair. | Reconciliation job tests |
| NFR-4 | Availability | Order creation 99.9% monthly (≈ 43 min downtime/month) **[ASSUMPTION]**. | SLO dashboards (Phase 6) |
| NFR-5 | Latency | `POST /orders` p95 < 300 ms, p99 < 800 ms; `GET /orders/{id}` p95 < 100 ms at nominal load **[ASSUMPTION]**. | Load tests (Phase 7) — results will be local-machine only |
| NFR-6 | Scalability | Handle 10× daily average at peak without redesign; 100× requires documented changes. | Capacity doc vs load tests |
| NFR-7 | Durability | RPO ≈ 0 for committed orders; RTO ≤ 30 min **[ASSUMPTION]**. | Recovery drill (documented) |
| NFR-8 | Security | Authenticated callers; a customer can only see their own orders; secrets not in source. | Security tests (not scheduled) |
| NFR-9 | Observability | Every request has a correlation ID visible in logs, metrics, and events; key flows traced. | Phase 6 tests |
| NFR-10 | Maintainability | Layered code; ≥ 80% line coverage on domain/service logic **[ASSUMPTION]** (coverage is a smell detector, not a goal). | CI |
| NFR-11 | Operability | Safe rollouts (backward-compatible schema and events); runbooks for top failure modes. | Docs review |

## Assumptions and expected scale

All **[ASSUMPTION]** — chosen for the exercise; mirrored in [capacity](scalability-and-capacity.md).

| Parameter | Assumed value | Rationale |
|---|---|---|
| Orders/day | 1,000,000 | Medium e-commerce platform; forces real thinking but fits one DB initially. |
| Average items per order | 3 | Typical basket. |
| Peak-to-average ratio | 10× | Sales events. |
| Read:write ratio | 20:1 | Users check status repeatedly. |
| Order payload | ≈ 2 KB total stored | Header + items + metadata. |
| Retention | 2 years hot, then archive | Finance/support. |
| Customers | Tens of millions registered, ≈ 10% monthly active | Drives list-query cardinality. |
| Clients retry | Yes (mobile networks) | Makes idempotency mandatory. |

**Local-development reality:** none of this scale will be reached on a laptop. Load tests in Phase 7 will measure what *your machine* supports and compare against the model.

## Out of scope

Pricing/promotions engine, tax, shipping carriers, returns/refunds beyond cancel, multi-region, GDPR tooling, UI. They appear only as interview follow-ups.

## Open questions

1. Is order total computed server-side from a catalog, or client-supplied? (Proposed: server stores client-supplied unit price for now; catalog is out of scope. Real systems must not trust client prices.)
2. Which production database? (PostgreSQL assumed for design; undecided.) → [ADR-0003](architecture-decisions.md#adr-0003--relational-database-as-system-of-record)
3. Is partial cancellation or order amendment required? (Assumed no.)
4. Do we need tenant/customer isolation beyond authorization checks? (Assumed no.)

## Interview technique: clarifying requirements

In the first 5 minutes of an HLD interview, state functional scope, then ask or assume: scale (QPS, data size), read/write ratio, latency/availability targets, consistency needs per operation, and what is explicitly out of scope. Write the assumptions on the board. Interviewers grade this step heavily; this file is a worked example of the output.
