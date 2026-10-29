# Testing and Quality Handbook

**Last updated:** 2026-10-29

Related: [Java handbook](java-backend.md#git-ci-code-review-and-maintainability) · [Spring Boot handbook](spring-boot.md) · [Order service test boundaries](../lld/order-service.md#unit-vs-integration-test-boundaries) · [Class design: test design](../lld/class-design.md#test-design) · [Roadmap](../../LEARNING_ROADMAP.md)

**Project facts [IMPLEMENTED]:** test starters `webmvc-test`, `data-jpa-test`, `validation-test`, `actuator-test` are declared; exactly one test exists — [`contextLoads`](../../src/test/java/com/ashish/distributed_order_platform/DistributedOrderPlatformApplicationTests.java) (`@SpringBootTest`, empty body). **Whether it passes locally is unverified**: an offline `./mvnw -o test` on 2026-10-29 failed because the Surefire plugin was not cached ([details](../README.md#verified-project-snapshot)). The exact JUnit/AssertJ/Mockito versions are managed by Boot **[UNVERIFIED]**. Boot 4 moved some test annotations/packages; check imports in the 4.1.2 docs. Code is **[ILLUSTRATIVE]**.

Each section: *Plain language → Why it matters → Example → Common mistakes → Debugging exercise → Interview questions.*

## Contents
1. [Test strategy and the pyramid](#test-strategy-and-the-pyramid)
2. [Unit tests](#unit-tests)
3. [Spring slice and integration tests](#spring-slice-and-integration-tests)
4. [Contract tests](#contract-tests)
5. [End-to-end tests](#end-to-end-tests)
6. [Testing concurrency and failure](#testing-concurrency-and-failure)
7. [Test quality: flakiness, coverage, maintainability](#test-quality-flakiness-coverage-maintainability)
8. [Quality gates in CI](#quality-gates-in-ci)

---

## Test strategy and the pyramid

**Plain language.** Many fast, isolated **unit** tests; fewer **integration** tests exercising real wiring/DB; very few **end-to-end** tests through the full deployed system. Add **contract** tests at service boundaries, and targeted **concurrency/failure** tests for correctness properties.

**Why it matters in production.** Tests are what let you change code (and dependencies like a Boot major version) safely. Wrong distribution produces either slow, flaky suites or a green build with broken integrations.

**Example (strategy for this project).**

| Layer | Examples | Tooling |
|---|---|---|
| Unit | state machine, price calc, retry delay calc | JUnit 5, AssertJ, no Spring |
| Slice | controller JSON/validation; repository queries | `@WebMvcTest`, `@DataJpaTest` |
| Integration | create order → DB → outbox row | `@SpringBootTest` |
| Contract | event envelope compatibility | schema/golden-file tests |
| E2E | start app, curl flow, later with broker | script / `TestRestTemplate`/`RestTestClient` (verify availability in Boot 4) |
| Failure/concurrency | duplicate events, racing cancels, crash-injection | latches, threads, fault hooks |

**Common mistakes.** Testing the framework; mocking everything (tests pass while production fails); asserting on implementation details; no tests on failure paths; one giant E2E test as the whole suite.

**Debugging exercise.** Take a failing integration test and bisect to the layer: reproduce as slice, then as unit. Note which layer first shows the bug.

**Interview questions.**
1. Describe your test strategy for a service with REST, a DB, and Kafka. What do you mock and what do you run for real?
2. Why can a high-coverage, all-mocks suite still miss production bugs?

---

## Unit tests

**Plain language.** Test one class's behaviour in isolation, fast (ms), deterministic, no network/DB/clock/randomness. Use fakes for collaborators; verify *outcomes* more than interactions.

**Why it matters in production.** They run on every save/commit and pinpoint failures. Domain logic (state machine, invariants) is where unit tests give the highest return.

**Example.**
```java
class OrderStatusTest {
    @ParameterizedTest
    @CsvSource({"CREATED,CANCELLED,true", "CANCELLED,CONFIRMED,false", "DELIVERED,CANCELLED,false"})
    void transitions(OrderStatus from, OrderStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }
}

class OrderServiceTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final InMemoryOrderRepository repo = new InMemoryOrderRepository();
    private final OrderService service = new OrderService(repo, clock);

    @Test void cancelTwiceIsRejectedOrIdempotent() { /* arrange via service.create, then act/assert */ }
}
```

**Common mistakes.** Time/`UUID.randomUUID()` inside logic (non-deterministic); Mockito `verify` everywhere (brittle); one test asserting ten things; test names that describe nothing; sleeping in tests.

**Debugging exercise.** Introduce a bug in `canTransitionTo` (allow SHIPPED→CREATED); confirm the parameterised matrix catches it; list what would *not* have caught it.

**Interview questions.**
1. Mock vs stub vs fake: differences and when you prefer each?
2. How do you make time-dependent or random code testable?

---

## Spring slice and integration tests

**Plain language.** Slice tests start a *partial* context: `@WebMvcTest` (controllers + MVC infrastructure; services mocked), `@DataJpaTest` (JPA + embedded DB, transactions rolled back by default). `@SpringBootTest` starts the full context (slowest; use sparingly and share contexts to benefit from caching).

**Why it matters in production.** They validate what unit tests can't: JSON binding, validation wiring, status codes, SQL, constraints, mappings, transaction behaviour.

**Example.** Boot 4 names/packages may differ from Boot 3 — **verify imports**.
```java
@WebMvcTest(OrderController.class)
class OrderControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean OrderService service;            // Boot 3.4+/Spring 6.2+ replacement for @MockBean; check Boot 4 package

    @Test void invalidBodyReturns400() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType(APPLICATION_JSON).content("{\"items\":[]}"))
           .andExpect(status().isBadRequest());
    }
}

@DataJpaTest
class OrderRepositoryTest {
    @Autowired OrderJpaRepository repo;
    @Test void listsNewestFirst() { /* save 3, assert order */ }
}
```
DB caveat: H2 ≠ PostgreSQL. For behaviours depending on locking/dialect, use the real engine when available ([ADR-0009](../hld/architecture-decisions.md#adr-0009--local-infrastructure-without-docker-open)). Without Docker, Testcontainers is unavailable.

**Common mistakes.** `@SpringBootTest` for everything (slow); `@DirtiesContext` overuse (kills context caching); relying on test-method order; `@Transactional` on tests hiding commit-time failures (constraint violations occur at flush/commit); leaking data between tests; asserting on the entity cache rather than the DB (no `flush/clear`).

**Debugging exercise.** Write a `@DataJpaTest` that violates a `NOT NULL`/`UNIQUE` constraint but passes because the failure is deferred until flush; add `flush()` to expose it. Observe the difference with `@Transactional` removed.

**Interview questions.**
1. What does `@WebMvcTest` load vs `@SpringBootTest`? When do you use each?
2. Why can a `@Transactional` test hide production bugs?

---

## Contract tests

**Plain language.** A **contract** is the agreed shape of an API or event. **Consumer-driven contract tests** (e.g. Pact, Spring Cloud Contract) let consumers declare expectations; providers verify them in CI. Lighter alternative: shared JSON Schema/golden files with compatibility checks.

**Why it matters in production.** Independent deployment is only safe if breaking changes are caught before release. For events with long retention, *old messages* must remain readable by new consumers.

**Example.** **[PLANNED, Phase 4]**
```java
// Golden-file approach: serialise a known event, compare to a checked-in file; fail on incompatible change
assertThat(json(new OrderCreated(...))).isEqualToIgnoringWhitespace(read("contracts/order-created.v1.json"));
// Backward compat: deserialize every historical sample with the current class
```

**Common mistakes.** Provider-side tests only (consumer needs unknown); contract tests duplicating integration tests; mocking the provider with assumptions never verified; breaking changes labelled "minor".

**Debugging exercise.** Rename an event field; show which test fails; then make a compatible change (add optional field) and show tests still pass.

**Interview questions.**
1. How do consumer-driven contract tests differ from integration tests and from E2E?
2. How do you evolve an event schema without breaking existing consumers?

---

## End-to-end tests

**Plain language.** Exercise the whole system as a user would: real HTTP to a running app (and later a real broker/DB), asserting externally visible outcomes (including eventual ones with polling/awaiting, e.g. Awaitility).

**Why it matters in production.** They catch wiring, configuration, and environment issues that nothing else will; but they're slow and flaky, so keep few and focused on critical journeys (create → confirm; create → cancel).

**Example.**
```java
await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
    assertThat(getOrder(id).status()).isEqualTo("CONFIRMED"));   // async: poll, never sleep
```
Smoke script (local, real): `curl -s localhost:8080/actuator/health` → expect `{"status":"UP"}` (author-reported; **verify** in Phase 0).

**Common mistakes.** `Thread.sleep` for async waits; shared mutable test environments; tests depending on each other; no cleanup; asserting on timing.

**Debugging exercise.** Make an E2E test flaky with `sleep`, run it 50 times, then fix with polling; count failures before/after (record real numbers).

**Interview questions.**
1. How do you test an eventually consistent workflow deterministically?
2. When is an E2E test the *right* tool?

---

## Testing concurrency and failure

**Plain language.** Concurrency bugs need tests designed to provoke them: many threads released simultaneously (`CountDownLatch`/`CyclicBarrier`), repeated runs, assertions on **invariants** (final count, exactly-one winner). Failure tests inject faults: kill between steps, throw from a collaborator, drop/duplicate/reorder messages.

**Why it matters in production.** These are the bugs unit tests never see and customers do. A concurrency test that passes once proves little; run it many times.

**Example.**
```java
@Test void exactlyOneCancelWins() throws Exception {
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(8);
    List<Future<Boolean>> fs = new ArrayList<>();
    for (int i = 0; i < 8; i++) fs.add(pool.submit(() -> { start.await(); return tryConfirmOrCancel(orderId); }));
    start.countDown();
    long wins = fs.stream().filter(f -> get(f)).count();
    assertThat(wins).isEqualTo(1);
    assertThat(repo.findById(orderId).orElseThrow().status()).isIn(CANCELLED, CONFIRMED);
}
```
Fault injection seam: a `FailurePoint` bean/interface (`maybeCrash("after-commit")`) enabled only in tests.

**Common mistakes.** Asserting timing instead of invariants; too few threads/iterations; shared state across test cases; non-deterministic seeds not logged; "fixing" a flaky concurrency test by adding sleeps.

**Debugging exercise.** Write a test that fails ~1 in 20 runs for the broken counter; loop it 200 times (`@RepeatedTest(200)`); fix the bug; run 2000 times.

**Interview questions.**
1. How do you test for a race condition?
2. How would you verify that your outbox survives a crash between commit and publish?

---

## Test quality: flakiness, coverage, maintainability

**Plain language.** Flaky tests (pass/fail without code change) destroy trust. Coverage measures executed lines, not verified behaviour — a smell detector for *untested* code, not proof of quality. Mutation testing (PIT) checks whether tests notice changes.

**Why it matters in production.** Teams ignore red builds if flaky. Over-coupled tests make refactoring painful.

**Example.** Policy: quarantine flaky tests with a tracking issue within a day; no `Thread.sleep`; builder/object-mother helpers for test data; one logical assertion per test; name tests by behaviour (`cancelShippedOrder_isRejected`).

**Common mistakes.** Coverage targets as a goal (tests without assertions); testing getters; ordering dependence; environment-dependent tests (timezone, locale); mutating static state.

**Debugging exercise.** Find a "test" with no assertions in a sample; show coverage counts it; add assertions; try PIT on the domain package (optional; adds a plugin).

**Interview questions.**
1. What makes a test flaky and how do you fix them systematically?
2. Is 100% coverage a good goal? What would you track instead?

---

## Quality gates in CI

**Plain language.** A **CI pipeline** builds on every push/PR in a clean environment: compile, unit+slice tests, (optionally) integration tests, static analysis, dependency vulnerability scan, packaging. Merge only on green. **[PLANNED, Phase 8]** — no CI configuration exists in the repo, and it is not a git repository yet.

**Why it matters in production.** Moves failure detection from production to PR time; provides a reproducible build.

**Example (conceptual).** `./mvnw -B verify` as the single entry point; separate fast (unit) and slow (integration) stages; cache `~/.m2`; publish test reports; fail on new critical vulnerabilities. Keep the pipeline definition in the repo (not in a UI).

**Common mistakes.** Builds that only work on one machine; skipping tests to "get it through"; slow pipelines (> 10 min) people bypass; secrets in pipeline files; deploying from developer laptops.

**Debugging exercise.** Delete `~/.m2` caches for one dependency in a scratch copy (or use a clean container/runner) and build from scratch; note anything that only worked because of local state (this repo already shows one: Surefire not cached offline).

**Interview questions.**
1. What would you put in a CI pipeline for a Spring Boot service and in what order?
2. How do you keep CI fast as the test suite grows?
