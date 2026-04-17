---
title: Wikantik Search Refinement
cluster: wikantik-development
type: article
tags:
- search
- retrieval
- evaluation
- testing
- development
- tooling
summary: How to iterate on the retrieval evaluation query set, run the standalone search-eval tool against a running wiki, and interpret the report.
related:
- WikantikDevelopment
- LocalRAG
- RagImplementationPatterns
- LlmEvaluationMetrics
---

# Wikantik Search Refinement

Retrieval quality — "did search put the right page in the top 5?" — is measured here by a seed set of `(query, ideal_page)` pairs run through the live wiki via `bin/search-eval`, reported as `recall@5`, `recall@20`, and `MRR`. Improvements (synonym handling, dense embeddings, rerankers) are validated against this set; without it, every future retrieval change is a vibe check.

The tool talks to whatever wiki you point it at — local dev, staging, production. It tests the *deployed* retriever, not an isolated in-process copy, so it picks up ACL filtering, index state, middleware, and any retrieval change that lands without needing code changes to the tool itself.

## The three locations

- **Source of truth — edit here:**
  `eval/retrieval-queries.csv`
  Three columns: `query, ideal_page, notes`. Add, remove, or remap queries in your editor. The `notes` column carries a category label (`synonym-drift`, `indirect`, `multi-word-concept`, `specific`, `general`, `business-process`, `hard`) so the per-category breakdown in the report is meaningful.

- **Rolling output — regenerated each run:**
  Whatever you pass to `--out`, or just stdout. Not committed; throwaway.

- **Frozen checkpoint — commit when it matters:**
  `docs/superpowers/specs/2026-04-17-retrieval-eval-baseline.md`
  A snapshot of the report at a known point in time. Update it when the query set is finalized, a new retriever lands, or you want a before-and-after reference to cite.

## Running the tool

Prerequisite: a wiki running somewhere you can reach. Local dev is easiest:

```bash
tomcat/tomcat-11/bin/startup.sh
```

Then:

```bash
# Default: localhost:8080, test.properties for credentials, all queries
bin/search-eval

# Write the report to a file while also printing it
bin/search-eval --out eval/reports/2026-04-18.txt

# Point at staging, anonymous user
bin/search-eval --base-url https://staging.example.com --anonymous

# JSON output, for diffing runs programmatically
bin/search-eval --json --out run.json

# Quieter — summary + missed-query list only, no full per-query table
bin/search-eval --quiet

# Full help
bin/search-eval --help
```

No Python dependencies — standard library only. Just `python3`.

**Exit codes:** `0` clean run, `1` at least one query produced an HTTP/network error (report enumerates them), `2` configuration error (missing queries file, unreachable endpoint).

## The typical loop

1. Edit `eval/retrieval-queries.csv`. Fix a bad `ideal_page`, add a query your team actually asks, drop one that turned out to have no good answer in the corpus.
2. Run `bin/search-eval --out eval/reports/try-N.txt`.
3. Read the report. Compare the summary line (recall@5, recall@20, MRR) and the missed-query list against the baseline doc.
4. Decide per query:
   - If a query moved from rank 193 to rank 4 — curation win.
   - If recall@5 dropped — is the query actually harder than you thought, is the `ideal_page` wrong, or did retrieval regress?
   - If a query always misses despite clearly having a good page — worth a bug.
5. Commit the CSV when satisfied. Update the baseline doc at natural milestones: end of a curation session, before swapping BM25 for hybrid retrieval, after a reranker lands.

## What the tool does

`bin/search-eval` is a standalone Python 3 script under `bin/`:

1. Loads `eval/retrieval-queries.csv` (or whatever `--queries` points at).
2. Reads credentials from `test.properties` at the repo root, or `--user/--password`, or skips auth with `--anonymous`.
3. For each query, issues `GET /api/search?q=<query>&limit=<N>` against the configured base URL.
4. Finds the 1-based rank of the `ideal_page` in the returned results (or `-1` if missing, `-2` on HTTP error).
5. Computes `recall@5`, `recall@20`, MRR, and per-category breakdowns.
6. Writes a formatted text report (default) or JSON (`--json`) to stdout, plus optionally to a file.

