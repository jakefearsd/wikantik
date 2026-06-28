# Grounded-agent eval — `eval/agent-grounding/`

Measures whether retrieval-augmented grounding helps Claude answer questions about Wikantik internals
compared to a cold (no-context) baseline.

## What it measures

**Three arms** run for every question:

| Arm | How it answers |
|-----|----------------|
| `cold` | Raw Claude call — no wiki context, no tools. |
| `grounded_bundle` | Bundle-injected: the wiki's `/api/bundle?q=…` endpoint is called first; the ranked, de-duplicated section context is prepended to the user turn. |
| `grounded_mcp` | Tool-augmented: Claude is given the full live tool list from `/knowledge-mcp` and runs an agentic tool-use loop (max 6 iterations). |

**Two signals per answer:**

- **Correctness (0–2):** scored by a blind LLM judge (claude-sonnet-4-6) against a hand-written reference answer. 2 = fully correct, 1 = partial/vague, 0 = wrong or hallucinated.
- **Citation hit:** programmatic check — does the answer cite at least one page listed in the question's `expect_sources`?

**Question set:** 16 questions on Wikantik internals (architecture, retrieval, security, rendering pipeline, Knowledge Graph, Page Graph, module layout, configuration).

**Dense-vs-BM25 comparison (`--lexical`):** the `--lexical` flag is accepted but is currently a **no-op**. Neither `/api/bundle` nor the `/knowledge-mcp` tools expose a lexical toggle in their public interface. The flag is reserved for future use once the bundle API surface gains an explicit BM25-only mode.

## Prerequisites

| Requirement | Notes |
|-------------|-------|
| Python 3.9+ | Stdlib only — no pip install needed |
| `ANTHROPIC_API_KEY` | Needed for the agent model and the judge |
| `MCP_ACCESS_KEYS` | Bearer key for `/knowledge-mcp`; space- or comma-separated; first token is used |
| Wikantik running at `https://wiki.wikantik.com` | Dense retrieval must be live for `grounded_bundle`; `/knowledge-mcp` must be reachable for `grounded_mcp` |

Source both keys from the repo `.env` (or `.env.prod` for production):

```bash
source <(grep -v '^#' ../../.env | sed 's/^/export /')
```

## Run

```bash
cd eval/agent-grounding

# Source secrets
source <(grep -v '^#' ../../.env | sed 's/^/export /')

# Pick a run ID (timestamp recommended for uniqueness)
RUN=$(date -u +%Y%m%dT%H%M%SZ)

# Step 1 — run all three arms against the live wiki
python3 run_eval.py --run-id "$RUN"

# Step 2 — grade answers with the LLM judge
python3 grade.py runs/$RUN

# Step 3 — render the scorecard and interface-findings report
python3 report.py runs/$RUN
```

Outputs land in `runs/<run-id>/`:

| File | Contents |
|------|----------|
| `raw.json` | Every arm/question row with the raw answer text and tool-call log |
| `graded.json` | Same rows + `correctness` (0–2), `rationale`, and `citation_hit` |
| `scorecard.md` | Per-arm summary table + cold→grounded delta + per-question breakdown |
| `interface-findings.md` | Tool usage histogram, cases where tool-use lost to the canned bundle, no-tool answers, loop detection, judge-flagged vague/hallucinated |

The `runs/` directory is gitignored — outputs are not committed.

## Optional: dense-vs-BM25 comparison

The `--lexical` flag is a no-op today (see above). Once the API surface supports it,
the comparison arm would be run as:

```bash
python3 run_eval.py --run-id "${RUN}-bm25" --lexical && \
  python3 grade.py runs/${RUN}-bm25 && \
  python3 report.py runs/${RUN}-bm25
```

## Advanced options

```
--base-url URL        Wiki base URL (default: https://wiki.wikantik.com)
--agent-model MODEL   Anthropic model for answering (default: claude-sonnet-4-6)
--judge-model MODEL   Anthropic model for grading (default: claude-sonnet-4-6)
--max-tool-iters N    Max tool-loop iterations for grounded_mcp arm (default: 6)
```

## Running tests

```bash
cd eval/agent-grounding
python3 -m pytest -v
```

All 33 unit tests are stdlib-only (no network calls; all I/O is mocked).
