# Observability and Reliability Handbook

**Last updated:** 2026-10-29

Related: [Distributed systems](distributed-systems.md#failure-recovery-and-observability) · [Debugging playbook](../debugging/debugging-playbook.md) · [Failure scenarios](../debugging/failure-scenarios.md) · [Architecture: observability](../hld/architecture.md#observability) · [Capacity](../hld/scalability-and-capacity.md) · [Roadmap Phases 6–7](../../LEARNING_ROADMAP.md#phase-6--observability-and-deliberate-debugging)

**Project facts:** `spring-boot-starter-actuator` is declared **[IMPLEMENTED]**; the author reports `/actuator/health` returns `{"status":"UP"}` **[UNVERIFIED]**. No metrics registry (e.g. Prometheus/OTel), tracing, structured logging, dashboards, or alerts exist. All of those are **[PLANNED]** (Phase 6). You have Grafana and incident-response experience; this doc maps it onto the Spring/Java stack. Code/config are **[ILLUSTRATIVE]**.

Each section: *Plain language → Why it matters → Example → Common mistakes → Debugging exercise → Interview questions.*

## Contents
1. [Logs](#logs)
2. [Metrics](#metrics)
3. [Tracing and correlation](#tracing-and-correlation)
4. [Health checks and graceful shutdown](#health-checks-and-graceful-shutdown)
5. [SLIs, SLOs, and alerting](#slis-slos-and-alerting)
6. [Load testing and performance](#load-testing-and-performance)
7. [Reliability practices](#reliability-practices)

---

## Logs

**Plain language.** Timestamped event records. **Structured** (JSON key/values) logs are searchable; **levels** (ERROR/WARN/INFO/DEBUG) express severity; **MDC** (Mapped Diagnostic Context) attaches per-request fields (requestId, orderId) to every line on that thread.

**Why it matters in production.** Logs answer "what happened to this order?" when metrics only say "something is wrong". Without correlation IDs, logs from concurrent requests are an unreadable interleave.

**Example.**
```java
MDC.put("requestId", requestId);                                  // set in a servlet filter; clear in finally
log.info("order created orderId={} customerId={} items={}", id, customerId, n);   // parameterised, no string concat
// Never: log.info("payload=" + body) with tokens/PII
```
Boot has built-in structured logging support in recent versions **[UNVERIFIED for 4.1.2; check `logging.structured.format.console`]**.

**Common mistakes.** Logging secrets/PII; logging in tight loops; log-and-throw at every layer (duplicates); ERROR for expected client errors (alert fatigue); no IDs; unbounded log volume (cost); async/MDC loss when hopping threads.

**Debugging exercise.** Add a request-ID filter, fire 20 concurrent requests, and follow one request through log lines; then break MDC propagation across an `@Async` hop and fix it.

**Interview questions.**
1. What goes into a good log line? How do you correlate logs across services?
2. How do you avoid logging sensitive data and excessive volume?

---

## Metrics

**Plain language.** Numeric time series, cheap to store and alert on. Types: **counter** (only up), **gauge** (up/down), **histogram/timer** (distribution → percentiles). Micrometer is Spring's metrics facade; backends include Prometheus/Azure Monitor. Methods: **RED** for requests (Rate, Errors, Duration), **USE** for resources (Utilisation, Saturation, Errors), and the **four golden signals** (latency, traffic, errors, saturation).

**Why it matters in production.** Metrics detect and scope problems quickly (which endpoint, since when, how bad) and drive SLOs and capacity planning. Percentiles matter: averages hide tail latency.

**Example.** Useful project metrics (planned):

| Metric | Type | Why |
|---|---|---|
| `http.server.requests` (built-in) | timer | RED per endpoint/status |
| `hikaricp.connections.active/pending` (built-in) | gauge | pool saturation |
| `jvm.gc.pause`, `jvm.memory.used` (built-in) | timer/gauge | GC & memory |
| `orders.created`, `orders.by_state` | counter/gauge | business health |
| `outbox.oldest_pending_age_seconds` | gauge | relay health |
| `kafka.consumer.lag` | gauge | async backlog |
| `orders.stuck` | gauge | reconciliation signal |
```java
Counter.builder("orders.created").tag("source","api").register(registry).increment();
```
**Cardinality warning:** never use unbounded values (orderId, userId) as metric labels — it explodes the time-series count.

**Common mistakes.** High-cardinality tags; averaging percentiles across instances (aggregate histograms instead); alerts on raw CPU; metrics that don't map to user pain; forgetting units; no dashboards-as-code.

**Debugging exercise.** Add a timer to one endpoint; generate load with and without an artificial 200 ms delay; read p50/p95/p99 and explain why the mean misleads.

**Interview questions.**
1. RED vs USE: what do you measure for an API and for a thread pool?
2. Why are percentiles preferred over averages, and why can't you average p99s?

---

## Tracing and correlation

**Plain language.** A **trace** is the path of one request across services; it's made of **spans** (timed operations) linked by parent IDs, propagated via headers (W3C `traceparent`). OpenTelemetry is the vendor-neutral standard; Micrometer Tracing bridges Spring to it.

**Why it matters in production.** Shows *where* latency or errors arise across hops (including Kafka via message headers), which logs and metrics cannot.

**Example.** Propagation points to build: HTTP in/out (automatic with instrumentation), Kafka producer→consumer headers, outbox rows (store `traceId` in the row so the relay continues the trace), `@Async` boundaries.

**Common mistakes.** Traces lost across async/queue boundaries; 100% sampling in prod (cost) or 0% (blind); span attributes with PII; no link between trace ID and logs.

**Debugging exercise.** Trace one create-order flow end to end; locate the slowest span; verify the same traceId appears in logs of every hop.

**Interview questions.**
1. How does distributed tracing propagate context across HTTP and Kafka?
2. Latency doubled after a deploy. How do you use metrics, traces, and logs together to find the cause?

---

## Health checks and graceful shutdown

**Plain language.** **Liveness** = "restart me if false" (process is wedged). **Readiness** = "send me traffic only if true" (can serve now). **Startup** probe handles slow boots. Graceful shutdown: stop accepting new requests, finish in-flight ones, release resources, then exit.

**Why it matters in production.** Rolling deploys on Kubernetes fail visibly (5xx spikes) when shutdown isn't graceful or readiness is wrong. A liveness probe that checks the DB can turn a DB outage into a restart storm.

**Example.** **[ILLUSTRATIVE]**
```properties
management.endpoint.health.probes.enabled=true
server.shutdown=graceful
spring.lifecycle.timeout-per-shutdown-phase=20s
```
Kubernetes: `readinessProbe: /actuator/health/readiness`, `livenessProbe: /actuator/health/liveness`, `terminationGracePeriodSeconds` > shutdown timeout; use a `preStop` sleep if the load balancer lags in deregistering.

**Common mistakes.** Same endpoint for liveness and readiness; heavy dependency checks in liveness; ignoring `SIGTERM`; grace period shorter than the longest request; consumers that don't stop polling before closing DB pools.

**Debugging exercise.** Run a request that takes 10 s, send SIGTERM, observe whether it completes with and without graceful shutdown.

**Interview questions.**
1. Design liveness and readiness checks for a service with a DB and Kafka dependency.
2. What happens to in-flight requests and Kafka consumption during a rolling deploy?

---

## SLIs, SLOs, and alerting

**Plain language.** **SLI** = measured indicator (e.g. fraction of `POST /orders` returning < 500 within 300 ms). **SLO** = target for it (99.9% over 30 days). **Error budget** = 1 − SLO (≈ 43 min/month at 99.9%) — spend it on change velocity; if exhausted, prioritise reliability. Alert on **burn rate** (budget consumption speed) with multiple windows, not on every blip.

**Why it matters in production.** Aligns reliability work with user impact and reduces alert fatigue. Every page should be actionable and have a runbook.

**Example.** Candidate SLOs (**[ASSUMPTION]** from [requirements](../hld/requirements.md#non-functional-requirements)): availability 99.9% for create; p95 < 300 ms; "orders stuck > 5 min" < 0.01%. Alert: page if 2% of the 30-day budget burns in 1 hour; ticket if 10% burns in 3 days (illustrative thresholds).

**Common mistakes.** SLOs nobody measures; 100% targets; alerting on causes instead of symptoms; no runbook links; ignoring async backlog SLIs (lag, outbox age).

**Debugging exercise.** Compute the error budget for 99.9% and 99.99% over 30 days; simulate an outage and compute burn.

**Interview questions.**
1. What are SLIs/SLOs/error budgets and how do they change engineering decisions?
2. Which alerts would you set for an event-driven order pipeline?

---

## Load testing and performance

**Plain language.** Generate controlled load to learn capacity and behaviour under stress: **load** (expected), **stress** (beyond limits), **spike**, **soak** (hours; finds leaks). Key ideas: use **open-model** arrival rates (requests/s regardless of response time) to avoid *coordinated omission*; measure percentiles; change one variable at a time; know your bottleneck (CPU, DB, pool, network, GC).

**Why it matters in production.** Capacity numbers on slides are guesses until measured. Load tests reveal saturation knees, pool misconfiguration, and slow queries before users do.

**Example.** Tools: k6, Gatling, JMeter, `wrk`/`hey` for quick tests. Record always: machine specs, JVM flags, DB/engine, data size, test duration, warm-up, tool/version, raw results. **Results from a laptop with H2 are local-machine numbers only** — never present them as production capacity.

**Common mistakes.** Testing on a cold JVM; load generator on the same bottlenecked machine; closed-loop tests hiding latency; empty database; looking at averages; changing several things between runs; no hypotheses.

**Debugging exercise.** Phase 7: find the max RPS at which p95 < 300 ms for `POST /orders`; then double the DB pool and show whether anything changed; explain using Little's law.

**Interview questions.**
1. How would you load test an order API and what would you look at to find the bottleneck?
2. What is coordinated omission and why does it matter?

---

## Reliability practices

**Plain language.** Practices that make failure survivable: timeouts, retries with jitter, circuit breakers, bulkheads ([distributed systems](distributed-systems.md)); graceful degradation; capacity headroom; safe deployments (canary, rollback); backups with **tested restores**; runbooks; blameless post-mortems; chaos/failure injection ([failure scenarios](../debugging/failure-scenarios.md)).

**Why it matters in production.** Most incidents come from change and unplanned interactions; reliability = preparation + fast detection + fast mitigation (rollback first, root-cause later).

**Example.** Incident response order: **detect → triage → mitigate → communicate → resolve → learn**. Mitigate before understanding (rollback, shed load, disable feature). Post-mortem format: [incident template](../debugging/debugging-playbook.md#incident-template).

**Common mistakes.** Debugging live before mitigating; no rollback plan; untested backups; blaming individuals; fixing symptoms and skipping prevention actions; no ownership of action items.

**Debugging exercise.** Pick a [failure scenario](../debugging/failure-scenarios.md), write the runbook *before* injecting the failure, inject it, and see whether the runbook was enough.

**Interview questions.**
1. Walk me through a production incident you handled (use only real experience; for this project use only logged incidents).
2. How do you decide between rolling back and rolling forward?
