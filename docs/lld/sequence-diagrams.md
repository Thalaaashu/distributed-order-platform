# Sequence Diagrams

**Last updated:** 2026-10-29

Related: [Order service LLD](order-service.md) · [HLD architecture](../hld/architecture.md) · [Distributed systems handbook](../learning/distributed-systems.md) · [Failure scenarios](../debugging/failure-scenarios.md)

> **All flows are [PLANNED].** None is implemented. Each flow notes the phase in which it would be built and the crash points to test. Mermaid blocks are syntax-checked only by rendering (VS Code Markdown preview with a Mermaid extension, or GitHub).

## Contents
1. [Create order (Phase 1: no persistence guarantees)](#1-create-order-phase-1)
2. [Create order with idempotency (Phase 2)](#2-create-order-with-idempotency-phase-2)
3. [Concurrent cancel vs confirm (Phase 2)](#3-concurrent-cancel-vs-confirm-phase-2)
4. [Create order with outbox and relay (Phase 5)](#4-create-order-with-outbox-and-relay-phase-5)
5. [Idempotent consumer (Phase 4)](#5-idempotent-consumer-phase-4)
6. [Saga with compensation (Phase 5)](#6-saga-with-compensation-phase-5)
7. [Retry with circuit breaker (Phase 5)](#7-retry-with-circuit-breaker-phase-5)
8. [Reconciliation of a stuck order (Phase 5)](#8-reconciliation-of-a-stuck-order-phase-5)

## 1. Create order (Phase 1)

```mermaid
sequenceDiagram
    actor C as Client
    participant Ctl as OrderController
    participant V as Bean Validation
    participant S as OrderService
    participant R as OrderRepository
    C->>Ctl: POST /api/v1/orders {json}
    Ctl->>V: validate CreateOrderRequest
    alt invalid
        V-->>Ctl: ConstraintViolations
        Ctl-->>C: 400 problem+json (field errors)
    else valid
        Ctl->>S: create(command)
        S->>S: new Order(...) enforces invariants
        S->>R: save(order)
        R-->>S: order
        S-->>Ctl: order
        Ctl-->>C: 201 Created + Location
    end
```

## 2. Create order with idempotency (Phase 2)

```mermaid
sequenceDiagram
    actor C as Client
    participant S as OrderService (tx)
    participant DB as Database
    C->>S: POST /orders, Idempotency-Key=K
    S->>DB: BEGIN
    S->>DB: INSERT idempotency_keys(customer,K,hash)
    alt unique violation (K exists)
        DB-->>S: duplicate key
        S->>DB: ROLLBACK
        S->>DB: SELECT original order by (customer,K)
        alt hash equal
            S-->>C: original order
        else hash differs
            S-->>C: 422 key reused with different payload
        end
    else inserted
        S->>DB: INSERT orders, order_items
        S->>DB: COMMIT
        S-->>C: 201 Created
    end
```

**Crash points to test:** (a) crash before COMMIT → nothing persisted → client retry creates it once; (b) crash after COMMIT before response → client retry gets original via key; (c) two simultaneous requests with the same key → the second blocks on the unique index, then sees the duplicate.

## 3. Concurrent cancel vs confirm (Phase 2)

```mermaid
sequenceDiagram
    participant T1 as Tx1 cancel
    participant T2 as Tx2 confirm
    participant DB as orders row (version=3, CREATED)
    T1->>DB: read order (v3)
    T2->>DB: read order (v3)
    T1->>DB: UPDATE ... SET status=CANCELLED, version=4 WHERE id AND version=3
    DB-->>T1: 1 row
    T1->>DB: COMMIT
    T2->>DB: UPDATE ... SET status=CONFIRMED, version=4 WHERE id AND version=3
    DB-->>T2: 0 rows
    T2-->>T2: OptimisticLockingFailure
    T2->>DB: reload (CANCELLED v4)
    T2-->>T2: confirm illegal, stop (consumer acks, API returns 409)
```

## 4. Create order with outbox and relay (Phase 5)

```mermaid
sequenceDiagram
    actor C as Client
    participant S as OrderService
    participant DB as Order DB
    participant Rl as Outbox relay
    participant K as Kafka
    C->>S: POST /orders
    S->>DB: BEGIN
    S->>DB: INSERT order, items
    S->>DB: INSERT outbox_events(OrderCreated)
    S->>DB: COMMIT
    S-->>C: 201
    loop every N ms
        Rl->>DB: SELECT pending outbox rows FOR UPDATE SKIP LOCKED (batch)
        Rl->>K: send(key=orderId, event) acks=all
        K-->>Rl: ack
        Rl->>DB: UPDATE outbox SET published_at=now()
    end
```

**Crash analysis**

| Crash point | State left behind | Outcome |
|---|---|---|
| Before COMMIT | No order, no outbox row | Client retries (same key); consistent |
| After COMMIT, before response | Order + pending outbox row | Relay publishes anyway; client retry returns original |
| Relay after `send`, before marking published | Event in Kafka, row still pending | Relay republishes → **duplicate event**; consumers dedupe by `eventId` |
| Relay before `send` | Row pending | Published on the next poll |
| Kafka down | Rows accumulate | Relay backs off; alert on outbox age; no loss |

## 5. Idempotent consumer (Phase 4)

```mermaid
sequenceDiagram
    participant K as Kafka
    participant C as Consumer (Inventory)
    participant DB as Inventory DB
    K->>C: poll() -> OrderCreated(eventId=E)
    C->>DB: BEGIN
    C->>DB: INSERT processed_events(consumer,E)
    alt duplicate (already processed)
        DB-->>C: unique violation
        C->>DB: ROLLBACK
    else new
        C->>DB: reserve stock (conditional UPDATE)
        C->>DB: INSERT outbox(InventoryReserved|Rejected)
        C->>DB: COMMIT
    end
    C->>K: commit offset
```

**Crash analysis:** crash after DB COMMIT but before the offset commit → Kafka redelivers E → insert into `processed_events` fails → skipped → offset committed. Crash before COMMIT → nothing happened → redelivery processes normally. This is "at-least-once delivery + idempotent effect = effectively-once".

## 6. Saga with compensation (Phase 5)

```mermaid
sequenceDiagram
    participant O as Order
    participant I as Inventory
    participant P as Payment
    O->>I: (event) OrderCreated
    I-->>O: InventoryReserved
    I->>P: (event) InventoryReserved
    P-->>O: PaymentFailed
    O->>O: status = REJECTED
    O->>I: (event) OrderRejected
    I->>I: release reservation (idempotent)
    Note over I,P: Compensation must tolerate duplicates and out-of-order arrival (release before reserve was applied)
```

Hard cases to design for: compensation arrives before the original action (record a "tombstone" so a late reserve is ignored); a timeout with no reply (reconciler); payment authorized but order cancelled concurrently (void the authorization).

## 7. Retry with circuit breaker (Phase 5)

```mermaid
sequenceDiagram
    participant S as Service
    participant CB as CircuitBreaker
    participant D as Downstream
    S->>CB: call
    CB->>D: attempt 1 (timeout 500ms)
    D--xCB: timeout
    CB->>D: attempt 2 after backoff+jitter
    D--xCB: 503
    Note over CB: failure rate > threshold -> OPEN
    S->>CB: next call
    CB-->>S: fail fast (fallback / 503)
    Note over CB: after wait -> HALF_OPEN, probe with few calls
```

Retries must be bounded, only for idempotent operations, with a total deadline, and budgeted (e.g. ≤ 10% extra traffic) or they cause retry storms ([FS-05](../debugging/failure-scenarios.md)).

## 8. Reconciliation of a stuck order (Phase 5)

```mermaid
sequenceDiagram
    participant J as Reconciler job
    participant DB as Order DB
    participant I as Inventory
    J->>DB: find orders CREATED for > 5 min (partial index)
    loop each stuck order
        J->>I: query reservation status (or re-emit OrderCreated)
        alt reserved + paid
            J->>DB: confirm (version check)
        else unknown / failed
            J->>DB: reject + emit OrderRejected (compensation)
        end
    end
    J->>J: emit metric stuck_orders_total, alert if rising
```

The reconciler is the safety net that makes "eventually consistent" a bounded promise rather than a hope.
