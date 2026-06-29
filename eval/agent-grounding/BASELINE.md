# Grounded-agent eval — canonical baseline

**Canonical `--samples 3` baseline run:** `runs/baseline-3s-20260629T0530Z/`
(2026-06-29, dense, N=16, post-v2.2.0 incl. the dense-backend corpus fix).

Use this as the **before** reference for future before/after comparisons. Always
compare `--samples 3` to `--samples 3` — a 1-sample run is too noisy to trust at
the per-question level (see below).

## Baseline means (correctness 0–2)

| Arm | Mean | Citation hit |
|-----|------|--------------|
| cold | 0.062 | 0% |
| grounded_bundle | 1.562 | 81% |
| grounded_mcp | 1.625 | 75% |

Grounding beats cold by **+1.50 / +1.56** — the headline "grounding is worth it"
result, stable across every run to date.

## Noise floor (why `--samples 3` and why per-arm ±0.06 is meaningless)

Two `--samples 3` runs of essentially the same system, a few hours apart:

| Run | grounded_bundle | grounded_mcp |
|-----|-----------------|--------------|
| theme-c-3s-20260628T2200Z | 1.625 | 1.562 |
| baseline-3s-20260629T0530Z | 1.562 | 1.625 |

They **swapped** which arm leads. So a ±0.06 mean difference (≈ one question of
16) is run-to-run noise, not signal. A real regression/improvement must move the
mean by more than ~0.1, or move a per-question cell consistently across runs.
**Decision rule:** only treat a change as real if it reproduces across two
`--samples 3` runs (or moves the mean >0.1).

## Stable per-question signals (reproduce across both 3-sample runs)

- **`read-path-acl` mcp ≈ 0, bundle ≈ 1** — a genuine weak spot (NOT a Theme C
  regression: the Theme-A 1-sample baseline's mcp=2 was the noisy outlier; the
  3-sample truth is ~0). The read-path / agent-surface ACL-enforcement content
  retrieves weakly for both arms. **Future improvement target** (corpus and/or
  retrieval), tracked separately — not a release blocker.
- **`dense-backend-options` bundle = 1, mcp = 2** — the corpus content is now
  correct and rank-1 retrievable (the 2026-06-29 table fix marking `lucene-hnsw`
  the docker1 production default), but the bundle arm's *answer* still scores
  partial. Content is no longer the gap; this is answer-synthesis/judge-bound.
- **`kg-predicates-count` mcp = 1, bundle = 2** — stable; content present, mcp
  answer partial.

## How to reproduce

```bash
cd eval/agent-grounding
set -a; source ../../.env; set +a            # ANTHROPIC_API_KEY + MCP_ACCESS_KEYS
python3 run_eval.py --run-id <id> --samples 3
python3 grade.py runs/<id>
python3 report.py runs/<id>
```
Targets prod (`wiki.wikantik.com`); needs the inference host up. ~30–45 min.
