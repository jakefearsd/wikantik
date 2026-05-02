---
canonical_id: 01KQKEA11ARM1J4YGVF1BXME9R
title: Running the Judge Experiment Harness
type: runbook
cluster: agent-cookbook
audience: [agents, humans]
summary: How to invoke `bin/kg-judge-experiment.sh` (Phase 6 of the KG-extraction redesign) — sample pending `kg_proposals`, judge each through NoOp + a real comparator (`ollama` or `claude`), and read the side-by-side report before deciding whether to flip the production extractor's `--judge` default.
tags:
  - extraction
  - evaluation
  - knowledge-graph
  - runbook
  - agent-context
runbook:
  when_to_use:
    - You are about to flip the extractor's production `--judge` default from `none` to `ollama` or `claude`
    - You want to compare two judge models (e.g. `qwen3.5:9b` vs `gemma4-assist:latest`) on the same proposal queue
    - The pending `kg_proposals` queue has grown unwieldy and you want to know how aggressively a real judge would prune it
    - You are calibrating the judge prompt against a hand-labelled sample (see Pitfalls below)
  inputs:
    - The judge backend — `ollama` (cheap, local) or `claude` (gated, billed)
    - The judge model tag — defaults to `gemma4-assist:latest` for ollama (chosen 2026-05-02 over `qwen3.5:9b` after the latter timed out on 19/30 calls; see Observed model behaviour below) and `claude-haiku-4-5` for claude; pass `--judge-model` to override
    - Sample size — `--sample N` (default 100; use 25–50 for fast spot-checks, 100+ for calibration)
    - Output path — `--output reports/judge-<tag>.json` (required)
    - For `--judge claude`, the env-var name holding the API key (`--anthropic-key-env ANTHROPIC_API_KEY`) and the `-Dwikantik.kg.judge.allow_claude=true` system property (the script auto-injects this when you pass `--judge claude`)
  steps:
    - Confirm the local Tomcat is deployed (so `tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml` carries the JDBC creds) — alternatively export `PG_PASSWORD` and the script falls back to env vars
    - Run `bin/kg-judge-experiment.sh --judge ollama --sample 50 --output reports/judge.json` — the script rebuilds `wikantik-extract-cli.jar` if stale, samples `ORDER BY random() LIMIT 50` from `kg_proposals WHERE status='pending'`, and judges each row through `NoOpProposalJudge` plus the comparator (default model `gemma4-assist:latest`; pass `--judge-model` to override)
    - Read the JSON report — `noopVerdicts` is the production-default baseline (always 100% accept); `comparatorVerdicts` shows what the judge would have done; `examples` is the per-row diff
    - Inspect `comparatorVerdicts.judge_failed_accepts` — these are fail-open accepts caused by HTTP errors / timeouts / parse failures, NOT real verdicts; if this is more than ~5% of the sample the run is unreliable
    - Inspect `comparatorVerdicts.reject_reasons` — the closed enum is `ungrounded`, `redundant_with_existing_node`, `wrong_type`, `too_generic`, `weak_support`; any unknown reason code from the model collapses to `weak_support`
    - To compare two models, run the script twice with different `--judge-model` and different `--output` paths, then diff the `comparatorVerdicts.{accepted,rejected}` counts and the `reject_reasons` histogram — this is how the qwen-vs-gemma4 evaluation is done
    - Before flipping the production default, hand-label a sample of ~50 pending rows and compare the labels against the judge's verdicts — accuracy below ~80% is the design's "do not flip" threshold
  pitfalls:
    - "Tiny sample sizes (`--sample 5`) hide real variance — a single timeout looks like 20% judge_failed; run at least N=30 before drawing conclusions and N=100 before flipping a production default"
    - "Two runs see different rows (`ORDER BY random()` is not seeded), so per-row diffs across model runs are noise — compare aggregate counts and reason histograms, not individual rows"
    - "Switching ollama models between consecutive runs costs a model-load on the inference server (5–30s of latency) — do not interpret the first few timeouts after a model swap as a quality signal"
    - "`comparatorVerdicts.accepted` lumps real accepts and `judge_failed: ...` fail-open accepts together — always read `judge_failed_accepts` alongside it before reporting an accept rate"
    - "`--judge claude` is billed per-call; the cost gate (`-Dwikantik.kg.judge.allow_claude=true`) is mandatory and the script auto-injects it, but you still need a valid `ANTHROPIC_API_KEY` env var named via `--anthropic-key-env`"
    - "Using the experiment to evaluate the page extractor (rather than the judge) is a category error — the harness reads already-consolidated proposals, so extractor recall problems do not show up here"
  related_tools: []
  references:
    - AgentGradeContentDesign
    - KnowledgeExtractionFromText
---

# Running the Judge Experiment Harness

Phase 6 of the KG-extraction redesign added an opt-in proposal-judge
stage that runs after `ProposalConsolidator` and before the
`kg_proposals` upsert. The harness in this runbook lets you preview
what enabling the judge would actually do to the queue — without
flipping the production default.

## When to use this runbook

Three concrete situations:

1. The pending `kg_proposals` queue is large and you want to know how
   much a real judge would prune.
2. A new judge model has appeared (newer Qwen, newer Claude, a local
   fine-tune) and you want to compare it against the current default.
3. You are about to flip the production extractor's `--judge` flag
   from `none` to a real judge and want a calibration check first.

## Quick start

