# Bundle-eval scheduled run — runbook

The `BundleEvalScheduler` (off by default) runs `eval/bundle-corpus/queries.csv` against the live
bundle path every `wikantik.bundle.eval.interval.hours` and writes one `bundle_eval_run` row per run.

## Enable it
Set `wikantik.bundle.eval.interval.hours` > 0 in a deployment that has a live embedding index
(prod / a local Tomcat with the dense index built). It stays disabled in CI and fresh installs.

**Container/Tomcat deployments:** the default corpus path `eval/bundle-corpus/queries.csv` is
repo-relative and is NOT bundled into the WAR, so under Tomcat it will not resolve (you'll see a
"corpus empty/not found" warn every tick and no `bundle_eval_run` rows). Set
`wikantik.bundle.eval.corpus` to an ABSOLUTE path to a `queries.csv` that exists on the container
filesystem, and place a `thresholds.properties` next to it (the loader reads it as the corpus
file's sibling). Shipping the corpus with the deployment is a possible later-phase improvement.

## Read a result row
`SELECT run_at, config_id, overall_recall, recall_similarity, recall_relational, recall_boundary,
questions_scored, regression FROM bundle_eval_run ORDER BY run_at DESC LIMIT 10;`

- `regression = true` means overall or a per-category recall fell STRICTLY below its floor in
  `eval/bundle-corpus/thresholds.properties`. The scheduler also logged a `BUNDLE-EVAL REGRESSION`
  WARN naming the breached floor(s).
- `regression = false` with recall near the floor is a warning sign; compare to the baseline in
  `eval/bundle-corpus/baseline-notes.md`.

## Respond to a regression
1. What changed since the last non-regressed `run_at`? A retrieval config flip
   (`wikantik.bundle.rerank.chain`, dense backend, chunker), a re-index, or a corpus edit.
2. Reproduce with the manual measurement in `baseline-notes.md`.
3. If a config change caused it, revert or re-measure; if the corpus/index is the cause, fix and
   re-run. Ratchet the floors UP in `thresholds.properties` only after a confirmed improvement — never
   silently down.
