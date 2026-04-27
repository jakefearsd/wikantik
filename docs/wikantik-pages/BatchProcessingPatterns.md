---
canonical_id: 01KQ0P44MDVRTNQYZE0ZNZ6XR8
title: Batch Processing Patterns
type: article
cluster: distributed-systems
status: active
date: '2026-04-26'
summary: Patterns for processing data in batches — chunking, parallelism, checkpointing,
  failure handling — and the choice between batch frameworks (Spring Batch, Spark)
  vs. simple loops.
tags:
- batch-processing
- chunking
- parallelism
- spring-batch
- spark
related:
- BackgroundJobProcessing
- MapReduceParadigm
- DataPipelineDesign
- IdempotencyPatterns
---
# Batch Processing Patterns

Batch processing: handle a large set of items in a controlled, repeatable way. Daily reports, ETL, bulk migrations, monthly billing. The needs differ from request-driven work: throughput matters more than latency; failures need restart; idempotency is critical.

This page covers the patterns.

## When batch is the right tool

### Periodic data work

Daily aggregations, weekly reports, monthly reconciliations. Run on a schedule.

### Bulk migrations

One-time data movement: between databases, between formats, schema upgrades.

### Large API operations

Processing millions of records that came in via streaming or upload.

### Background analysis

ML training, statistical analysis, anything compute-heavy that's not user-facing.

## When batch is wrong

### Real-time needs

User wants the result now. Batch is too slow.

### Stream processing fits

Continuous data flow. See streaming alternatives.

### Tiny scale

Hundreds of items, processable in seconds. Skip batch infrastructure; just loop.

## Core patterns

### Chunking

Don't process everything at once; process in chunks of (say) 1000 items.

```python
def process_all(items, chunk_size=1000):
    for chunk in chunked(items, chunk_size):
        process_chunk(chunk)
        commit()
```

Why:
- Memory: doesn't hold all items
- Resilience: failure in one chunk doesn't lose others
- Progress: see how far along you are

### Parallelism

Process chunks in parallel. Multiple workers; each takes a chunk.

```python
with ThreadPoolExecutor(max_workers=10) as executor:
    futures = [executor.submit(process_chunk, c) for c in chunks]
    results = [f.result() for f in futures]
```

For CPU-bound work, multiprocessing or distributed workers (Spark, Dask).

### Checkpointing

For long-running batches, save progress. On restart, resume from last checkpoint.

```python
checkpoint = load_checkpoint()
for chunk in chunks_after(checkpoint):
    process_chunk(chunk)
    save_checkpoint(chunk.id)
```

Otherwise: 4-hour job fails 3 hours in; restart from scratch.

### Idempotency

Items may be processed twice (retry, restart). The result must be the same.

Use UPSERT instead of INSERT; use deduplication keys; design idempotent operations. See [IdempotencyPatterns](IdempotencyPatterns).

### Bounded concurrency

Don't unleash unlimited workers on a database. Cap concurrency:

```python
semaphore = Semaphore(20)
def process_with_limit(item):
    with semaphore:
        process(item)
```

Or use a worker pool. Saves the database from being crushed.

## Failure handling

### Per-item retries

If processing one item fails, retry that item; continue with the rest.

### Per-chunk retries

If a chunk fails, retry the chunk. Items already processed (in earlier chunks) don't repeat.

### Skip failed items

After N retries, skip the item; record the failure; continue.

```python
failed_items = []
for item in items:
    try:
        process(item)
    except Exception as e:
        failed_items.append((item, e))
        log.warning(f"Failed: {item}")

if failed_items:
    write_failure_report(failed_items)
```

Failed items get a separate report; investigate later.

### All-or-nothing

For some operations, either all items process or none. Wrap in a transaction; rollback on failure.

Risk: large transactions can be expensive. Often the chunked approach is better, accepting partial completion.

## Frameworks

### Simple loop

For most batch needs, a Python script with chunking is fine. No framework needed.

### Spring Batch (Java)

For Java enterprise batch. Provides chunking, retries, restart, monitoring.

Heavyweight; useful for complex batch jobs.

### Apache Spark

For very large data (TB+). Distributed processing; SQL-like API.

Heavyweight; useful for analytics, ETL at scale.

### Apache Beam / Flink

Streaming-batch unified. For pipelines that handle both modes.

### Cloud-native

AWS Step Functions + Lambda; GCP Workflows; Azure Logic Apps. Coordination and execution managed by cloud.

For most batch jobs, simple scripts work. Frameworks earn their place at scale or for specific operational needs.

## Specific patterns

### Producer-consumer

Producer reads input; consumer processes. Buffered queue between them.

Decouples reading from processing; throughput limited by slowest part.

### Pipeline

Stage 1 → Stage 2 → Stage 3. Each stage processes; passes to next.

For multi-step batch transformations.

### Fan-out / fan-in

Distribute work across many workers (fan-out); collect results (fan-in).

For parallel processing where the result needs to be aggregated.

### Sliding window

For time-series batch processing. Window of recent data; slides forward.

## Operational concerns

### Monitoring

Per-batch metrics:
- Items processed
- Items failed
- Duration
- Memory usage

Without monitoring, batches that silently slow or fail are invisible.

### Alerting

Batch didn't run. Batch ran but didn't complete. Batch completed but failed many items.

Each is different signal; alarm on each.

### Resource isolation

Batch jobs can crush shared resources. Database; rate limits; CPU.

Run in dedicated environment or with rate limiting.

### Idempotency vs. transactional integrity

Long-running batches with database commits can leave partial state on failure. Strategies:
- Idempotent processing (re-run produces same final state)
- Resumability (checkpoint; restart from last good state)
- Compensation (track partial state; rollback on failure)

## Common failure patterns

- **No chunking.** Memory exhaustion or slow restart.
- **No checkpointing.** Long jobs restart from scratch.
- **Unbounded parallelism.** Database overload.
- **Per-item exceptions stop everything.** Bad item kills batch.
- **No idempotency.** Re-running causes duplicates or wrong totals.
- **No monitoring.** Failures invisible.

## Further Reading

- [BackgroundJobProcessing](BackgroundJobProcessing) — Adjacent pattern
- [MapReduceParadigm](MapReduceParadigm) — Foundational batch model
- [DataPipelineDesign](DataPipelineDesign) — Where batch fits in data engineering
- [IdempotencyPatterns](IdempotencyPatterns) — Required for batch