```bash
# Cheap, local — about 25–30 seconds per proposal, model-dependent.
# Default --judge-model is gemma4-assist:latest; pass --judge-model to override.
bin/kg-judge-experiment.sh \
    --judge ollama \
    --sample 30 \
    --output reports/judge-gemma.json

# Gated, billed — only after the local one looks promising.
export ANTHROPIC_API_KEY=sk-…
bin/kg-judge-experiment.sh \
    --judge claude \
    --judge-model claude-haiku-4-5 \
    --anthropic-key-env ANTHROPIC_API_KEY \
    --sample 30 \
    --output reports/judge-claude.json
```

The script:

- rebuilds `wikantik-extract-cli/target/wikantik-extract-cli.jar` if the
  jar is missing or older than any source under
  `wikantik-extract-cli/src` or
  `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/`;
- pulls JDBC creds from
  `tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml` (falls back to
  `PG_JDBC_URL` / `PG_USER` / `PG_PASSWORD`);
- forwards every other flag straight through to `JudgeExperimentCli`,
  so `--help` shows the full flag set.

## Reading the report

The JSON output has three top-level groups:

- **`noopVerdicts`** — what the production default
  (`NoOpProposalJudge`) would have done. Always `accepted=N,
  rejected=0`. This is the baseline.
- **`comparatorVerdicts`** — what the requested judge actually did.
  Counts: `accepted`, `rejected`, `rewritten`, `judge_failed_accepts`
  (the fail-open accepts caused by HTTP errors / parse failures —
  these are *not* real verdicts), and `reject_reasons` keyed by the
  closed-enum reason code.
- **`examples`** — the full per-row diff. Each row has the
  `signature`, the proposal's `kind` / `displayName` / `type` (or
  edge `source`/`target`/`predicate`), the verdicts from both judges,
  and the comparator's rationale string.

A typical accept-rate read:

```bash
jq '.comparatorVerdicts | "accept=\(.accepted) reject=\(.rejected) " +
    "judge_failed=\(.judge_failed_accepts) reasons=\(.reject_reasons)"' \
   reports/judge-qwen.json
```

## Comparing two judge models

The harness only takes one comparator per run. To compare models, run
twice with different `--judge-model` / `--output`:

```bash
bin/kg-judge-experiment.sh --judge ollama --judge-model gemma4-assist:latest \
    --sample 30 --output reports/judge-gemma.json
bin/kg-judge-experiment.sh --judge ollama --judge-model qwen3.5:9b \
    --timeout-ms 180000 \
    --sample 30 --output reports/judge-qwen.json
diff <(jq -S '.comparatorVerdicts' reports/judge-gemma.json) \
     <(jq -S '.comparatorVerdicts' reports/judge-qwen.json)
```

Two runs see *different* rows (the sample is `ORDER BY random()` and
not seeded), so per-row comparisons across models are noise — read
the aggregates and the reject-reason histogram. With `--sample 30+`,
the relative accept/reject rates are stable enough to drive a
default-flip decision; below that, variance dominates.

## Observed model behaviour (2026-05-02)

A 30-row comparison on the local `inference.jakefear.com` Ollama
endpoint at the default 60s per-call timeout produced:

| Model                   | Real accepts | Real rejects | `judge_failed` (timeouts) |
|-------------------------|-------------:|-------------:|--------------------------:|
| `qwen3.5:9b`            |            4 |            7 |                **19 / 30** |
| `gemma4-assist:latest`  |           23 |            7 |                       0 / 30 |

Same rejection rate (7/30 ≈ 23%); rationales from both models look
sound on the rejects (caught genuinely ungrounded claims like "Ralph
Gomory" and "Vision Pro" with no supporting page text). The
distinguishing factor is reliability: qwen3.5:9b consistently exceeds
60s on real proposals on this endpoint and produces unusable runs at
default settings. If you want to use it anyway, pass
`--timeout-ms 180000` (or larger) and re-test before drawing
quality conclusions.

This evidence drove the 2026-05-02 default flip in
`BootstrapExtractionCli.Args.judgeModel` and
`JudgeExperimentCli.Args.judgeModel` from `qwen3.5:9b` to
`gemma4-assist:latest`. The same-model self-judging concern (the page
extractor also uses `gemma4-assist:latest`) is a real but distant
second to a 63% timeout rate; revisit if a future Ollama deployment
makes qwen reliable at the default budget.

## Calibration before flipping the production default

Run accuracy is the bar — not run latency. Before flipping
`BootstrapExtractionCli`'s default `--judge` from `none` to a real
judge, hand-label a sample of ~50 pending rows (drawn the same way
the harness draws them) and compare the labels against the judge's
verdicts. The design's "do not flip" threshold is judge-vs-human
accuracy below ~80% on any single reject reason; the most common
failure mode at the time of writing is the judge over-rejecting
sparsely-supported but legitimate domain entities as `weak_support`.

## Pitfalls

The frontmatter `pitfalls` capture the recurring methodology
mistakes. The common one is reading
`comparatorVerdicts.accepted` without separating
`judge_failed_accepts` — fail-open accepts inflate the apparent
accept rate and hide quality problems.

## Where the code lives

- CLI entry point —
  `wikantik-extract-cli/src/main/java/com/wikantik/extractcli/JudgeExperimentCli.java`
- Shell wrapper — `bin/kg-judge-experiment.sh`
- Judge implementations —
  `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/{Ollama,Claude}ProposalJudge.java`
- Production wiring (`--judge` switch) —
  `wikantik-extract-cli/src/main/java/com/wikantik/extractcli/BootstrapExtractionCli.java`
