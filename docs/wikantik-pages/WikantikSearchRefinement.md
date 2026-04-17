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
summary: How to iterate on the retrieval evaluation query set, run the harness, and interpret the report against a committed baseline.
related:
- WikantikDevelopment
- LocalRAG
- RagImplementationPatterns
- LlmEvaluationMetrics
---

# Wikantik Search Refinement

Retrieval quality — "did search put the right page in the top 5?" — is measured here by a seed set of `(query, ideal_page)` pairs run through the live Lucene index, reported as `recall@5`, `recall@20`, and `MRR`. Improvements (synonym handling, dense embeddings, rerankers) are validated against this set; without it, every future retrieval change is a vibe check.

## Three files, three roles

- **Source of truth — edit here:**
  `wikantik-main/src/test/resources/retrieval-eval/queries.csv`
  Three columns: `query, ideal_page, notes`. Add, remove, or remap queries in your editor. The `notes` column carries a category label (`synonym-drift`, `indirect`, `multi-word-concept`, `specific`, `general`, `business-process`, `hard`) so the per-category breakdown in the report is meaningful.

- **Rolling output — regenerated each run, gitignored:**
  `target/retrieval-eval-report.txt`
  Written by the harness on every run. Shows overall recall, per-category recall, and a per-query table of ranks. This is the "did my edit change anything?" view.

- **Frozen checkpoint — commit when it matters:**
  `docs/superpowers/specs/2026-04-17-retrieval-eval-baseline.md`
  A snapshot of the report at a known point in time. Update it when the query set is finalized, a new retriever lands, or you want a before-and-after reference to cite.

## The typical loop

1. Edit `queries.csv`. Fix a bad `ideal_page`, add a query your team actually asks, drop one that turned out to have no good answer in the corpus.
2. Run the harness:

   ```bash
   mvn test -pl wikantik-main -Dtest=RetrievalEvalTest -DincludeDisabled=true -q
   ```

   Or strip `@Disabled` from the test locally for rapid iteration.

3. Read `target/retrieval-eval-report.txt`. Compare the summary line (recall@5, recall@20, MRR) and the missed-query list against the baseline doc.

4. Decide per query:
   - If a query moved from rank 193 to rank 4 — curation win.
   - If recall@5 dropped — is the query actually harder than you thought, is the `ideal_page` wrong, or did retrieval regress?
   - If a query always misses despite clearly having a good page — worth a bug.

5. Commit the CSV when satisfied. Update the baseline doc at natural milestones: end of a curation session, before swapping BM25 for hybrid retrieval, after a reranker lands.

## What the harness does

`RetrievalEvalTest` is `@Disabled` by default — it's a measurement tool, not a gate. When enabled, it:

1. Builds a `TestEngine` pointed at **a throwaway copy** of `docs/wikantik-pages/` (see "Safety note" below).
2. Waits up to 180 s for Lucene to finish indexing the corpus.
3. Runs every query through `SearchManager.findPages(query, context)`.
4. Records the 1-based rank of the ideal page in the ordered result list (or `-1` if not found).
5. Computes `recall@5`, `recall@20`, and MRR, plus per-category recall.
6. Prints a formatted report to stdout and writes `target/retrieval-eval-report.txt`.

No threshold assertions in v1. The harness is informational.

## Safety note — TestEngine and emptyWikiDir

`TestEngine.shutdown()` calls `emptyWikiDir()`, which recursively deletes everything under the configured `wikantik.fileSystemProvider.pageDir`. This is deliberate — tests that create ad-hoc pages want a clean slate for the next run. But it assumes the page dir is a disposable tmp directory.

Pointing `TestEngine` directly at `docs/wikantik-pages` will delete the real corpus on shutdown. The harness avoids this by copying the corpus to a per-run `target/retrieval-eval-corpus-<timestamp>/` directory before engine init, and pointing the engine there. Do **not** remove that copy step.

Related: `emptyWorkDir` (the sibling cleanup for the Lucene index files) has a built-in ownership check — it only deletes if the workdir contains a `refmgr.ser` file, proving the engine created it. `emptyWikiDir` has no such guard, which is the root cause of the risk. Adding a "test-created this dir" marker before bulk deletion would bring both helpers to the same safety posture. Tracked as a potential follow-up.

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
- **Multiple equally-right answers.** Forces a single-answer framing; v1 supports only one `ideal_page` per query.
- **Queries with no good answer in the corpus.** Ungradable; drop them.

## Interpreting the per-category breakdown

The report groups recall by the `notes` label. Categories tell you *where* retrieval is weak, not just how weak overall:

- `specific` near 1.0: keyword BM25 is doing its job on literal terms.
- `multi-word-concept` near 1.0: probably too many title-ish queries — add drift.
- `synonym-drift` and `indirect` low: the expected gap that dense embeddings should close.
- `business-process` low: the wiki hasn't built much terminological structure around these topics yet, or the queries need rewriting.
- `hard` stubbornly low: these are the intentionally-hard cases; don't over-optimize for them at the expense of the easier categories.

Track the categories over time. A retrieval change that lifts `specific` from 0.9 to 1.0 while dragging `synonym-drift` from 0.4 to 0.2 is a regression worth rolling back.

## See also

- The current baseline numbers and missed-query list:
  `docs/superpowers/specs/2026-04-17-retrieval-eval-baseline.md`
- The harness itself:
  `wikantik-main/src/test/java/com/wikantik/search/RetrievalEvalTest.java`
- Seed query set:
  `wikantik-main/src/test/resources/retrieval-eval/queries.csv`
