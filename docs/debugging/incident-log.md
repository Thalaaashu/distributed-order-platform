# Incident Log

**Last updated:** 2026-10-29

Related: [Debugging playbook & template](debugging-playbook.md#incident-template) · [Failure scenarios](failure-scenarios.md) · [Mock interview log](../interviews/mock-interview-log.md)

**Rules**
- Only **real** investigations go here: genuine bugs you hit in this project, or deliberate exercises (marked `EXERCISE`, linked to a scenario ID).
- Use the [template](debugging-playbook.md#incident-template) in full. Paste real evidence only.
- Never fabricate, back-fill, or "reconstruct" an incident. If you didn't record evidence at the time, say so in the entry.
- Do not include company-confidential incidents or data from your employer. Describe general lessons instead.
- When an entry is complete, mark the matching scenario in [failure-scenarios.md](failure-scenarios.md) as `VERIFIED` and link here.

## Index

| ID | Date | Type | Title | Status | Scenario |
|---|---|---|---|---|---|
| INC-001 | 2026-11-22 | REAL | Surefire fork dies on literal `@{argLine}` | RESOLVED | N/A |
| INC-002 | 2026-11-22 | REAL | Hibernate HHH160246: `@OrderColumn` on `mappedBy` collection | RESOLVED | N/A |
| INC-003 | 2026-11-22 | REAL | NPE in service unit test: unpersisted entity has null `@Version` | RESOLVED | N/A |

Next ID: **INC-004**

## Entries

### INC-001 — Surefire fork dies on literal `@{argLine}`

| Field | Value |
|---|---|
| **ID** | INC-001 |
| **Type** | REAL (build-hygiene change made while implementing Increment 1) |
| **Date / duration** | 2026-11-22; detected immediately by the first test run; resolved in one iteration |
| **Environment** | local, macOS, Java 21.0.13, Maven 3.10.0 via wrapper, uncommitted work on top of `9982eeb` |
| **Status** | RESOLVED |

**1. Symptoms** — Every `./mvnw test` run printed a Mockito "self-attaching" JVM warning. I configured Surefire to load Mockito as an explicit agent; the next run failed with `The forked VM terminated without properly saying goodbye`, `Tests run: 0`.

**2. Expected vs actual** — Expected: tests run without the warning. Actual: the JVM never started.

**3. Reproduction** — Set `<argLine>@{argLine} -javaagent:${org.mockito:mockito-core:jar} -Xshare:off</argLine>` in Surefire; run `./mvnw test`. Always reproducible.

**4. Evidence** — Surefire printed the exact fork command: `java '@{argLine}' '-javaagent:.../mockito-core-5.23.0.jar' '-Xshare:off' -jar ...`. The placeholder was passed to the JVM unreplaced.

**5. Hypotheses** — (a) agent path wrong: refuted, the path in the command is correct. (b) `@{argLine}` not substituted: supported, it appears verbatim.

**6. Experiments** — Removed `@{argLine}`; reran: JVM started, 25 tests ran.

**7. Root cause** — `@{...}` is Surefire late replacement of a Maven property named `argLine`. It only works if another plugin (JaCoCo is the usual one) has defined that property. None does here, so nothing was substituted. I copied a snippet that assumed JaCoCo.

**8. Fix and regression test** — Dropped the placeholder in `pom.xml`. The regression test is the build itself: the full suite runs, and the Mockito/CDS warnings no longer appear in the log.

**9. Before / after** — Before: the Mockito self-attach and CDS (`Sharing is only supported for boot loader classes`) JVM warnings appeared on every run. After: neither appears in the `./mvnw clean test` log. Not otherwise measured.

**10. Prevention** — Read the printed fork command first when the forked VM dies. If JaCoCo is added later, `@{argLine}` must be restored together with it.

### INC-002 — Hibernate HHH160246: `@OrderColumn` on a `mappedBy` collection

| Field | Value |
|---|---|
| **ID** | INC-002 |
| **Type** | REAL |
| **Date / duration** | 2026-11-22; found in the first integration-test log; resolved in one iteration |
| **Environment** | local, H2 2.4.240, Hibernate 7.4.13, Spring Boot 4.1.2 |
| **Status** | RESOLVED |

**1. Symptoms** — Context startup logged `WARN HHH160246: Association 'Order.items' is 'mappedBy' another entity and should not specify an '@OrderColumn' (use '@OrderBy' instead)`. All tests were green despite it.

**2. Expected vs actual** — Expected: no mapping warnings. Actual: a warning that the list-order column is declared on the inverse side.

**3. Reproduction** — `@OneToMany(mappedBy="order") @OrderColumn(name="line_no")`; start any Spring context. Always.

**4. Evidence** — The log line above (from `./mvnw test`).

**5. Hypotheses** — Harmless cosmetic warning vs. a real ownership problem. The message itself says the child side owns the column, so ordering would not be written reliably by the inverse side.

**6. Experiments** — Replaced it with an explicit `lineNo` column on `OrderItem`, assigned in `Order.create`, plus `@OrderBy("lineNo ASC")`; reran. The warning disappeared; `OrderApiIntegrationTest` asserts item order after a database round trip.

**7. Root cause** — In a bidirectional association the `@ManyToOne` side owns the foreign key, so only it can persist a position. `@OrderColumn` is meant for the owning side.

**8. Fix and regression test** — `Order`/`OrderItem` mapping change; `OrderApiIntegrationTest.createdOrderCanBeFetchedAndIsPersistedWithExactMoney` checks `items[0]` and `items[1]` order. Warning absence verified by log inspection, not by a test.

**9. Before / after** — Not measured.

**10. Prevention** — Treat startup WARN lines as failures. A future step could fail the build on unexpected WARN logs.

### INC-003 — NPE in service unit test: unpersisted entity has null `@Version`

| Field | Value |
|---|---|
| **ID** | INC-003 |
| **Type** | REAL |
| **Date / duration** | 2026-11-22; resolved in one iteration |
| **Environment** | local, JUnit 5 + Mockito |
| **Status** | RESOLVED |

**1. Symptoms** — `OrderServiceTest.createOrderPersists...` failed with `NullPointerException: Cannot invoke "java.lang.Long.longValue()" because the return value of "Order.getVersion()" is null`.

**2. Expected vs actual** — Expected: green. Actual: NPE during DTO mapping.

**3. Reproduction** — Mock `save` to return its argument unchanged; call `createOrder`. Always.

**4. Evidence** — The NPE message above, pointing at the unboxing of `Long version` into the record's `long version`.

**5. Hypotheses** — (a) production bug: refuted by `OrderApiIntegrationTest`, where the real `save` yields `version: 0`. (b) unrealistic fixture: supported, as `@Version` is assigned by persistence, which a mock bypasses.

**6. Experiments** — Made the mock set `version=0` as a real `save` does; the test passed.

**7. Root cause** — The mock returned an entity in a state that cannot occur in production. Mocks can hide or invent states.

**8. Fix and regression test** — Fixture change only, in `OrderServiceTest`. The integration test is the guard for the real behaviour.

**9. Before / after** — Not measured.

**10. Prevention** — Keep at least one real-persistence test per feature. The DTO contract keeps a primitive `long version` on purpose: a persisted order always has one.