No threshold assertions — the tool is informational. Wire it into CI as a separate nightly or pre-deploy job when you're ready to alert on regressions.

## Query-writing heuristics

The value of the eval set is in query *variety*, not volume. Include:

- **Synonym drift.** Words in the query don't literally appear in the title. "Auth config" → a page titled `AuthenticationManager`. If every query is a title keyword hunt, the eval will never surface the problem that dense embeddings fix.
- **Indirect phrasing.** Natural how-to language instead of title-ish language. "How do I get started as a new hire" instead of "onboarding."
- **Multi-word concepts.** Three-to-five-word technical concepts the page discusses without necessarily title-matching.
- **Specific vs. general.** Both "Gemma 4 VRAM budget" and "local LLM hardware" should surface reasonable answers.
- **Business entities.** Named clients, products, internal codes — the queries that motivate the consulting-wiki use case.
- **Hard cases.** Queries your team has seen the LLM fumble. Abbreviations ("k8s"), short common terms ("ai"), or ambiguous phrasings.

Anti-patterns:

- **Title-copy queries.** "WikantikDevelopment" as a query against `WikantikDevelopment` is trivially passing.
- **Multiple equally-right answers.** Forces a single-answer framing; the current tool scores only one `ideal_page` per query.
- **Queries with no good answer in the corpus.** Ungradable; drop them.

## Interpreting the per-category breakdown

The report groups recall by the `notes` label. Categories tell you *where* retrieval is weak, not just how weak overall:

- `specific` near 1.0: keyword BM25 is doing its job on literal terms.
- `multi-word-concept` near 1.0: probably too many title-ish queries — add drift.
- `synonym-drift` and `indirect` low: the expected gap that dense embeddings should close.
- `business-process` low: the wiki hasn't built much terminological structure around these topics yet, or the queries need rewriting.
- `hard` stubbornly low: these are the intentionally-hard cases; don't over-optimize for them at the expense of the easier categories.

Track the categories over time. A retrieval change that lifts `specific` from 0.9 to 1.0 while dragging `synonym-drift` from 0.4 to 0.2 is a regression worth rolling back.

## Why a standalone tool (not a JUnit test)

Previous versions of this harness lived inside `wikantik-main` as a `@Disabled` JUnit test that built its own `TestEngine` and indexed `docs/wikantik-pages/` into a scratch directory. That worked but wasn't the shape an eval tool wants:

- Reindexed from scratch on every run (~30–90 s of overhead).
- Tested the test-path retriever, not whatever's actually deployed.
- Enabling it required `-DincludeDisabled=true -Dtest=RetrievalEvalTest` — a magic invocation rather than a direct command.
- Tied to the JVM and Maven.

`bin/search-eval` replaces that with an HTTP-based tool that:

- Runs in under a few seconds against whatever base URL you provide.
- Sees the real deployed retriever (indexed state, ACLs, any middleware).
- Is a direct command, not a testing gymnastics invocation.
- Requires no Maven, no JVM — just `python3`.

The `eval/retrieval-queries.csv` file is the same format as before; moving from test-classpath resources to a top-level `eval/` directory was purely a home-finding exercise. The committed baseline in `docs/superpowers/specs/2026-04-17-retrieval-eval-baseline.md` is still valid as a reference point — the numbers it reports came from the same underlying Lucene BM25, produced by the JUnit harness at the time.

## See also

- The baseline numbers and missed-query list:
  `docs/superpowers/specs/2026-04-17-retrieval-eval-baseline.md`
- The tool:
  `bin/search-eval`
- Seed query set:
  `eval/retrieval-queries.csv`
- The search endpoint it calls:
  `wikantik-rest/src/main/java/com/wikantik/rest/SearchResource.java` → `GET /api/search?q=…&limit=…`
