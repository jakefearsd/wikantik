# LaTeX Math Validation — Overnight Run Report (2026-06-11)

Running log of an autonomous overnight session. Compiled into the morning report at the end.

## Mandate (from the user)

1. Finish the full LaTeX math-validation implementation (validators + probe-discovered rules + save-path wiring + UI reporting).
2. Probe with a few hundred permutations to discover additional high-confidence patterns; bring them into the system + UI.
3. Deploy locally; test as an MCP agent against `localhost:8080`, hunting bugs from an agent perspective; fix → deploy → repeat (2–5 cycles).
4. Review site content via MCP; repair math formulas with rendering defects; report.
5. Refine the load-test harness to use current features (incl. MCP surface); soak + profile; fix perf items, esp. new features.
6. Heavy debug + refinement of the new LaTeX error checking for human + agent workflows.
7. A fresh single cycle: soak → profile → repair → validate.
8. Full morning report.

## Progress checklist

- [x] Stage A1: value types + CodeRegions + MathSpanExtractor (Tasks 1–3) — commits 0a2225b062, 441feb29a0, 99285eaa59 (7/7 tests)
- [x] Stage A2: MathStructureValidator + LatexSyntaxLinter (Tasks 4–5) — commits 6d40e7aa6a, 020a7c7873 (13/13 tests)
- [x] Stage A3: 50/50 corpus + grading + FastenerEngineering fix (Tasks 6–7) — commits 73aeabebaf, ba1dc4687a (106 rows: 58 valid/48 invalid; 235 tests; 8 blind-spot rows)
- [x] Stage A4: final Phase-1 verification (Task 8) — test-compile + math package green (exit 0)
- [x] Stage B1: probe harness built + run — 401 permutations, commit 8563a3ad3f
- [x] Stage B2: linter refined — commits 7f64f9d965, d02296974f, b8dd3d0484 (238 tests; 5 ERROR + 4 WARNING rules; fracArity 0.957; 0 blind-spots; LatexRules shared with probe)
- [x] Stage C: Phase 2 wiring + UI — commits ee635edb73, 8b3b545582, 0f7cfd53b7, b6c7970af2, 5f2c002791 (backend, 9/9) + e3dc71180c, 508bef1bda (frontend, 41 tests). Math ERRORs block on REST+MCP; ContentViolation carries body-relative location+excerpt+caret; MathValidationSummary + CodeMirror Jump.
- [x] Stage D: full build (BUILD SUCCESS) + redeploy. NOTE: Tomcat startup needs a manual bypass of unrelated migration V039 (needs `migrate` role pw / sudo, both unavailable autonomously). App healthy; MCP Bearer auth works.
- [x] Stage E: MCP agent bug-hunt — 4 cycles. C1: 18-case battery 17/18 → found+fixed CRLF bug (commit dad28c1ab6). C2: 18/18 live (fix confirmed). C3: 16/16 false-positive stress (14 complex real formulas + multi-error + accepted residual) — ZERO false positives. C4: update_page path (refuse malformed / accept valid). Both write+update MCP paths validated.
- [x] Stage F: repaired 102 shipped pages (197 glued display-math blocks isolated) — commit 41b26a1212; scanner ShippedPagesMathHealthTest now 0 defects (regression guard f9495e2496); safety invariant held; KaTeX spot-checks passed; repairs live via file provider.
- [x] Stage G: load harness refinement (commit pending) + first profiled soak (JFR) — found dotAt already-optimal, JAR-caching ~14% (local-only config), math validation ~0.25% (negligible).
- [x] Stage H: LaTeX precision refinement — commit 0c1c8538e3 (fracArity FP 7→0 / precision 1.0; unknownCommand FP 47→4; +\zeta). 240 math tests green; re-validated live (battery 18/18, \zeta + \frac{a}b clean).
- [~] Stage I: final soak/profile/validate with caching enabled — soak running; will quantify caching win + confirm no regression.
- [ ] Stage J: morning report

## Improvements log (append as we go)

- Foundation validators landed (code-region masking is the core false-positive defense).

## Bugs found & fixed (append)

- **CRLF defeats emptyScript + leaks `\r` into excerpts (FOUND via live MCP agent probe, FIXED — commit pending push):** the MCP/REST save pipeline persists `\r\n`; a trailing `\r` after `x^` looked like the superscript's argument so `emptyScript` silently missed it, and excerpts carried a stray `\r`. Fixed by normalizing `\r\n`/`\r`→`\n` at the `MathValidationPageFilter` boundary (offsets stay LF-based, matching CodeMirror). Regression test added; 18/18 battery after fix (verify post-redeploy). This is the bug class unit tests missed because they only used `\n`.
- **Linter false positive on `\rightarrow` (FOUND, fix pending in Stage B):** `LatexSyntaxLinter.count(latex, "\\right")` substring-matches `\rightarrow`/`\Rightarrow`/`\leftrightarrow`, firing a bogus `math.syntax.leftRight` warning. Fix: count `\left`/`\right` only as whole command tokens — regex `\\left(?![a-zA-Z])` / `\\right(?![a-zA-Z])`. False positives are the top risk per user; this gets fixed during linter refinement.

