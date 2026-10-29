# Java Backend Handbook

**Last updated:** 2026-10-29

Related: [Spring Boot](spring-boot.md) · [Database fundamentals](database-fundamentals.md) · [Distributed systems](distributed-systems.md) · [Testing](testing-and-quality.md) · [Backend interview notes](../interviews/backend-interview-notes.md) · [Roadmap](../../LEARNING_ROADMAP.md)

**How to use:** every section has the same six parts: *Plain language → Why it matters in production → Example → Common mistakes → Debugging exercise → Interview questions.* All code is **[ILLUSTRATIVE]** (self-contained snippets, Java 21) — none exists in the repo unless it links to a file. Exercises are *practice prompts*; none has been completed unless recorded in the [incident log](../debugging/incident-log.md) or [mock log](../interviews/mock-interview-log.md).

## Contents
1. [Collections](#collections)
2. [Generics](#generics)
3. [Equality and hashing](#equality-and-hashing)
4. [Immutability](#immutability)
5. [Exceptions](#exceptions)
6. [Threads and executors](#threads-and-executors)
7. [Synchronization and memory visibility](#synchronization-and-memory-visibility)
8. [Concurrent collections](#concurrent-collections)
9. [JVM memory](#jvm-memory)
10. [Garbage collection](#garbage-collection)
11. [Thread dumps](#thread-dumps)
12. [Profiling](#profiling)
13. [Git, CI, code review, maintainability](#git-ci-code-review-and-maintainability)

---

## Collections

**Plain language.** Collections are Java's built-in containers. Choose by access pattern: `ArrayList` (indexed, append), `LinkedList` (rarely right), `HashMap`/`HashSet` (lookup by key, O(1) average), `TreeMap` (sorted, O(log n)), `LinkedHashMap` (insertion order; can implement LRU), `ArrayDeque` (queue/stack), `PriorityQueue` (heap), `EnumMap`/`EnumSet` (enum keys; fast and compact).

**Why it matters in production.** Wrong collection choice shows up as latency (O(n) `contains` on a `List` in a hot path), memory bloat (millions of boxed `Integer`s), subtle bugs (mutating a collection while iterating → `ConcurrentModificationException`), or data races (`HashMap` shared across threads).

**Example.**
```java
// O(n) per lookup - fine for 10 items, catastrophic for 1M
List<UUID> seen = new ArrayList<>();
if (!seen.contains(id)) seen.add(id);

// O(1) average
Set<UUID> seen = new HashSet<>();
if (seen.add(id)) { /* first time */ }

// Group order items by product
Map<UUID, Integer> qty = new HashMap<>();
for (OrderItem i : items) qty.merge(i.productId(), i.quantity(), Integer::sum);
```

**Common mistakes.** Using mutable objects as `HashMap` keys and mutating them; relying on `HashMap` iteration order; `Arrays.asList` / `List.of` being fixed-size / immutable (`UnsupportedOperationException`); returning internal collections directly; `new ArrayList<>()` growth in tight loops without capacity; `LinkedList` for "fast inserts" (cache-hostile; usually slower than `ArrayList`).

**Debugging exercise.** A method that deduplicates 200k IDs takes 40 s. Write it with `List.contains`, measure with a simple timer (then JFR), rewrite with `HashSet`, compare. Record the numbers for your machine.

**Interview questions.**
1. How does `HashMap` work internally (buckets, hash spreading, collisions, treeification at 8, resize at load factor 0.75)? What happens with a bad `hashCode`?
2. When would you choose `LinkedHashMap` or `TreeMap` over `HashMap`? How would you build an LRU cache with `LinkedHashMap`?

---

## Generics

**Plain language.** Generics let types be parameters, giving compile-time type safety without casts. Type erasure means type arguments are removed at runtime. Wildcards: `? extends T` (read-only producer), `? super T` (consumer) — *PECS: Producer Extends, Consumer Super*.

**Why it matters in production.** Generic APIs (repositories, response wrappers, event envelopes) are everywhere in Spring. Misunderstanding erasure causes unchecked-cast bugs and `ClassCastException` far from the cause; Jackson deserialization of `List<Foo>` needs `TypeReference` because of erasure.

**Example.**
```java
public record PagedResponse<T>(List<T> content, int page, int size, long total) {}

static double sum(Collection<? extends Number> xs) {      // producer
    double s = 0; for (Number n : xs) s += n.doubleValue(); return s;
}
static void fill(List<? super Integer> sink) { sink.add(1); }   // consumer
```

**Common mistakes.** Raw types (`List` without `<T>`); expecting `List<Integer>` to be a `List<Number>` (generics are invariant); `instanceof List<String>` (illegal because of erasure); heap pollution via unchecked casts; creating generic arrays.

**Debugging exercise.** Deserialize a JSON array into `List<OrderResponse>` using `objectMapper.readValue(json, List.class)` and observe `LinkedHashMap` elements causing `ClassCastException` later; fix with a `TypeReference`/`JavaType`.

**Interview questions.**
1. What is type erasure, and what limitations does it impose? 
2. Explain PECS with an example. Why is `List<Integer>` not a subtype of `List<Number>`?

---

## Equality and hashing

**Plain language.** `==` compares references; `equals` compares logical equality. Contract: reflexive, symmetric, transitive, consistent; and **equal objects must have equal `hashCode`**. Hash-based collections rely on that.

**Why it matters in production.** Broken equality produces duplicates in sets, cache misses, lost map entries, and subtle JPA bugs (entity in a `Set` then mutated; proxies vs real class comparisons).

**Example.**
```java
public record Money(long amountMinor, String currency) {}      // records: value equality for free

final class ProductKey {                                        // manual
    private final UUID id;
    ProductKey(UUID id) { this.id = Objects.requireNonNull(id); }
    @Override public boolean equals(Object o) {
        return this == o || (o instanceof ProductKey p && id.equals(p.id));
    }
    @Override public int hashCode() { return id.hashCode(); }
}
```

**Common mistakes.** Overriding `equals` without `hashCode`; including mutable fields in `hashCode` and then mutating the object inside a `HashSet`; `getClass() != o.getClass()` vs `instanceof` (matters with Hibernate proxies); comparing `Long` with `==` (cache of −128..127 makes tests pass, production fail); `BigDecimal.equals` considers scale (`2.0` ≠ `2.00`; use `compareTo`); comparing strings with `==`.

**Debugging exercise.** Create a class with `equals` but no `hashCode`, put it in a `HashSet`, show `contains` returns false for an equal instance. Then fix it. Then mutate a field used in `hashCode` after insertion and observe the entry become unreachable.

**Interview questions.**
1. State the `equals`/`hashCode` contract. What breaks if you violate it?
2. How should `equals`/`hashCode` be implemented for a JPA entity?

---

## Immutability

**Plain language.** An immutable object cannot change after construction: `final` class/fields, no setters, defensive copies of mutable inputs/outputs. Records are concise immutable carriers (shallowly: a `List` component can still be mutable unless copied).

**Why it matters in production.** Immutable objects are inherently thread-safe, safe as map keys, safe to cache and share, and easier to reason about — eliminating a whole class of concurrency bugs.

**Example.**
```java
public record OrderItem(UUID productId, int quantity, long unitPriceMinor) {
    public OrderItem { if (quantity <= 0) throw new IllegalArgumentException("quantity"); }
}
public record OrderView(UUID id, List<OrderItem> items) {
    public OrderView { items = List.copyOf(items); }   // defensive copy -> truly immutable
}
```

**Common mistakes.** Thinking `final List<X>` is immutable (only the reference is final); exposing internal arrays/collections; `Date` (mutable) fields; assuming records deep-freeze.

**Debugging exercise.** A shared `Config` object is mutated by one thread, causing another to see half-updated values. Reproduce with two threads, then make it immutable and swap atomically via `AtomicReference<Config>`.

**Interview questions.**
1. How do you make a class immutable? What about a class containing a `List`?
2. Why are immutable objects good for concurrency? Give a limitation.

---

## Exceptions

**Plain language.** Checked exceptions must be declared/handled (`IOException`); unchecked (`RuntimeException`, `Error`) need not be. Use exceptions for exceptional conditions; give them types/codes the caller can act on; preserve the cause; clean up with try-with-resources.

**Why it matters in production.** Error handling decides whether a failure becomes a clean 4xx/5xx with a correlation ID or a swallowed error and corrupted state. In Spring, **only unchecked exceptions trigger transaction rollback by default**, and swallowing an exception inside a transaction commits half-done work.

**Example.**
```java
try (var in = Files.newInputStream(path)) {         // auto-closed, suppressed exceptions preserved
    return parse(in);
} catch (IOException e) {
    throw new ConfigLoadException("cannot read " + path, e);   // keep cause
}

// Translate at boundaries; one log line at the edge
@ExceptionHandler(OrderNotFoundException.class)
ProblemDetail handle(OrderNotFoundException e) { return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage()); }
```

**Common mistakes.** `catch (Exception e) {}` (swallow); log-and-rethrow at every layer (duplicate noise); losing the cause; catching `Throwable`/`InterruptedException` without restoring the interrupt flag (`Thread.currentThread().interrupt()`); using exceptions for control flow in hot paths; returning `null` to signal failure; exception messages containing secrets/PII.

**Debugging exercise.** Write a `@Transactional` method that catches a runtime exception from a second DB write and continues. Observe what commits. Then repeat with a checked exception and see that no rollback happens by default; fix with `rollbackFor`.

**Interview questions.**
1. Checked vs unchecked: when do you choose each? What are the downsides of checked exceptions in modern Java/Spring code?
2. What happens to a transaction when a checked exception propagates out of a `@Transactional` method? What about `InterruptedException` handling?

---

## Threads and executors

**Plain language.** A thread is an independently scheduled flow of execution. Don't create raw threads per task; use an `ExecutorService` (a pool with a queue). `CompletableFuture` composes async tasks. Java 21 adds **virtual threads** (cheap, JVM-scheduled threads for blocking I/O) via `Executors.newVirtualThreadPerTaskExecutor()`.

**Why it matters in production.** Thread pools are *the* capacity and failure-isolation mechanism: unbounded queues hide overload until OOM; undersized pools cause latency; blocking calls inside the wrong pool cause starvation; Tomcat request threads are a pool too. Virtual threads help blocking-I/O scalability but do not fix DB pool limits or CPU-bound work, and can pin carrier threads inside `synchronized` blocks on some JVM versions **[verify against JDK 21 behaviour]**.

**Example.**
```java
ExecutorService pool = new ThreadPoolExecutor(
        8, 8, 0L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(100),                      // BOUNDED queue
        new ThreadPoolExecutor.CallerRunsPolicy());         // backpressure instead of OOM

Future<Integer> f = pool.submit(() -> compute());
try { f.get(2, TimeUnit.SECONDS); } catch (TimeoutException e) { f.cancel(true); }
pool.shutdown();                                            // always shut down
```

**Common mistakes.** `Executors.newFixedThreadPool` with its unbounded `LinkedBlockingQueue`; never shutting pools down; swallowing exceptions in tasks (`submit` stores them in the `Future` — nobody calls `get`); `ThreadLocal` leaks in pools; blocking `join()` on the common pool; a 200-thread pool in front of a 10-connection DB pool.

**Debugging exercise.** Submit 1M tasks to `newFixedThreadPool(4)` where each sleeps; watch heap grow (queue). Replace with the bounded pool and rejection policy; observe behaviour under load. Capture with `jcmd <pid> GC.heap_info`.

**Interview questions.**
1. Explain `ThreadPoolExecutor`'s parameters and the order in which it uses core threads, the queue, and extra threads. Why can unbounded queues be dangerous?
2. When would you use virtual threads vs a platform-thread pool? What do they not solve?

---

## Synchronization and memory visibility

**Plain language.** Two problems: **atomicity** (check-then-act sequences interleave) and **visibility** (one thread's write may not be seen by another without a happens-before edge). Tools: `synchronized`, `volatile` (visibility + ordering, not atomicity of compound ops), `java.util.concurrent.atomic`, `Lock`/`ReentrantLock`, `ReadWriteLock`, `StampedLock`, `CountDownLatch`, `Semaphore`.

**Why it matters in production.** Races are non-deterministic, rarely reproduce in dev, and corrupt data silently (lost updates, double processing). Deadlocks freeze threads. Over-synchronizing kills throughput.

**Example.**
```java
class Counter {                                  // BROKEN: lost updates
    private int n;
    void inc() { n++; }                          // read-modify-write, not atomic
}
class SafeCounter { private final AtomicInteger n = new AtomicInteger(); void inc() { n.incrementAndGet(); } }

class Lazy {                                     // double-checked locking needs volatile
    private static volatile Lazy instance;
    static Lazy get() {
        Lazy l = instance;
        if (l == null) { synchronized (Lazy.class) { l = instance; if (l == null) instance = l = new Lazy(); } }
        return l;
    }
}
```

**Common mistakes.** `volatile int` + `n++`; synchronizing on a mutable/boxed/String field; check-then-act on a `ConcurrentHashMap` (`if (!map.containsKey(k)) map.put(k, v)` → use `putIfAbsent`/`computeIfAbsent`); inconsistent lock ordering (deadlock); holding a lock while calling unknown code or doing I/O; assuming `synchronized` on the method protects static state of other instances.

**Debugging exercise.** Run 8 threads × 1M `inc()` on the unsafe counter; record the (wrong) result; fix with `AtomicInteger`, `synchronized`, and `LongAdder`; compare correctness and timing. Then create a 2-lock deadlock and diagnose it with `jstack` ([thread dumps](#thread-dumps)).

**Interview questions.**
1. What does `volatile` guarantee and not guarantee? Explain happens-before.
2. How do you prevent deadlock? How would you detect one in a running service?

---

## Concurrent collections

**Plain language.** `ConcurrentHashMap` (fine-grained concurrent map with atomic compound ops), `CopyOnWriteArrayList` (read-mostly), `BlockingQueue` implementations (`ArrayBlockingQueue`, `LinkedBlockingQueue`) for producer/consumer, `ConcurrentLinkedQueue`, `ConcurrentSkipListMap`. `Collections.synchronizedX` wrappers lock the whole collection.

**Why it matters in production.** In-memory caches, dedupe sets, work queues and rate limiters are concurrent data structures. Using plain `HashMap` concurrently can corrupt it or loop; using synchronized wrappers serializes everything.

**Example.**
```java
ConcurrentHashMap<UUID, LongAdder> perProduct = new ConcurrentHashMap<>();
perProduct.computeIfAbsent(productId, k -> new LongAdder()).increment();    // atomic init

BlockingQueue<Job> q = new ArrayBlockingQueue<>(1000);
q.put(job);                    // blocks when full -> natural backpressure
Job j = q.poll(1, TimeUnit.SECONDS);
```

**Common mistakes.** Compound operations that aren't atomic (`get` then `put`); doing slow work inside `computeIfAbsent`'s lambda (blocks bucket; never modify the map inside it); iterating `ConcurrentHashMap` and expecting a snapshot (it's weakly consistent); `size()` as an exact value under concurrency; unbounded `LinkedBlockingQueue`; storing mutable values and mutating them without sync.

**Debugging exercise.** Implement an in-memory "seen event IDs" dedupe with `HashMap` and 16 threads; find the duplicates/corruption; switch to `ConcurrentHashMap.newKeySet()` and verify with a stress test. Note this only dedupes within one JVM — explain why that is not sufficient across instances ([distributed systems](distributed-systems.md#idempotency-and-duplicate-handling)).

**Interview questions.**
1. How does `ConcurrentHashMap` achieve concurrency (Java 8+: CAS + per-bin locking)? Why is `containsKey`+`put` still unsafe?
2. Design a thread-safe bounded producer/consumer pipeline. What happens when consumers are slower than producers?

---

## JVM memory

**Plain language.** Main areas: **heap** (objects; young + old generations), **metaspace** (class metadata, native memory), **thread stacks** (per thread), **direct/off-heap buffers**, code cache. `-Xmx` caps heap only; total process memory is larger. In containers, set heap relative to the container limit (e.g. `-XX:MaxRAMPercentage=`), or the OOM-killer terminates the pod.

**Why it matters in production.** `OutOfMemoryError: Java heap space` (leak or undersizing), `Metaspace`, `unable to create native thread`, and kernel OOM kills (exit 137 in Kubernetes) are different problems with different diagnoses.

**Example.**
```bash
# Inspect a running JVM (replace <pid>)
jcmd <pid> VM.flags
jcmd <pid> GC.heap_info
jcmd <pid> GC.class_histogram | head -20
jcmd <pid> GC.heap_dump /tmp/heap.hprof      # then open in Eclipse MAT / VisualVM
# Start-up flags for diagnostics
java -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp -Xmx512m -jar app.jar
```
```java
// Classic leak: unbounded static cache
static final Map<String, byte[]> CACHE = new HashMap<>();
void handle(String k) { CACHE.put(k, new byte[1_000_000]); }
```

**Common mistakes.** Unbounded caches/maps/queues; `ThreadLocal` values never removed in pooled threads; listeners/callbacks never deregistered; loading entire result sets into memory; confusing heap with RSS; setting `-Xmx` equal to container limit (no room for metaspace/stacks/direct memory); string concatenation in loops creating garbage.

**Debugging exercise.** Run the leak above under `-Xmx128m`, watch `jcmd GC.class_histogram` growth, capture a heap dump on OOM, find the dominator in a heap analyser. Scenario catalogue entry: [FS-07](../debugging/failure-scenarios.md).

**Interview questions.**
1. Describe the JVM memory areas. What does `-Xmx` cover and not cover? Why might a pod be OOMKilled with a small heap?
2. Walk through diagnosing a suspected memory leak in production.

---

## Garbage collection

**Plain language.** GC reclaims unreachable objects. Generational hypothesis: most objects die young, so young collections are frequent/cheap and full collections rare/expensive. Collectors: **G1** (default on modern JDKs; region-based, pause-target), **ZGC / Generational ZGC** (very low pauses, large heaps), **Parallel** (throughput), **Serial** (tiny heaps).

**Why it matters in production.** GC shows up as latency spikes (stop-the-world pauses), throughput loss (GC overhead), and CPU burn when the heap is nearly full ("GC death spiral"). Allocation rate matters as much as heap size.

**Example.**
```bash
java -Xlog:gc*:file=gc.log:time,uptime,level,tags -Xmx512m -jar app.jar
# Look for: pause times, frequency, heap after GC trending up (leak) vs flat (healthy)
jcmd <pid> GC.heap_info
jstat -gcutil <pid> 1000
```

**Common mistakes.** Tuning flags before measuring; huge heaps without need; explicit `System.gc()`; ignoring allocation hot spots; reading "heap used" without "heap after full GC"; choosing a collector by folklore.

**Debugging exercise.** Allocate lots of short-lived objects in a loop with `-Xlog:gc`; then allocate long-lived ones; compare logs. Identify which pattern produces growing "after GC" heap.

**Interview questions.**
1. Explain generational GC and what triggers young vs full collections. What's the difference between G1 and ZGC trade-offs?
2. A service has p99 latency spikes every few minutes. How do you determine whether GC is the cause?

---

## Thread dumps

**Plain language.** A thread dump is a snapshot of every thread's stack and state (`RUNNABLE`, `BLOCKED`, `WAITING`, `TIMED_WAITING`) plus lock ownership. Take several, ~5–10 s apart; compare.

**Why it matters in production.** It's the fastest way to diagnose hangs, deadlocks, thread-pool starvation, DB-pool exhaustion (many threads waiting in `HikariPool.getConnection`), and CPU hot loops.

**Example.**
```bash
jcmd <pid> Thread.print > td1.txt        # or: jstack <pid>
# Spring Boot Actuator also offers /actuator/threaddump if exposed (do not expose publicly)
# Deadlocks are reported at the end: "Found one Java-level deadlock"
```
Reading tips: group by stack signature; many threads parked on the same monitor/pool ⇒ contention; `RUNNABLE` threads in native socket reads are usually idle I/O, not CPU; map hot threads to CPU with `top -H` + hex thread id (`nid=`).

**Common mistakes.** Taking one dump only; mistaking idle `RUNNABLE` socket reads for CPU use; ignoring thread names (name your pools!); not capturing dumps before a restart destroys the evidence.

**Debugging exercise.** Create a two-lock deadlock and a DB-pool exhaustion (pool size 2, requests that hold connections while sleeping). Capture dumps for both and write the diagnosis steps in the [playbook](../debugging/debugging-playbook.md) format. Scenarios: [FS-04, FS-06](../debugging/failure-scenarios.md).

**Interview questions.**
1. How do you recognise a deadlock vs thread-pool starvation vs a hot CPU loop in thread dumps?
2. Your service is up but unresponsive. What are your first five minutes?

---

## Profiling

**Plain language.** Profiling measures where CPU time/allocations/locks go. **JFR (Java Flight Recorder)** is built into the JDK, low overhead, suited to production; async-profiler gives flame graphs; VisualVM/JMC for visualisation. Micro-benchmarks need JMH (naïve loops lie because of JIT, dead-code elimination, warm-up).

**Why it matters in production.** Optimising without profiling wastes time. Typical findings: a regex compiled per call, excessive JSON serialisation, N+1 queries, lock contention, unnecessary object churn.

**Example.**
```bash
java -XX:StartFlightRecording=duration=60s,filename=rec.jfr,settings=profile -jar app.jar
jcmd <pid> JFR.start duration=60s filename=/tmp/rec.jfr
jfr print --events jdk.ExecutionSample rec.jfr | head      # or open in JDK Mission Control
```

**Common mistakes.** Profiling an unrealistic workload; benchmarking cold JVMs; optimising what's not on the profile; trusting wall-clock single runs; comparing runs on different machines or while on battery power.

**Debugging exercise.** Add an endpoint that does something silly (regex compile per call, sleep inside synchronized). Load it with a simple tool, capture a JFR, find the hot frame, fix, and record before/after **with your machine's specs**.

**Interview questions.**
1. How would you find the cause of high CPU in a production Java service without redeploying?
2. Why are hand-rolled microbenchmarks unreliable on the JVM?

---

## Git, CI, code review, and maintainability

**Plain language.** *Git:* commits are snapshots; branches are pointers; small, focused commits with intent-explaining messages. *CI:* every push builds and runs tests automatically on a clean machine. *Code review:* a second pair of eyes for correctness, design, security, and readability. *Maintainability:* code that others can change safely: clear names, small functions, tests, consistent layering.

**Why it matters in production.** Most outages originate from changes. Small reviewable PRs, fast CI, and reversible deployments reduce blast radius. This project is **not currently a git repository** ([snapshot](../README.md#verified-project-snapshot)); initialise git in Phase 0.

**Example.**
```bash
git init && git add -A && git commit -m "chore: baseline Spring Boot 4.1.2 skeleton"
git switch -c feature/order-create
git commit -m "feat(order): add create-order endpoint with validation"
git rebase -i origin/main            # tidy local history BEFORE sharing
git bisect start; git bisect bad; git bisect good <sha>   # find the commit that broke it
```
Review checklist: correctness & edge cases · tests prove behaviour · transaction/concurrency · error handling · security (authZ, input) · observability (logs/metrics) · backward compatibility (API, schema, events) · naming and simplicity · no secrets.

**Common mistakes.** Giant PRs; "fix stuff" commits; committing secrets or `target/`; force-pushing shared branches; red CI ignored; reviews that nitpick style but skip correctness; refactoring and behaviour change in one commit; commented-out code.

**Debugging exercise.** Introduce a deliberate regression in a test branch with 10 commits; use `git bisect` (optionally `git bisect run ./mvnw -q test`) to find the culprit. Note how small commits made it fast.

**Interview questions.**
1. Merge vs rebase vs squash: when do you use each, and what are the risks?
2. How do you review a PR that changes a database schema and an API at once? What would make you block it?
