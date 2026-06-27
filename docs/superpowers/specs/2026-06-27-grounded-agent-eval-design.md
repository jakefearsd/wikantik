# Grounded-on-Wikantik agent eval — design

**Date:** 2026-06-27
**Status:** approved (brainstorming), pending spec review
**Author:** brainstorming session (Jake + Claude)

## Problem

Wikantik exposes a substantial agent-facing surface — two MCP servers
(`/knowledge-mcp` read, `/wikantik-admin-mcp` write) and a RAG context bundle
(`/api/bundle`, `assemble_bundle`). It is unproven whether this surface actually
makes an agent *more useful* than the same model with no grounding. We want
evidence, not vibes, plus a concrete backlog of what would make the interface
better.

## Goal & success criteria

Build a small, reproducible eval that answers: **does grounding an agent in the
wiki via the MCP interface beat a cold model on questions about Wikantik's own
internals?**

- **Primary output — a scorecard** comparing answer quality and citation
  accuracy across three conditions (below), with the cold→grounded delta as the
  headline number.
- **Secondary output — interface-friction findings:** a ranked list of what made
  the agentic surface awkward (unclear tool descriptions, missing tools, awkward
  return shapes, wasted/looping tool calls). This is the "make it more useful"
  backlog and is considered a first-class deliverable, not a byproduct.

"Worth a damn" = grounded conditions materially out-score cold on correctness
**and** produce verifiable citations to the right wiki sections, on questions the
base model demonstrably cannot answer from training alone.

## Why "Wikantik's own internals" as the domain

The base model cannot know this codebase's specific design decisions, so any
correctness lift is attributable to retrieval (not the model already knowing the
answer). The design docs (HybridRetrieval, the RAG/bundle + citation specs,
ontology, structural spine, Page-vs-Knowledge-Graph, security model) are the
ground-truth source, and they are present in the live wiki corpus — so the agent
must reach them **through the interface**, not by reading repo files.

## Conditions (three arms per question)

1. **COLD** — Claude answers with no tools, no context. Baseline.
2. **GROUNDED-MCP** — Claude runs a tool-use loop against `/knowledge-mcp`; the
   *model* chooses tools (`assemble_bundle` / `retrieve_context` /
   `search_knowledge` / `read_pages` / etc.) and answers with citations. This is
   the real test of the agentic interface.
3. **GROUNDED-BUNDLE** — the harness calls `/api/bundle?q=<question>` itself and
   injects the returned sections as context; the model does no tool-use. Isolates
   the *bundle* from the *tool-use autonomy*. The MCP-vs-BUNDLE gap shows whether
   letting the model drive retrieval helps or hurts versus a single canned bundle.

A **DENSE vs BM25** comparison is available by toggling retrieval mode (the GPU is
up, so DENSE is the default/headline; a `--lexical` run forces BM25 fallback to
quantify how much dense adds).

## Architecture

Standalone Python harness under `eval/agent-grounding/` (matches existing
`eval/bundle-corpus/` + `bin/eval/*.py` conventions). Read-only against prod; no
deployment, no schema change.

### Components

1. **`questions.yaml`** — ~15–20 hand-authored questions. Each entry:
   ```yaml
   - id: stale-citation-detection
     question: "How does Wikantik detect that a citation has gone stale, and what
                granularity of staleness does it report?"
     reference: "Citations are version-pinned + span-hashed at save; a background
                 check compares the cited span hash against the current target
                 section and reports graded span-level staleness ..."
     expect_sources: [Citations, "rag-as-a-service-and-knowledge-base-design"]
     difficulty: medium
   ```
   Categories spread across: retrieval/bundle, citations/drift, KG vs Page Graph,
   ontology/SPARQL, structural spine, security/ACL model, derived pages,
   agent-grade content.

2. **`run_eval.py`** — for each question runs the three arms. The GROUNDED-MCP arm
   implements a **local agent loop where the harness is the MCP client** (HTTP
   `initialize` → `notifications/initialized` → `tools/call`, per the CLAUDE.md
   MCP-testing notes), executing the model's `tool_use` calls against
   `/knowledge-mcp` and returning `tool_result`s. Chosen over the Anthropic
   remote-MCP connector because (a) it works against the internal or external
   endpoint without depending on Anthropic's servers traversing the Cloudflare
   WAF, and (b) it lets us **log every tool call** — which is the raw material for
   the interface-findings report. Caps tool iterations (e.g. 6) to catch loops.

3. **`grade.py`** — LLM-judge scores each answer for correctness vs. `reference`
   on a 0–2 scale (0 wrong/refused, 1 partial, 2 correct), with a one-line
   rationale. Plus a programmatic **citation check**: did the answer cite a page
   in `expect_sources`? Judge and agent models are configurable (default
   `claude-sonnet-4-6`; the run is a few cents). The judge never sees which arm
   produced an answer (blind).

4. **Outputs:**
   - `scorecard.md` — per-condition mean correctness, citation precision/recall,
     the cold→grounded delta, the MCP-vs-BUNDLE gap, and (when run) the
     DENSE-vs-BM25 gap. Plus a per-question table.
   - `interface-findings.md` — ranked friction observations distilled from the
     tool-call logs and judge rationales (e.g. "model called `search_knowledge`
     then ignored it 8/20 times — KG tool value unclear", "`assemble_bundle`
     return omits page title, model had to follow up with `read_pages`").

### Data flow

`questions.yaml` → `run_eval.py` (3 arms × N questions, logging tool calls) →
raw `runs/*.json` → `grade.py` → `scorecard.md` + `interface-findings.md`.

## Environment & prereqs

- Targets **prod** (`wiki.wikantik.com`, GPU now up → dense live). Read-only.
- `ANTHROPIC_API_KEY` for the harness; `/knowledge-mcp` bearer key (in `.env.prod`,
  `MCP_ACCESS_KEYS`). `/api/bundle` is public (view-ACL enforced at retrieval).
- Re-runnable with one command; same code for DENSE and `--lexical` runs.

## Non-goals

- Not a product/chatbot — no UI, no persistence, no answer synthesis service.
- Not a corpus-wide retrieval benchmark (that's `eval/bundle-corpus/`); this is an
  *agent-level* end-to-end test on a curated internals question set.
- Not testing the write/admin MCP surface (separate future eval).
- Not wired into CI initially (it makes paid API calls); manual/ad-hoc.

## Risks & mitigations

- **Question set leaks answers** (too easy / phrased from the doc) → author
  questions in operator language, keep `reference` separate, spot-check that COLD
  genuinely struggles.
- **Judge unreliability** → 0–2 scale with rationale, blind to arm; manual review
  of the ~20 graded rows is cheap.
- **Small N** → 15–20 is enough for a directional verdict + qualitative findings;
  not a statistical claim. Stated explicitly in the scorecard.
- **MCP auth/WAF** → local-agent-loop client avoids the remote-connector path;
  can target internal `docker1:8080` if CF blocks.

## Out of this spec / future

If the verdict is positive, the interface-findings backlog feeds a follow-up
"agentic interface improvements" effort. If negative, we learn cheaply where it
falls down.