## Probe results (Stage B) — rule discovery (401 permutations, 249 valid / 152 broken)

Ranked precision/recall/support/FP vs real KaTeX ground truth:

```
rule                          precision  recall  support  FP
unbalancedBraces                1.000    0.257     39     0
leftRightMismatch_token         1.000    0.138     21     0
emptyScript                     1.000    0.132     20     0
beginEndMismatch                1.000    0.118     18     0
doubleScript                    1.000    0.112     17     0
ampOutsideEnv                   1.000    0.092     14     0
sqrtBadOptional                 1.000    0.039      6     0
fracArity                       0.750    0.138     28     7   (nested \frac breaks the regex)
leftRightMismatch_naive         0.457    0.138     46    25   (\rightarrow false positives — LIVE BUG)
unknownCommand                  0.145    0.053     55    47   (allowlist too narrow)
```

**Promotion policy:** block (ERROR) iff deterministic "KaTeX cannot parse" + 100% precision + 0 FP + support ≥15.

- Promote to **ERROR (block):** unbalancedBraces, leftRightMismatch(token), emptyScript, beginEndMismatch, doubleScript.
- Keep **WARNING (savable, reported):** ampOutsideEnv, sqrtBadOptional (airtight but low evidence), fracArity (after recursive-counter fix), unknownCommand (after widening allowlist).
- All 8 corpus blind-spots are now covered by the new detectors → inventory shrinks toward 0.

## Performance findings (append)
Profiled a 4-min soak (30 read VUs + MCP-write VU) with JFR (`/tmp/soak.jfr`). Soak was clean: 7265/7265 checks passed, p95 18ms, ~30 req/s.

1. **The NEW math validation is NOT a hotspot** — ~0.25% of CPU samples total (`MathValidationPageFilter.preSave`/`MathStructureValidator.validate`/`CodeRegions.scan` = 1 sample each; the 6 `DisplayMathPreProcessor.transform` samples are the pre-existing render path). The save-time validator is effectively free. Good news for the new feature.
2. **Top hotspot: `InMemoryChunkVectorIndex.dotAt` = 27.5% CPU** — the dense-vector dot product. **Already optimal** (Vector-API FMA over a flat `float[]`, scalar tail only). This is the inherent cost of brute-force kNN in the **inmemory** backend; prod uses `lucene-hnsw` (HNSW, no linear scan), so this does not dominate in prod. No change.
3. **JAR resource lookups ≈14% CPU** (`ZipFile.getEntryPos` 5.5% + `getArchiveEntry` 4.5% + `closeJarFile` 2.5% + `JarFile.getEntry` 1.6%) — caused by **`cachingAllowed="false"` in the local `ROOT.xml`** (a deliberate dev convenience). **Prod is already correct** (`docker/entrypoint.sh` sets `reloadable="false" cachingAllowed="true"`), so this is a LOCAL-only artifact that also skewed the profile. REPAIR: enabled caching locally (`cachingAllowed="true" cacheMaxSize=40MB cacheTtl=5000`) → re-soak in Stage I to quantify. Reversible; documented in `ROOT.xml`.

## Environment blockers (not feature bugs)

- **Migration V039 unappliable autonomously:** `migrate` role needs a password I don't have; `sudo -u postgres` is interactive. App started by bypassing migrate (manual WAR swap). `password_must_change` column is absent — affects password login only, not MCP/Bearer paths. ACTION FOR USER: apply V039 (`bin/db/migrate.sh` as the migrate role, or `sudo -u postgres psql -d wikantik -f bin/db/migrations/V039__password_must_change.sql`).
- **testbot password in test.properties is stale:** REST `/api/auth/login` returns 401 (the MCP API key still works). Pivoted load-test writes to the MCP write surface (Bearer) — which is what was wanted anyway. ACTION FOR USER: refresh `test.user.password` or re-seed testbot if REST-auth load testing is needed.

## Load harness refinement (Stage G)

Refined `loadtest/lib/endpoints.js` + `wikantik-load.js`:

- Writes now carry LaTeX (valid display blocks + 1-in-10 malformed) so `MathValidationPageFilter` runs on every save.
- New `mcpWriteCycle` + `mcpAgentFlow`: real MCP `tools/call` traffic (write_pages/update/delete + read_page/search_knowledge/query_nodes) with per-VU sessions — the expensive MCP surface, not just `tools/list`.
- New read surfaces: `/wiki/{slug}?format=md` (RAG), `/api/changes` (sync feed).
- Read scenario reweighted to include 25% real MCP retrieval.

## Open risks / follow-ups (append)

- Inline `$…$` glued to words (cosmetic, renders OK) left as-is across the corpus — only the ERROR-class display-`$$` glue was repaired. Could add an inline-spacing pass later.
- `fracArity` WARNING has 1 known FP (`\frac{a}b`, valid bare-token 2nd arg); `unknownCommand` WARNING still ~7 rare-command FPs (`\zeta`, `\xrightarrow`). Both WARNING-only (non-blocking) — widen allowlist later.
