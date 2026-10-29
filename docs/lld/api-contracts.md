# API Contracts

**Last updated:** 2026-10-29

Related: [Order service LLD](order-service.md) · [Class design](class-design.md) · [Database design](database-design.md) · [HLD architecture](../hld/architecture.md)

> **Status: [PLANNED].** No controller exists yet. This is the contract the Phase 1 code must satisfy. JSON examples are **[ILLUSTRATIVE]** designs, not captured responses. The "Verified examples" section is intentionally empty until real `curl` transcripts exist.

## Contents
- [Conventions](#conventions)
- [Endpoints](#endpoints)
- [Request and response DTOs](#request-and-response-dtos)
- [Error model](#error-model)
- [Status-code decision table](#status-code-decision-table)
- [Idempotency](#idempotency)
- [Pagination](#pagination)
- [Versioning and compatibility](#versioning-and-compatibility)
- [Event contracts (planned)](#event-contracts-planned)
- [Verified examples](#verified-examples)
- [Currently available endpoint](#currently-available-endpoint)

## Conventions

- Base path `/api/v1`. JSON, UTF-8. Resource nouns, plural.
- IDs are UUIDs (strings). Timestamps are ISO-8601 UTC (`2026-10-29T15:47:59Z`).
- Money: integer minor units (`amountMinor`) + ISO-4217 `currency`. Never floating point.
- Request correlation: client may send `X-Request-Id`; server generates one if absent and echoes it back and in logs.
- DTOs ≠ entities. The API shape is a contract; the entity is an implementation detail ([why](class-design.md#entity-vs-domain-model)).

## Endpoints

| Method & path | Purpose | Success | Notes |
|---|---|---|---|
| `POST /api/v1/orders` | Create order | `201 Created` + `Location` | Requires `Idempotency-Key` header (Phase 2); body: `CreateOrderRequest` |
| `GET /api/v1/orders/{orderId}` | Fetch one order | `200` | `404` if absent (or not owned by caller once auth exists) |
| `GET /api/v1/orders?customerId=&page=&size=` | List a customer's orders | `200` | Newest first; bounded `size` (max 100) |
| `POST /api/v1/orders/{orderId}/cancel` | Cancel an order | `200` (returns updated order) | `409` if state forbids; idempotent when already `CANCELLED` |

`cancel` is modelled as a command sub-resource rather than `DELETE`, because the order is retained and state-transitioned, not removed.

## Request and response DTOs

**[ILLUSTRATIVE]** Java records (Java 21). They would live in `order/api/dto`.

```java
public record CreateOrderRequest(
        @NotNull UUID customerId,
        @NotEmpty @Size(max = 100) List<@Valid OrderItemRequest> items) {}

public record OrderItemRequest(
        @NotNull UUID productId,
        @Min(1) @Max(1000) int quantity,
        @Positive long unitPriceMinor,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) {}

public record OrderResponse(
        UUID orderId, UUID customerId, String status,
        long totalMinor, String currency,
        List<OrderItemResponse> items,
        Instant createdAt, Instant updatedAt, long version) {}

public record OrderItemResponse(UUID productId, int quantity, long unitPriceMinor) {}

public record PagedResponse<T>(List<T> content, int page, int size, long totalElements) {}
```

Notes: `@Valid` on the controller parameter triggers validation; `@Valid` on the list element type triggers per-item validation. Mixed currencies within an order are a **business rule** (domain), not expressible with simple annotations.

Example request **[ILLUSTRATIVE]**:

```json
{
  "customerId": "6f1c1e0a-0000-0000-0000-000000000001",
  "items": [
    { "productId": "a1b2c3d4-0000-0000-0000-000000000002", "quantity": 2, "unitPriceMinor": 1299, "currency": "USD" }
  ]
}
```

Example response **[ILLUSTRATIVE]**:

```json
{
  "orderId": "3f9d2c10-0000-0000-0000-000000000003",
  "customerId": "6f1c1e0a-0000-0000-0000-000000000001",
  "status": "CREATED",
  "totalMinor": 2598,
  "currency": "USD",
  "items": [{ "productId": "a1b2c3d4-0000-0000-0000-000000000002", "quantity": 2, "unitPriceMinor": 1299 }],
  "createdAt": "2026-10-29T15:47:59Z",
  "updatedAt": "2026-10-29T15:47:59Z",
  "version": 0
}
```

Design notes:
- `status` is a string for forward compatibility; **clients must tolerate unknown values** (new states will appear).
- Server computes `totalMinor`; a client-supplied total is not accepted (see open question 1 in [requirements](../hld/requirements.md#open-questions)).
- Do not expose DB sequence IDs or internal fields. `version` is exposed so clients can use `If-Match` later (optimistic concurrency over HTTP) — optional.

## Error model

RFC 9457 Problem Details (`application/problem+json`), supported by Spring's `ProblemDetail`. **[ILLUSTRATIVE]**:

```json
{
  "type": "https://example.invalid/problems/illegal-state-transition",
  "title": "Illegal state transition",
  "status": 409,
  "detail": "Order 3f9d... is SHIPPED and cannot be cancelled",
  "instance": "/api/v1/orders/3f9d.../cancel",
  "code": "ILLEGAL_STATE_TRANSITION",
  "requestId": "b7c1...",
  "errors": []
}
```

For validation failures (`400`), `errors` is a list of `{ "field": "items[0].quantity", "message": "must be greater than or equal to 1" }`. Rules: stable machine `code`; human `detail` safe to show; **never** include stack traces, SQL, or internal class names.

## Status-code decision table

| Situation | Code | Why |
|---|---|---|
| Malformed JSON, missing/invalid fields | 400 | Client can fix the request syntax |
| Missing/invalid auth (future) | 401 | Unauthenticated |
| Authenticated but not allowed (future) | 403 (or 404 to avoid existence leaks) | Policy decision |
| Order not found | 404 | |
| Illegal state transition; optimistic-lock conflict | 409 | Request valid, conflicts with current state |
| Same `Idempotency-Key` with a *different* payload | 422 (or 409) | Key misuse; pick one and document |
| Valid but business rule violated (e.g. total too large) | 422 | Semantic error |
| Rate limited (future) | 429 + `Retry-After` | Overload protection |
| Dependency down / overloaded | 503 + `Retry-After` | Retry later |
| Unexpected bug | 500 | With request ID only |

Tip: `201` for first creation; for an idempotent replay return the same body with `200` or `201` — **decision pending**; many APIs return the original status.

## Idempotency

- `POST /orders` accepts `Idempotency-Key: <opaque string ≤ 64 chars>` (client generates a UUID per *logical* attempt and reuses it on retry).
- Server stores `(customer_id, key, request_hash, order_id)` under a **unique constraint**, inside the same transaction as the order.
- Same key + same payload → return the original order. Same key + different payload → error. Key lifetime e.g. 24 h (**[ASSUMPTION]**).
- `GET` is naturally idempotent. `cancel` is idempotent by state (cancelling a cancelled order returns it).
- Why a header and not a payload field: it's transport metadata and works uniformly across endpoints. Crash analysis: [distributed systems handbook](../learning/distributed-systems.md#idempotency-and-duplicate-handling).

## Pagination

Phase 1: offset pagination (`page`, `size`) — simple, but deep pages are slow (`OFFSET N` scans N rows) and unstable under inserts. Planned improvement: **keyset (cursor) pagination** on `(created_at, order_id)`, matching the index in [database design](database-design.md#indexes). Always cap `size`.

## Versioning and compatibility

- URL major version (`/v1`). Within a version only **additive** changes: new optional request fields, new response fields, new enum values (clients must be tolerant readers).
- Breaking changes (rename/remove/semantic change) → `/v2` and a deprecation period.
- Servers should ignore unknown request fields only if you deliberately configure that; decide explicitly (Jackson's default behaviour changed across versions — **[UNVERIFIED]** for Boot 4's Jackson; check).

## Event contracts (planned)

**[PLANNED, Phase 4]** Envelope shared by all events:

```jsonc
{
  "eventId": "uuid",          // dedupe key for consumers
  "eventType": "OrderCreated",
  "schemaVersion": 1,
  "aggregateId": "orderId",   // Kafka key
  "aggregateVersion": 1,      // lets consumers detect out-of-order/stale
  "occurredAt": "ISO-8601",
  "traceId": "w3c-traceparent",
  "payload": { }
}
```

Compatibility rules: add optional fields only; never reuse field meanings; consumers ignore unknown fields. A schema registry (Avro/JSON Schema) is an option later. Topic design is in the [architecture doc](../hld/architecture.md#asynchronous-event-flow-order-confirmation-saga).

## Verified examples

_None yet._ After Phase 1, paste real `curl -i` transcripts here, with date and commit hash.

## Currently available endpoint

Only the Actuator health endpoint exists **[UNVERIFIED]**: `GET /actuator/health` → `{"status":"UP"}` (reported by the author; actuator dependency present in [`pom.xml`](../../pom.xml)).
