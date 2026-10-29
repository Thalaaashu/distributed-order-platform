# Spring Boot Handbook

**Last updated:** 2026-10-29

Related: [Java handbook](java-backend.md) · [Database fundamentals](database-fundamentals.md) · [Testing](testing-and-quality.md) · [Observability](observability-and-reliability.md) · [Order service LLD](../lld/order-service.md) · [API contracts](../lld/api-contracts.md)

**Project facts [IMPLEMENTED]:** Spring Boot **4.1.2**, Java 21; starters: `webmvc`, `data-jpa`, `validation`, `actuator`, plus `spring-boot-h2console`, runtime `h2`. Only `@SpringBootApplication` class and one property exist ([snapshot](../README.md#verified-project-snapshot)). **Boot 4 caution:** Boot 4 modularised auto-configuration and test slices; starters and package names differ from many Boot 2/3 tutorials. Always confirm imports against the 4.1.2 docs ([HELP.md](../../HELP.md) links to them). Code here is **[ILLUSTRATIVE]** and not compiled in this repo.

Each section: *Plain language → Why it matters → Example → Common mistakes → Debugging exercise → Interview questions.*

## Contents
1. [Auto-configuration and application startup](#auto-configuration-and-application-startup)
2. [Dependency injection and the IoC container](#dependency-injection-and-the-ioc-container)
3. [Bean lifecycle and scopes](#bean-lifecycle-and-scopes)
4. [Configuration and profiles](#configuration-and-profiles)
5. [REST controllers and DTOs](#rest-controllers-and-dtos)
6. [Validation](#validation)
7. [Exception handling](#exception-handling)
8. [Transactional proxies and pitfalls](#transactional-proxies-and-pitfalls)
9. [Actuator](#actuator)

---

## Auto-configuration and application startup

**Plain language.** `@SpringBootApplication` = `@Configuration` + `@ComponentScan` (from the app's package downward) + `@EnableAutoConfiguration`. Auto-configuration inspects the classpath and existing beans (`@ConditionalOnClass`, `@ConditionalOnMissingBean`) and creates sensible defaults: e.g. H2 on the classpath and no datasource URL ⇒ an embedded DataSource; Tomcat on the classpath ⇒ embedded web server. Your own bean usually *replaces* the default.

**Why it matters in production.** "Magic" becomes debuggable when you can ask *why* a bean exists. Most startup failures (missing datasource, port in use, bean ambiguity) are explained by the conditions report.

**Example.** [`DistributedOrderPlatformApplication`](../../src/main/java/com/ashish/distributed_order_platform/DistributedOrderPlatformApplication.java) **[IMPLEMENTED]** is the entire application today:
```java
@SpringBootApplication
public class DistributedOrderPlatformApplication {
    public static void main(String[] args) { SpringApplication.run(DistributedOrderPlatformApplication.class, args); }
}
```
```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--debug     # prints the conditions evaluation report
```
Important: component scanning only covers `com.ashish.distributed_order_platform` and sub-packages — so new classes must live under that package.

**Common mistakes.** Putting classes outside the base package (never scanned); excluding auto-configuration to "fix" an error instead of understanding it; fighting defaults rather than overriding properly; leaving the H2 console/`ddl-auto` defaults in a production profile.

**Debugging exercise.** Start with `--debug`; find (a) which `DataSource` was auto-configured and its JDBC URL, (b) which web server, (c) one `Negative matches` entry and why. Record in the [incident log](../debugging/incident-log.md) only if it was a real problem. This also satisfies Phase 0 learning goals.

**Interview questions.**
1. What does `@SpringBootApplication` do? How does auto-configuration decide what to create and how can you override it?
2. A bean you expected isn't being created. How do you find out why?

---

## Dependency injection and the IoC container

**Plain language.** You declare *what* a class needs (constructor parameters); the container (`ApplicationContext`) creates and wires objects (beans). Benefits: loose coupling, testability, lifecycle management. Prefer **constructor injection** (immutable, required dependencies explicit, no reflection needed in tests).

**Why it matters in production.** DI governs object lifetimes (singletons shared by all request threads ⇒ must be thread-safe) and makes replacing implementations (fake in tests, resilient wrapper in prod) trivial. Circular dependencies and ambiguous beans cause startup failures.

**Example.**
```java
@Service
class OrderService {
    private final OrderRepository repo;
    private final Clock clock;
    OrderService(OrderRepository repo, Clock clock) { this.repo = repo; this.clock = clock; } // single ctor: no @Autowired needed
}

@Configuration
class TimeConfig { @Bean Clock clock() { return Clock.systemUTC(); } }

// Two implementations -> disambiguate
@Component @Primary class SmtpNotifier implements Notifier {}
@Component class LogNotifier implements Notifier {}
// or inject by qualifier: OrderService(@Qualifier("logNotifier") Notifier n)
```

**Common mistakes.** Field injection (hides dependencies, hinders tests); storing request-specific state in singleton fields (race condition); circular dependencies "fixed" with `@Lazy` instead of redesign; `new`-ing collaborators inside beans; component-scanning everything and relying on bean-name collisions; injecting `ApplicationContext` to look things up (service locator).

**Debugging exercise.** Add two `@Component` implementations of one interface; start the app; read `NoUniqueBeanDefinitionException`. Fix three ways (`@Primary`, `@Qualifier`, inject `List<Notifier>`). Then create A↔B constructor cycle and read the error.

**Interview questions.**
1. Constructor vs field vs setter injection — trade-offs? Why is constructor injection preferred?
2. Singleton beans are shared across threads. What does that imply for fields you put in them?

---

## Bean lifecycle and scopes

**Plain language.** Order: instantiate → inject dependencies → `BeanPostProcessor`s (this is where **proxies** for `@Transactional`/`@Async` are created) → `@PostConstruct`/`InitializingBean` → ready → on shutdown `@PreDestroy`/`DisposableBean`. Scopes: `singleton` (default, one per context), `prototype`, and web scopes `request`/`session`.

**Why it matters in production.** Startup work (cache warming, connecting) belongs in lifecycle hooks; shutdown hooks release resources during graceful shutdown (important on Kubernetes rolling deploys). Proxies explain surprising behaviour of annotations.

**Example.**
```java
@Component
class PoolOwner {
    private ExecutorService pool;
    @PostConstruct void start() { pool = Executors.newFixedThreadPool(4); }
    @PreDestroy  void stop()  { pool.shutdown(); }
}
// Application-level readiness: ApplicationReadyEvent / SmartLifecycle for ordered start/stop
```
Graceful shutdown property: `server.shutdown=graceful` (+ `spring.lifecycle.timeout-per-shutdown-phase`) **[UNVERIFIED for 4.1.2; confirm]**.

**Common mistakes.** Doing heavy or failing I/O in constructors; relying on `@PostConstruct` ordering across beans; injecting a prototype bean into a singleton (you get one instance); forgetting to shut down executors; assuming `@PreDestroy` runs on `kill -9`.

**Debugging exercise.** Log in constructor, `@PostConstruct`, `@PreDestroy`; run the app, stop with Ctrl-C; confirm order. Then inject a prototype into a singleton and show only one instance is used.

**Interview questions.**
1. Describe a bean's lifecycle. Where do AOP proxies come in?
2. Singleton vs prototype vs request scope: when is each appropriate?

---

## Configuration and profiles

**Plain language.** Externalised configuration: `application.properties`/`.yaml`, environment variables, command-line args, profile-specific files (`application-prod.properties`), with documented precedence (env/CLI override files). Bind groups of properties to type-safe objects with `@ConfigurationProperties`.

**Why it matters in production.** Same artifact, different environments. Secrets must come from the environment/secret store, not source control. Wrong precedence assumptions cause "works on my machine".

**Example.** Current config **[IMPLEMENTED]**: `spring.application.name=distributed-order-platform` ([file](../../src/main/resources/application.properties)). Illustrative additions:
```properties
# [ILLUSTRATIVE] application.properties
server.port=8080
spring.datasource.hikari.maximum-pool-size=10
management.endpoints.web.exposure.include=health,info
```
```java
@ConfigurationProperties(prefix = "orders")
record OrderProperties(@DefaultValue("100") int maxPageSize, Duration idempotencyTtl) {}   // validate with @Validated
```
Environment override: `SPRING_DATASOURCE_URL=... java -jar app.jar` (relaxed binding).

**Common mistakes.** Committing credentials; one giant profile with prod flags enabled locally; magic strings via `@Value` scattered across code; assuming a missing property silently defaults safely; `ddl-auto=update` in production.

**Debugging exercise.** Set the same property in the file, an env var, and a CLI arg with different values; determine which wins; verify via `/actuator/env` (local only!) or logs.

**Interview questions.**
1. How do you manage per-environment configuration and secrets in a Spring Boot service on Kubernetes?
2. `@Value` vs `@ConfigurationProperties`: when would you pick each?

---

## REST controllers and DTOs

**Plain language.** `@RestController` maps HTTP to methods; Jackson converts JSON ↔ Java. A **DTO** is a purpose-built request/response object that decouples the public contract from internal entities.

**Why it matters in production.** The API is a long-lived contract. Exposing entities leaks internals, causes lazy-loading serialization errors, and enables mass assignment (client sets `status` or `id`).

**Example.** Full contract design: [API contracts](../lld/api-contracts.md). Sketch:
```java
@RestController @RequestMapping("/api/v1/orders")
class OrderController {
    @PostMapping ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest req) { /* ... */ }
    @GetMapping("/{id}") OrderResponse get(@PathVariable UUID id) { /* ... */ }
}
```

**Common mistakes.** Business logic in controllers; returning entities; wrong status codes (200 with an error body); using `GET` for state change; unbounded list endpoints; trusting client-supplied IDs/prices; not handling `Content-Type`/unknown properties deliberately; blocking calls without timeouts.

**Debugging exercise.** Send malformed JSON, a wrong type (`"quantity":"abc"`), and a missing required field with `curl -i`; observe the three distinct error responses; ensure all are 400 with one consistent format.

**Interview questions.**
1. Why use DTOs rather than entities in controller signatures?
2. How do you design a safe, idempotent `POST` endpoint? What status codes would you return?

---

## Validation

**Plain language.** Bean Validation (Jakarta Validation; Hibernate Validator is the implementation) annotations (`@NotNull`, `@Size`, `@Min`, `@Pattern`, `@Valid` for nested) declare constraints; Spring triggers them when `@Valid`/`@Validated` is present. On failure, `MethodArgumentNotValidException` (body) or `ConstraintViolationException` (path/query/method) is thrown.

**Why it matters in production.** Cheap, early rejection of garbage protects the domain and the DB and gives clients actionable errors. But annotations can't check cross-field or stateful rules (those belong in the domain/DB).

**Example.**
```java
record CreateOrderRequest(@NotNull UUID customerId, @NotEmpty List<@Valid OrderItemRequest> items) {}
record OrderItemRequest(@NotNull UUID productId, @Min(1) int quantity) {}
// Custom cross-field rule
@Constraint(validatedBy = SameCurrencyValidator.class) @Target(TYPE) @Retention(RUNTIME) @interface SameCurrency {...}
```
`spring-boot-starter-validation` **[IMPLEMENTED: declared in pom.xml]**; no constraints exist yet.

**Common mistakes.** Forgetting `@Valid` (silently no validation); validating only the outer object, not nested elements; relying on validation for security-critical or race-prone rules; leaking raw violation messages with internal paths; mixing primitives (`int` cannot be null → missing field becomes 0).

**Debugging exercise.** Remove `@Valid` from the controller param; show invalid data reaching the service; restore; add a nested item without `@Valid` and see it slip through.

**Interview questions.**
1. What's the difference between `@Valid` and `@Validated`? Where does each trigger?
2. Which validations belong in the DTO, the domain, and the database? Why not just one?

---

## Exception handling

**Plain language.** `@RestControllerAdvice` + `@ExceptionHandler` centralises translation of exceptions to HTTP responses. Spring supports RFC 9457 `ProblemDetail`. Unhandled exceptions become 500 via the error controller.

**Why it matters in production.** Consistent error contracts make clients simple and incidents debuggable (request ID in the body and in logs). Leaking stack traces is a security issue.

**Example.** See [class design](../lld/class-design.md#illustrative-code) for handlers and [API contracts](../lld/api-contracts.md#error-model) for the format.

**Common mistakes.** Catching `Exception` in the controller and returning 200; handler ordering ambiguity (most-specific wins); returning internal messages; not logging the 500s with the correlation ID; handling database exceptions by string-matching messages.

**Debugging exercise.** Throw a custom runtime exception from the service; observe the default 500 JSON; add a handler; add a test (`@WebMvcTest`) asserting status and body fields.

**Interview questions.**
1. How would you design a global error-handling strategy for a REST API?
2. How do you map a database unique-constraint violation to a client-friendly response without leaking internals?

---

## Transactional proxies and pitfalls

**Plain language.** `@Transactional` is implemented with a **proxy** wrapping the bean: the proxy opens a transaction, calls your method, then commits or rolls back. Defaults: rollback on unchecked exceptions and errors only; propagation `REQUIRED` (join existing or create); transactions are bound to the current thread.

**Why it matters in production.** Misplaced or ineffective transactions cause partial writes, lost atomicity, or long-held connections. These are among the most common real-world Spring bugs.

**Example.**
```java
@Service
class Svc {
    @Transactional public void a() { b(); }              // b() call bypasses the proxy
    @Transactional(propagation = Propagation.REQUIRES_NEW) public void b() {}  // NOT applied when called from a()

    @Transactional(rollbackFor = Exception.class) void checkedAware() throws Exception {}
}
```
Pitfalls checklist: self-invocation; `private`/`final`-ish methods that can't be proxied; checked exceptions not rolling back; swallowing exceptions inside the method; `@Transactional` on controllers; remote calls inside transactions; lazy loading after the transaction closes (`LazyInitializationException`); `readOnly=true` is a hint (flush mode/optimisations), not a security guarantee; `@Async`/new threads do not inherit the transaction.

**Common mistakes.** Everything above, plus wrapping a whole HTTP handler in one transaction, and expecting `REQUIRES_NEW` to share locks with the outer transaction (it can deadlock against it).

**Debugging exercise.** Enable `logging.level.org.springframework.transaction.interceptor=TRACE` (and SQL logging); call method `b()` from `a()` and prove from logs that no new transaction started. Fix by moving `b()` into another bean.

**Interview questions.**
1. Why doesn't `@Transactional` work when a method calls another method in the same class? What are the fixes?
2. What are the default rollback rules? What does `REQUIRES_NEW` do and when is it dangerous?

---

## Actuator

**Plain language.** Production-ready endpoints: `/actuator/health`, `info`, `metrics`, `prometheus` (with a registry), `threaddump`, `heapdump`, `env`, `loggers`… By default only a small subset is exposed over HTTP. Health supports liveness/readiness groups used by Kubernetes probes.

**Why it matters in production.** It's the first observability surface and the load balancer's health signal. Over-exposure (`env`, `heapdump`) leaks secrets and memory contents.

**Example.** `spring-boot-starter-actuator` **[IMPLEMENTED: declared in pom.xml]**; the author reports `/actuator/health` → `{"status":"UP"}` **[UNVERIFIED by me]**.
```properties
# [ILLUSTRATIVE]
management.endpoint.health.probes.enabled=true
management.endpoints.web.exposure.include=health,info,metrics
```

**Common mistakes.** Exposing all endpoints on the public port; health checks that call slow dependencies (cascading failures); readiness that always returns UP; liveness tied to a downstream (restart loops during an outage).

**Debugging exercise.** Add a custom `HealthIndicator` that reports DOWN when a flag file exists; observe readiness change; reason about whether liveness should be affected (it shouldn't, for dependencies).

**Interview questions.**
1. Liveness vs readiness probes: what should each check, and what goes wrong if you conflate them?
2. Which actuator endpoints are risky to expose and how do you secure them?
