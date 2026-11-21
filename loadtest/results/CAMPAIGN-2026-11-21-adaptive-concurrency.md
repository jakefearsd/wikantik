# Tuning run 2026-11-21: the adaptive query-embedding concurrency limit on docker2

Question: does the learned limit (`ThroughputGradientPolicy`, `concurrency.mode = adaptive`) do at least as well
as the hand-tuned fixed cap of 4 from 2026-11-20 without anyone measuring the hardware, and does it follow the
embedder when its capacity changes?

## Environment

- **Target:** docker2 (Ryzen 7 5800U APU, iGPU embedder `qwen3-embedding:0.6b` on Ollama, Vulkan,
  `OLLAMA_NUM_PARALLEL=4`). Fresh image from the working tree (main `52237cc493` + this change), stood up with
  `bin/dr-restore.sh docker2 --from-host docker2` from the 2026-11-21 NAS snapshot of production: 21,240 chunk
  vectors. Prod perf overlay as on 2026-11-20 (lucene-hnsw, ef_search 100, collab + work, private clusters),
  load generator exempt from the per-client rate limiter.
- **Load:** k6 on docker2. *Ramp*: the 2026-11-20 open-model probe (50 → 1,000 req/s over 5 min, 20% search,
  20% bundle, half the queries novel). *Steady*: 150 req/s for 7 min, same mix.
- **Contention:** two streams of 32-text batch embeds sent straight to Ollama from 120 s to 300 s of the steady
  run — the GPU load of a bulk re-embed.
- Raw per-run outputs: `adaptive-*/` (`windows.txt` = per-30 s latency, `limit-samples.txt` = limit, in-flight
  and embedder counters every 2 s).

## Headline

| run | dense answers | shed | timeouts | breaker opens | search p95 under load | limit |
|---|---:|---:|---:|---:|---|---|
| ramp, fixed cap 4 (`p4-fixed4`) | 4,698 | 28,158 | 0 | 0 | 230–275 ms | 4 |
| ramp, adaptive, min-gain 0.10 (`p4-gain10`) | 4,420 | 28,776 | 0 | 0 | **130–140 ms** | ≈2 (1–4) |
| ramp, adaptive, min-gain 0.05 (`p4-gain05`) | 4,465 | 28,688 | 0 | 0 | 130–145 ms | ≈2 (1–5) |
| ramp, adaptive, Ollama parallel **1** (`p1-gain10`) | 4,436 | 28,364 | 0 | 0 | 125–140 ms | ≈2 (1–5) |
| ramp, adaptive, **final build** (`p4-final`) | 4,545 | 28,557 | 0 | 0 | 130–145 ms after the first 90 s | ≈2 (1–5) |
| steady + contention, fixed 4 (`contend-fixed4`) | 3,855 | 3,910 | 18 | 6 | ~240 ms outside contention | 4 |
| steady + contention, adaptive (`contend-adaptive2`) | 3,412 | 6,391 | 22 | 5 | 130–170 ms outside contention | 2, 1 during contention |

- **The learned limit finds the real knee, and it is 2, not 4.** On this APU under HTTP load, a third parallel
  query embed buys under 5% more throughput (min-gain 0.05 and 0.10 settle in the same place). The fixed cap of
  4 — chosen because `OLLAMA_NUM_PARALLEL=4` — mostly queued two calls inside Ollama.
- **Trade:** about 6% fewer dense answers under overload for roughly half the tail wait (search p95 ~135 ms
  against ~250 ms). `concurrency.min-gain` is the dial; the default stays 0.10.
- **It follows the embedder rather than its configuration.** With `OLLAMA_NUM_PARALLEL=1` throughput was the
  same (~15–17 dense/s) and the limit again settled near 2 (one call on the GPU, one in transit), where a fixed
  cap of 4 would queue three.
- **Under GPU contention** the limit dropped to 1 within seconds and climbed back to 2 within ~30 s of the
  contention ending. Neither mode served dense answers while two batch streams owned the GPU: a single query
  waited over the 2 s timeout behind the batches, so the breaker opened and search fell back to BM25 (p95
  27 ms). That needs queries to go ahead of re-embedding (still open from 2026-11-20 Finding 1), not a limit.
- Dense throughput falls as HTTP load rises in every run (17 → 10 dense/s from 125 to 925 req/s): the iGPU
  shares the APU with the CPU serving requests. The limiter sees that as lower throughput per level.

## Finding: a cold embedder never loaded while query embeds were cancelled at the timeout (fixed)

The first contention run (`contend-adaptive`) served no dense answers for its first 300 s, before any
contention began: the breaker opened 9 times and every half-open probe timed out at exactly 2 s. The
embedder's log showed why:

    msg="client connection closed before llama-server finished loading, aborting load"
    msg="Load failed" ... error="timed out waiting for llama-server ..."

The embedder had just been recreated and its model load took over 2 s. Since 2.7.6 a timed-out query embed
cancels its HTTP exchange (so an abandoned request stops holding a queue slot), and Ollama aborts a model load
whose client disconnects — so every load was aborted, and dense search stayed off until an uncancelled request
(here, the batch embeds; in production, a save-time re-embed with its 30 s timeout) happened to load the model.
Reproduced on demand by recreating the embedder under a trickle of searches: still dark after 80 s, 9 aborted
loads.

Fix (`QueryEmbedder`): cancel a timed-out query embed only while the embedder is answering — some call
completed within the last 10 s (`WARM_WINDOW`). Under overload other calls keep completing tens of milliseconds
apart, so the queue-slot fix still applies; when nothing has answered for 10 s the call is left to finish (the
HTTP client's own timeout still bounds it). A first attempt keyed on "answered within 5 minutes" did not help —
the embedder had answered moments before it restarted. After the fix: dense search back ~15 s after the
embedder restart, breaker never opened, 4 loads aborted inside the window. Pinned by three `QueryEmbedderTest`
cases (cold call left running, warm call cancelled, restart).

## Review follow-up

An independent review found no defects; it flagged that several calls timing out in one stall each halved the
limit (8 → 4 → 2 → 1). Now a timeout halves the limit once per stall: further timeouts from calls that started
before the last cut are ignored (`EmbedConcurrencyLimiterTest.callsTimingOutInTheSameStallHalveTheLimitOnce`).
The warm-window logic moved into `EmbedderWarmth` (the PMD complexity ratchet flagged `QueryEmbedder`). The
final build (`p4-final`) re-ran the ramp — 4,545 dense answers, 97% of the fixed cap's, at about half its p95 —
and the cold-restart test (dense back by +15 s).

## Unit-test notes (what the simulation taught)

`EmbedConcurrencyLimiterSimulationTest` drives the limiter against a simulated processor-sharing GPU. Two
design corrections came out of it before any hardware run:

- A call must be filed under the concurrency it **experienced** (the time-average of calls in flight over its
  life), not the count when it started: under load a call that starts with 3 in flight soon has 4, which made
  R(3) look like R(4) and parked the limit in the hold band. The limiter integrates the in-flight count over
  time; each level keeps its true mean concurrency, and gains are normalised per whole call.
- A level must be measured before the limit leaves it, or a probe down and an exploration up chase each other
  (1 ↔ 2 every few milliseconds).
- With random arrivals a limit of exactly the embedder's parallelism leaves a slot idle between a completion
  and the next arrival, so parallelism + 1 can genuinely pay a few percent.
