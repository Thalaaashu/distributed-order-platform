# Scalability and Capacity

**Last updated:** 2026-10-29

Related: [Requirements](requirements.md) · [Architecture](architecture.md) · [ADRs](architecture-decisions.md) · [Roadmap Phase 7](../../LEARNING_ROADMAP.md#phase-7--load-testing-performance-and-reliability)

> **Every number on this page is an [ILLUSTRATIVE] example derived from [ASSUMPTION]s in [requirements](requirements.md#assumptions-and-expected-scale). Nothing has been measured.** Phase 7 will replace these with local measurements, labelled as such.

## Contents
- [Capacity estimation](#capacity-estimation)
- [Little's law and connection pools](#littles-law-and-connection-pools)
- [Scaling the stateless service tier](#scaling-the-stateless-service-tier)
- [Scaling the database](#scaling-the-database)
- [Caching](#caching)
- [Kafka partitioning and sizing](#kafka-partitioning-and-sizing)
- [Replication trade-offs](#replication-trade-offs)
- [Load balancing algorithms](#load-balancing-algorithms)
- [What changes at 10× and 100×](#what-changes-at-10-and-100)
- [Estimation technique for interviews](#estimation-technique-for-interviews)

## Capacity estimation

**Inputs [ASSUMPTION]:** 1,000,000 orders/day · 3 items/order · peak = 10× average · read:write = 20:1 · ≈ 2 KB stored per order · ≈ 6 events per order (Created, Reserved, Authorized, Confirmed, Shipped, Delivered) at ≈ 1 KB each.

| Quantity | Calculation | Result |
|---|---|---|
| Average write rate | 1,000,000 / 86,400 s | ≈ 11.6 orders/s |
| Peak write rate | 11.6 × 10 | ≈ 116 orders/s |
| Average read rate | 11.6 × 20 | ≈ 231 reads/s |
| Peak read rate | 231 × 10 | ≈ 2,300 reads/s |
| Order storage/day | 1,000,000 × 2 KB | ≈ 2 GB/day |
| Order storage/year | 2 GB × 365 | ≈ 730 GB (raw) |
| With indexes/overhead (×1.5) | 730 × 1.5 | ≈ 1.1 TB/year |
| Event volume | 1,000,000 × 6 | 6 M events/day; peak ≈ 116 × 6 ≈ 700 events/s |
| Event storage/day | 6 M × 1 KB | ≈ 6 GB/day; ×3 replicas = 18 GB/day |
| Kafka retention (7 d) | 18 GB × 7 | ≈ 126 GB cluster-wide |
| Inbound bandwidth (peak) | 116 req/s × ~2 KB | ≈ 230 KB/s — negligible |
| Outbound bandwidth (peak) | 2,300 reads/s × ~2 KB | ≈ 4.6 MB/s — small |

**Reading the table:** at this scale **bandwidth is irrelevant; the interesting constraints are DB connection count, write amplification (indexes, outbox), storage growth, and hot-key contention** — not raw QPS. A single well-tuned relational primary can plausibly serve ~100–200 transactional writes/s (the real limit depends on hardware, schema, and I/O; *to be measured*).

## Little's law and connection pools

`concurrency = arrival rate × latency` (L = λW).

Worked example **[ILLUSTRATIVE]**:
- Writes at peak: 116/s × 0.10 s DB time = **≈ 12** concurrently busy connections.
- Reads at peak: 2,300/s × 0.01 s = **≈ 23** busy connections.
- Total ≈ 35 busy connections at peak across all instances → a pool of 10 per instance × 4 instances = 40 is in the right order of magnitude.

Lessons: bigger pools rarely help (the DB has finite cores/IO; excess just queues inside the DB); if latency rises 10× (slow query), the same arrival rate needs 10× the connections → **pool exhaustion cascade** ([FS-04](../debugging/failure-scenarios.md)). Sizing and failure behaviour are discussed in [database fundamentals](../learning/database-fundamentals.md). HikariCP is Spring Boot's default pool **[UNVERIFIED for 4.1.2 — confirm in logs]**, default max size is 10.

## Scaling the stateless service tier

- Scale horizontally behind a load balancer; CPU/RPS-based autoscaling (HPA in Kubernetes).
- Requirements: stateless; idempotent writes; graceful shutdown; per-instance DB pool × instance count ≤ DB `max_connections` budget (a very common outage cause).
- Limits: the DB becomes the bottleneck first. Adding instances beyond the DB's capacity makes things worse.

## Scaling the database

Ordered by increasing complexity — **try earlier items first**:

1. **Fix queries and indexes** (cheapest, biggest wins). See [database design](../lld/database-design.md).
2. **Vertical scaling** (more CPU/RAM/IOPS).
3. **Read replicas** for read-heavy endpoints. Cost: replication lag → read-your-writes anomalies (user creates an order and immediately GETs it: route that read to primary, or use a version/token, or cache the just-written value).
4. **Archival/partitioning by time** (`created_at` ranges): keeps hot indexes small, makes dropping old data cheap. Native partitioning in PostgreSQL/MySQL.
5. **Sharding** by `customer_id` hash: every query must carry the shard key; cross-shard queries/transactions become hard; resharding is costly. Do this only when a single primary's write throughput/storage is truly exceeded (not at 116 writes/s).
6. **CQRS read models** (separate store for queries) when read shape diverges from write shape.

**Choosing a shard key for orders:** `customer_id` (customer's order list is a single-shard query; hot customers are rare) vs `order_id` (even distribution; "orders of a customer" becomes scatter-gather). Typical answer: shard by `customer_id`, keep a lookup for `order_id → shard` or embed the shard in the ID.

## Caching

| Level | What | Invalidation | Risk |
|---|---|---|---|
| Client/CDN | `Cache-Control`/`ETag` for GETs of immutable (terminal) orders | Not needed for terminal | Stale non-terminal data if misused |
| Application (Caffeine) | Small hot data per instance | TTL | Per-instance inconsistency |
| Distributed (Redis) | `order:{id}` cache-aside | Delete on state change; TTL as safety net | Stampede, stale data, extra moving part |

Patterns: **cache-aside** (default), write-through, write-behind (risky for orders). **Stampede protection:** request coalescing/locks, jittered TTL. **Negative caching** for missing IDs with short TTL (guards against enumeration). Rule: *a cache must never be the only copy of anything.* **[PLANNED/optional]**; not justified until measured.

## Kafka partitioning and sizing

- **Key = `orderId`** → ordering per order; even spread given random UUIDs.
- **Partition count** is the max parallelism of one consumer group. Rule of thumb: partitions ≥ peak consumer instances you foresee; choose with headroom (e.g. 12 → allows 12 parallel consumers) because increasing later changes key→partition mapping (breaking per-key ordering during transition).
- **Throughput check [ILLUSTRATIVE]:** peak 700 events/s ≪ a single partition's capability (often thousands to tens of thousands msgs/s — *depends on hardware, to measure*). Partitions here are for *parallelism and consumer scaling*, not raw throughput.
- **Hot keys:** a key such as `productId` for a flash sale concentrates load on one partition; see [HLD question: flash sale](../interviews/hld-questions.md).
- **Consumer lag** is the primary health signal; alert on lag growth, not absolute value.

## Replication trade-offs

| Setting | Durability | Latency/availability |
|---|---|---|
| Kafka `acks=all`, RF=3, `min.insync.replicas=2` | Survives 1 broker loss without loss | Slight latency; unavailable for writes if 2 brokers lost |
| `acks=1` | May lose acked messages if leader dies before replication | Faster |
| DB synchronous replica | RPO = 0 | Higher commit latency; availability depends on replica |
| DB asynchronous replica | RPO > 0 (lag) | Fast commits; stale reads |

## Load balancing algorithms

Round robin (simple, assumes equal cost), least connections (better for uneven request durations), consistent hashing (cache affinity; used when instances hold warm state), power-of-two-choices (good low-overhead approximation of least-loaded). For stateless Order Service, least-connections or round robin suffices.

## What changes at 10× and 100×

| Scale | Likely changes |
|---|---|
| 1× (1M orders/day) | Single primary DB, 3–4 service pods, one Kafka cluster, 12-partition topics. |
| 10× (10M/day, ~1,160 peak writes/s) | Read replicas + cache for reads; partition large tables by month; larger Kafka topics (e.g. 48 partitions); outbox relay needs batching/parallelism (or CDC like Debezium); tune pools; possibly separate read model. |
| 100× (100M/day) | Shard orders by customer; per-shard outbox/CDC; multi-cluster Kafka or tiered storage; async everything not needing immediate answer; cell-based architecture; capacity planning becomes continuous. |

## Estimation technique for interviews

1. State assumptions explicitly and round aggressively (86,400 s ≈ 10⁵ s).
2. Compute QPS (average, peak), storage/day and /year, bandwidth, and cache size (e.g. if 20% of 2 KB orders from the last 7 days are hot: 7 M orders × 20% × 2 KB ≈ 2.8 GB — fits one Redis node).
3. Identify the **first bottleneck** and say what you'd do about it.
4. Sanity-check against known capabilities, and say they must be validated by load testing.
