# Increment 1 — Create and Get Order: what it teaches

**Date:** 2026-11-22 · Related: [ADR-0011](../hld/architecture-decisions.md#adr-0011--order-service-foundations-bigdecimal-money-feature-first-packages-problemjson-no-envelope) · [API contracts](../lld/api-contracts.md#verified-examples) · [Incident log](../debugging/incident-log.md)

Every claim below points at code or a test that exists.

## Java

- **Records for DTOs** ([`CreateOrderRequest`](../../src/main/java/com/ashish/distributed_order_platform/order/dto/CreateOrderRequest.java)): immutable, no boilerplate. Entities cannot be records because JPA needs a no-arg constructor and mutable state.
- **`BigDecimal`**: `new BigDecimal("0.10")` vs `new BigDecimal(0.10)` — the latter captures the binary-double error. `equals` compares scale (`10.0` ≠ `10.00`); `compareTo` does not. That is why `OrderItem` pads prices to scale 2 and the tests use `isEqualByComparingTo` where scale is irrelevant. See `OrderTest.totalIsExactWhereDoubleArithmeticWouldDrift`.
- **Factory method + private invariants** (`Order.create`): the only way to build an `Order`, so an invalid one cannot exist. Getters return an unmodifiable list.
- **`Clock` injection**: time becomes a dependency, so tests can fix it (`OrderServiceTest`).

## Spring Boot / JPA

- **Boot 4 package moves**: `@WebMvcTest` is now `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`; `@MockBean` is replaced by `@MockitoBean`; Jackson is 3.x.
- **Validation layers**: Bean Validation on the DTO (shape: null, range, size) vs. domain rules in `Order.create` (meaning: valid currency code). Both exist on purpose; the domain check protects non-HTTP callers.
- **`@Valid` on a list element type** (`List<@Valid OrderItemRequest>`) is what validates each item; without it, only the list itself is checked.
- **`ResponseEntityExceptionHandler`**: base class that maps Spring MVC's own exceptions to `ProblemDetail`. Extending it beats hand-writing handlers for malformed JSON and type mismatch.
- **Transaction boundary in the service**: `@Transactional` on `createOrder` makes order + items one atomic unit. `readOnly=true` on `getOrder` is a hint that lets Hibernate skip dirty checking.
- **`open-in-view=false`**: with it on, the persistence context stays open through JSON serialisation and hides lazy-loading queries. Off, mapping to a DTO inside the transaction is mandatory (it is, in `OrderResponse.from`).
- **`@EntityGraph`** on `findById` fetches `items` in one JOIN instead of a second query.
- **Assigned UUID + `@Version`**: Spring Data decides "new vs existing" from the version being `null`, so `save` calls `persist` (a plain INSERT) rather than `merge` (SELECT first).
- **Bidirectional mapping**: the `@ManyToOne` side owns the foreign key; `mappedBy` marks the inverse side. `@OrderColumn` belongs only on the owning side ([INC-002](../debugging/incident-log.md)).

## Testing

- Pyramid used: pure unit (`OrderTest`, no Spring), Mockito unit (`OrderServiceTest`), web slice (`OrderControllerTest`, `@WebMvcTest`: only MVC beans, service mocked), full stack (`OrderApiIntegrationTest`, real JPA + H2).
- A slice test proves wiring and error format cheaply; the integration test proves persistence (exact `NUMERIC` value, item rows, round-trip equality). Neither replaces the other ([INC-003](../debugging/incident-log.md)).

## Known gaps (deliberate)

Duplicate `POST`s create duplicate orders (Phase 2: idempotency keys); no pagination or cancel yet; `create-drop` schema; scale 2 assumes two-decimal currencies.
