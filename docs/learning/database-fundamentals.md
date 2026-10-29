# Database Fundamentals Handbook

**Last updated:** 2026-10-29

Related: [Database design (project)](../lld/database-design.md) · [Spring Boot handbook](spring-boot.md) · [Distributed systems](distributed-systems.md) · [Failure scenarios](../debugging/failure-scenarios.md) · [Backend interview notes](../interviews/backend-interview-notes.md)

**Project facts:** `spring-boot-starter-data-jpa` (Hibernate ORM, version **[UNVERIFIED]**: local Maven cache shows 7.4.13.Final) and runtime `h2` are declared **[IMPLEMENTED]**. No entities, repositories, or schema exist. SQL/Java here is **[ILLUSTRATIVE]**. H2 differs from PostgreSQL/MySQL in locking/isolation details — see [fidelity note](../lld/database-design.md#h2-vs-postgresql-fidelity).

Each section: *Plain language → Why it matters → Example → Common mistakes → Debugging exercise → Interview questions.*

## Contents
1. [JPA and Hibernate basics: the persistence context](#jpa-and-hibernate-basics-the-persistence-context)
2. [Transactions and ACID](#transactions-and-acid)
3. [Lazy loading](#lazy-loading)
4. [The N+1 query problem](#the-n1-query-problem)
5. [SQL indexes](#sql-indexes)
6. [Query plans](#query-plans)
7. [Isolation levels and anomalies](#isolation-levels-and-anomalies)
8. [Locks: optimistic and pessimistic](#locks-optimistic-and-pessimistic)
9. [Deadlocks](#deadlocks)
10. [Connection pools](#connection-pools)

---

## JPA and Hibernate basics: the persistence context

**Plain language.** JPA is the specification; Hibernate is the implementation. An `EntityManager` holds a **persistence context** (first-level cache + identity map + change tracker) for a transaction. Managed entities are *dirty-checked*: changes are written automatically at **flush** (before commit or before a query). `save()` on an already-managed entity is often redundant.

**Why it matters in production.** Implicit flushes, unexpected UPDATEs, stale reads within a transaction, and memory growth in big batch jobs (persistence context grows without clearing) all come from not understanding this model.

**Example.**
```java
@Transactional
void rename(UUID id) {
    Order o = repo.findById(id).orElseThrow();   // managed
    o.cancel(clock.instant());                   // no save() needed: dirty checking issues the UPDATE on commit
}
```
Enable SQL visibility locally: `spring.jpa.show-sql=true` (or the `org.hibernate.SQL` logger at DEBUG; `org.hibernate.orm.jdbc.bind` for parameters — logger names vary by version, verify).

**Common mistakes.** Treating entities as plain DTOs; modifying entities outside a transaction (nothing saved); bulk loading 1M entities into one context; relying on `ddl-auto=update`; `EnumType.ORDINAL`; assuming `findById` hits the DB every time (it can return from the context cache); using `@Entity` classes with `equals` based on mutable fields.

**Debugging exercise.** In a test, load an entity twice in one transaction and count SQL statements; then modify a field without calling `save` and observe the UPDATE. Predict before running.

**Interview questions.**
1. What is the persistence context? What is dirty checking and when does a flush happen?
2. `save()` vs `saveAndFlush()` vs `persist()` vs `merge()`: what differs?

---

## Transactions and ACID

**Plain language.** A transaction groups operations into a unit that is **A**tomic (all or nothing), **C**onsistent (constraints hold), **I**solated (concurrent transactions don't see each other's partial work, to a degree set by the isolation level), **D**urable (committed data survives crashes — via the write-ahead log).

**Why it matters in production.** Transaction scope defines correctness (what must succeed together) and performance (how long locks/connections are held). It also defines the **commit point** — the moment a crash stops being able to undo a decision. See the commit-point analysis in the [distributed systems handbook](distributed-systems.md#crash-scenario-table).

**Example.**
```java
@Transactional
public Order create(CreateOrderCommand c) {
    Order o = orders.save(new Order(...));
    outbox.save(OutboxEvent.of("OrderCreated", o));     // same tx: both or neither  [PLANNED, Phase 5]
    return o;
}   // commit happens when the method returns (after the proxy); response is built after commit
```

**Common mistakes.** Too-large transactions (HTTP calls inside); too-small (two related writes in separate transactions); assuming commit == message published; ignoring that an exception *after* commit (e.g. while building the response) doesn't undo it; catching exceptions inside transactions.

**Debugging exercise.** Write a method that saves an order then throws an unchecked exception; check the row doesn't exist. Then throw a checked exception and observe the row exists (default rules).

**Interview questions.**
1. Explain ACID with an example where each property matters in an order system.
2. Where is the commit point in "create order and publish event," and what can go wrong on each side of it?

---

## Lazy loading

**Plain language.** Associations marked `LAZY` aren't loaded until accessed; Hibernate substitutes a **proxy** or a lazy collection. Access outside an open session throws `LazyInitializationException`. `@OneToMany` is lazy by default; `@ManyToOne`/`@OneToOne` are **eager** by default in JPA (set to `LAZY` deliberately).

**Why it matters in production.** Lazy loading avoids loading the world, but accessing it inside loops causes N+1 queries; serialising entities directly to JSON triggers loading (or exceptions) at the worst time.

**Example.**
```java
@OneToMany(mappedBy = "order", fetch = FetchType.LAZY) List<OrderItem> items;

@Transactional(readOnly = true)
OrderResponse get(UUID id) {
    Order o = repo.findById(id).orElseThrow();
    return mapper.toResponse(o);        // touch items INSIDE the transaction/session
}
```
Fixes: fetch-join query (`select o from Order o join fetch o.items where o.id=:id`), `@EntityGraph`, projections (DTO queries), batch fetching. **Avoid** "Open Session in View" as a crutch (Boot enables `spring.jpa.open-in-view` by default and warns; it keeps a connection through view rendering — decide deliberately).

**Common mistakes.** Disabling laziness globally (`EAGER` everywhere); catching `LazyInitializationException` with a try/catch; `toString()`/Lombok `@Data` on entities that traverses lazy relations; returning entities from controllers.

**Debugging exercise.** Remove `@Transactional` from the read method and trigger `LazyInitializationException`; fix by mapping inside the transaction; then fix again using a fetch join and compare SQL.

**Interview questions.**
1. Why does `LazyInitializationException` occur, and what are good and bad ways to fix it?
2. What are the defaults for fetch types per association type, and why are they risky?

---

## The N+1 query problem

**Plain language.** One query to fetch N parents + N queries (one per parent) to fetch each parent's children. 1 + N round-trips instead of 1–2.

**Why it matters in production.** Fast with 5 rows in dev, catastrophic with 500 in prod: latency grows linearly, DB CPU and pool pressure spike. A classic cause of connection-pool exhaustion and slow-endpoint incidents ([FS-03, FS-04](../debugging/failure-scenarios.md)).

**Example.**
```java
List<Order> orders = repo.findByCustomerId(cid);                 // 1 query
orders.forEach(o -> total += o.getItems().size());               // N queries (lazy)

// Fixes
@Query("select distinct o from Order o join fetch o.items where o.customerId = :cid")   // 1 query (beware paging + collection fetch join -> in-memory paging warning)
@EntityGraph(attributePaths = "items") List<Order> findByCustomerId(UUID cid);
@BatchSize(size = 50)  // or hibernate.default_batch_fetch_size: IN (...) loading, N/50 queries
```
Detect: SQL logging, Hibernate statistics (`hibernate.generate_statistics`), a **test that asserts query count**, APM slow-endpoint traces showing repeated identical queries.

**Common mistakes.** "Fixing" by making everything eager (cartesian products, over-fetching); fetch-joining multiple collections (`MultipleBagFetchException`, row explosion); paging with collection fetch joins; relying on `show-sql` only in dev and never testing counts.

**Debugging exercise.** Seed 100 orders × 5 items in a `@DataJpaTest`, load them and count statements via Hibernate statistics; observe 101; fix with fetch join or batch fetching; add a regression test that asserts ≤ 2 statements.

**Interview questions.**
1. What is the N+1 problem and how do you detect and fix it in Hibernate?
2. Why is paging combined with a collection `join fetch` problematic?

---

## SQL indexes

**Plain language.** An index is a sorted structure (usually a B-tree) letting the DB find rows without scanning the table. Composite index `(a, b, c)` serves filters on `a`, `a,b`, `a,b,c` (leftmost prefix) and can serve `ORDER BY` in the same order. Covering indexes include all needed columns (index-only scan). Partial indexes cover a subset (`WHERE status = 'PENDING'`).

**Why it matters in production.** Missing index → full scans → latency and lock pressure grow with data. Too many indexes → slow writes, bloat. Choice of index follows **query patterns**, not columns.

**Example.** Project proposal in [database design](../lld/database-design.md#indexes):
```sql
CREATE INDEX orders_customer_created_idx ON orders (customer_id, created_at DESC, id DESC);
-- serves: WHERE customer_id = ? ORDER BY created_at DESC LIMIT 20
```

**Common mistakes.** Indexing every column; wrong column order; functions on indexed columns (`WHERE lower(email)=...` needs a functional index); implicit casts (`varchar` vs `int` param) defeating indexes; low-selectivity indexes (boolean); `LIKE '%x'` leading wildcard; forgetting FK indexes; `OFFSET` pagination on large tables; not considering write amplification.

**Debugging exercise.** Insert 1M rows (generate with SQL/`generate_series` on PostgreSQL), time the list query without the index, add the index, re-time, and capture both `EXPLAIN ANALYZE` outputs in your notes **with the DB name/version**. (H2 plans/timings are not representative.)

**Interview questions.**
1. Given `WHERE customer_id=? AND status=? ORDER BY created_at DESC`, design the index and justify column order.
2. Why can adding an index slow a system down? When does the planner ignore an index?

---

## Query plans

**Plain language.** `EXPLAIN` shows the planner's chosen strategy (seq scan, index scan, index-only scan, bitmap scan; nested loop, hash, merge joins) with *estimated* costs/rows; `EXPLAIN ANALYZE` executes and shows *actual* rows/time. Large gaps between estimated and actual rows indicate stale statistics (`ANALYZE`) or correlation problems.

**Why it matters in production.** It is how you move from "the query is slow" to "it does a sequential scan of 40M rows because the filter column isn't indexed" with evidence.

**Example (PostgreSQL syntax, illustrative).**
```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM orders WHERE customer_id = '...' ORDER BY created_at DESC LIMIT 20;
-- Look for: Seq Scan on large tables; Rows Removed by Filter (huge); Sort (external merge/disk);
-- estimated vs actual rows; Buffers: shared read (disk) vs hit (cache); Index Scan / Index Only Scan
```

**Common mistakes.** Running `EXPLAIN ANALYZE` on writes in production without a rollback; judging plans on tiny dev data; ignoring estimates vs actuals; adding an index without re-checking the plan; optimising the wrong query (find it via `pg_stat_statements`/slow-query log).

**Debugging exercise.** Take the list query with and without the index and explain every plan node in your own words. Then deliberately create stale statistics (bulk insert, no `ANALYZE`) and observe a bad estimate.

**Interview questions.**
1. How do you investigate a slow SQL query end to end?
2. Why might the planner choose a sequential scan even if an index exists?

---

## Isolation levels and anomalies

**Plain language.** Isolation controls what concurrent transactions can see:

| Level | Dirty read | Non-repeatable read | Phantom | Lost update / write skew |
|---|---|---|---|---|
| Read uncommitted | possible* | possible | possible | possible |
| **Read committed** (PostgreSQL default) | no | possible | possible | lost update possible; write skew possible |
| **Repeatable read** (MySQL InnoDB default; PostgreSQL = snapshot isolation) | no | no | engine-dependent | write skew possible in snapshot isolation; PG detects some lost updates (error) |
| Serializable | no | no | no | no (may abort with serialization failure → retry) |

*PostgreSQL treats read uncommitted as read committed. Behaviours differ by engine — **verify for your database**.

**Why it matters in production.** Anomalies are the source of "impossible" data: two cancellations both succeed, stock goes negative, a balance is overwritten. Choosing higher isolation trades throughput/retries for simplicity; choosing lower requires explicit locks/versions.

**Example.**
```java
// Lost update under READ COMMITTED if two threads do read-modify-write in Java:
Stock s = repo.find(sku); s.setQty(s.getQty() - 1); repo.save(s);
// Safer: atomic conditional update
@Modifying @Query("update Stock s set s.qty = s.qty - :n where s.sku = :sku and s.qty >= :n")
int reserve(@Param("sku") String sku, @Param("n") int n);     // 0 => insufficient stock
```
Spring: `@Transactional(isolation = Isolation.SERIALIZABLE)`; retries must wrap the transaction, outside it.

**Common mistakes.** Assuming `@Transactional` alone prevents lost updates; assuming isolation names mean the same in every DB; read-then-write without version/lock; testing concurrency only on single-threaded unit tests; using SERIALIZABLE without retry logic.

**Debugging exercise.** [FS-01](../debugging/failure-scenarios.md): two threads decrement stock 100× each from 1000 using read-modify-write; observe final > 800; fix via optimistic version, pessimistic lock, and atomic update; compare.

**Interview questions.**
1. Explain the anomalies each isolation level prevents. What is write skew? Give an order-system example.
2. How would you prevent overselling the last item without serializing the whole system?

---

## Locks: optimistic and pessimistic

**Plain language.** **Optimistic:** assume no conflict; detect at write time with a `version` column; loser retries (`@Version`). **Pessimistic:** lock rows up front (`SELECT … FOR UPDATE`, JPA `@Lock(PESSIMISTIC_WRITE)`); others wait. Also `FOR UPDATE SKIP LOCKED` for work queues, advisory locks.

**Why it matters in production.** Optimistic suits low contention (order edits); pessimistic or atomic updates suit hot rows (stock). Wrong choice → livelock (optimistic storms) or blocking pile-ups (long pessimistic locks).

**Example.**
```java
@Version private long version;                           // optimistic
@Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from Stock s where s.sku=:sku")
Optional<Stock> lockBySku(@Param("sku") String sku);     // pessimistic; keep the transaction short
// Work-queue claiming
// SELECT id FROM outbox_events WHERE published_at IS NULL ORDER BY created_at LIMIT 100 FOR UPDATE SKIP LOCKED
```

**Common mistakes.** Not handling `OptimisticLockException` (500 instead of 409/retry); lock timeouts unset; holding row locks across network calls; locking in inconsistent order; thinking application-level `synchronized` protects against other instances.

**Debugging exercise.** Two threads update the same versioned order; confirm one gets `ObjectOptimisticLockingFailureException`; implement a bounded retry (≤ 3, jittered); show that unbounded retry under contention degrades.

**Interview questions.**
1. Optimistic vs pessimistic locking: when each, and what are failure modes?
2. How do multiple relay/worker instances safely claim rows from a table?

---

## Deadlocks

**Plain language.** Two transactions each hold a lock the other needs. The DB detects the cycle and aborts one (the *victim*) with a deadlock error; the other proceeds.

**Why it matters in production.** Intermittent 500s under load; retried failures; hard to reproduce. Related: lock waits and timeouts.

**Example.**
```text
Tx1: lock row A -> wants B          Tx2: lock row B -> wants A      => deadlock
Fix: always lock in the same order (e.g. sort productIds before reserving), keep transactions short,
     use a single atomic statement where possible, retry the victim (transaction-level retry).
```
Diagnose: DB deadlock logs (PostgreSQL `log_lock_waits`, `deadlock_timeout`; MySQL `SHOW ENGINE INNODB STATUS`), plus app thread dump showing threads waiting in JDBC calls.

**Common mistakes.** Treating deadlock as a rare "DB bug"; no retry; long transactions; inconsistent update order across code paths; secondary-index/gap-lock surprises in MySQL.

**Debugging exercise.** [FS-06](../debugging/failure-scenarios.md): reserve items (A,B) and (B,A) concurrently; capture the DB error; fix by sorting; prove with a repeated stress test (not a single run).

**Interview questions.**
1. What causes deadlocks in a database and how do you prevent and handle them?
2. How does a DB deadlock differ from a JVM thread deadlock, and how do you see each?

---

## Connection pools

**Plain language.** Opening a DB connection is expensive, so apps borrow from a pool (HikariCP is Spring Boot's usual default **[UNVERIFIED for 4.1.2]**; default max pool size 10). When all connections are in use, new requests wait up to `connectionTimeout` (default 30 s) then fail.

**Why it matters in production.** Pool exhaustion is a top cause of cascading failures: slow query → connections held longer → pool empties → all endpoints (even cheap ones) time out. Sizing: Little's law ([capacity](../hld/scalability-and-capacity.md#littles-law-and-connection-pools)); total across instances must fit DB `max_connections`.

**Example.**
```properties
# [ILLUSTRATIVE]
spring.datasource.hikari.maximum-pool-size=10
spring.datasource.hikari.connection-timeout=2000     # fail fast instead of queuing 30s
spring.datasource.hikari.leak-detection-threshold=10000
```

**Common mistakes.** Making the pool huge to "fix" timeouts; 30 s connection timeout (threads pile up); holding connections during remote calls; OSIV keeping connections open through serialization; ignoring pool metrics (`hikaricp.connections.active/pending`).

**Debugging exercise.** [FS-04](../debugging/failure-scenarios.md): set pool size 2, add a `Thread.sleep(5000)` inside a transaction, fire 10 concurrent requests, read the errors and a thread dump, then fix the cause (not by raising the pool).

**Interview questions.**
1. Users report timeouts on all endpoints though CPU is low. Walk through how you'd diagnose connection-pool exhaustion.
2. How do you decide on a pool size?
