# Themes D + E Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Land the deterministic cleanup tier of the improvement roadmap — eval-harness robustness + opt-in multi-sample stability (Theme D) and an explicit-rollback hygiene fix (Theme E) — without changing any production behavior.

**Architecture:** Two independent tasks. Task 1 is a defensive Java change in `EmbeddingIndexService` (explicit `rollback()` when a `RuntimeException` escapes the transactional work block, instead of relying on implicit rollback-on-close). Task 2 is Python eval-harness work: an opt-in `--samples N` median mode (default 1 = zero cost/behavior change) plus three robustness minors.

**Tech Stack:** Java 21 / JUnit5 (Task 1); Python 3 stdlib + pytest (Task 2).

## Global Constraints

- **No production behavior change.** Task 1 must not alter observable behavior — PostgreSQL already rolls back on connection-close; this only makes the rollback explicit. Existing `EmbeddingIndexServiceTest` (Docker-gated, real pgvector container) must stay green as the regression guard. Do NOT add a contrived mock-Connection test for the rollback call — the change is provably-safe hygiene, guarded by the existing suite.
- **`--samples` defaults to 1.** With no `--samples` flag, the eval runs exactly as today (one row per arm/qid; report means unchanged). Multi-sample is purely opt-in.
- **Do NOT edit eval ground-truth (`questions.json` references) in this plan.** (D1 was investigated and dropped — the kg-inclusion reference is already correct and complete; editing it would game the eval.)
- **TDD** for the Python pure-function changes (median, SCORE-regex) and the robustness guards. Java Task 1 is regression-guarded only (see above).
- Python tests run: `cd eval/agent-grounding && python3 -m pytest <file> -q` (pytest 9.1.1, user-site for 3.14).

---

### Task 1: Explicit rollback hygiene in EmbeddingIndexService (E1)

Three transactional methods (`indexAll` and the two reconcile methods, ~lines 231–273, 292–325, 358–392) set `autoCommit=false` and `rollback()` only inside `catch(SQLException)`. But `flushBatch` can throw a `RuntimeException` (the fix-#1 transient-abort path); that escapes the `catch(SQLException)` and relies on implicit rollback when the try-with-resources closes the connection. Make it explicit.

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/search/embedding/EmbeddingIndexService.java` (the three transactional methods)
- Regression guard: `wikantik-main/src/test/java/com/wikantik/search/embedding/EmbeddingIndexServiceTest.java` (existing; Docker-gated — do not modify)

**Interfaces:**
- Consumes: existing `Connection conn` (autoCommit=false), the existing inner `catch(SQLException)` blocks.
- Produces: each method also rolls back explicitly when a `RuntimeException` escapes the work block, then rethrows; no new public API.

- [ ] **Step 1: Add an explicit RuntimeException rollback to each of the three methods**

In each of the three transactional methods, after the existing inner `} catch( final SQLException e ) { conn.rollback(); ... throw ...; }`, add a sibling catch for RuntimeException that rolls back explicitly before rethrowing. Pattern (apply to all three; keep each method's existing message wording):

```java
            } catch( final SQLException e ) {
                conn.rollback();
                LOG.warn( "<existing msg> rolled back ...", ... , e );
                throw new RuntimeException( "<existing msg> failed for " + modelCode, e );
            } catch( final RuntimeException e ) {
                conn.rollback();   // explicit — do not depend on implicit rollback-on-close
                LOG.warn( "<existing msg> rolled back on runtime error ...: {}", ..., e.getMessage(), e );
                throw e;
            }
```

(The outer `catch(SQLException)` that wraps `getConnection()`/`setAutoCommit` stays as-is. `conn.rollback()` itself declares `SQLException`; it is inside the `try(Connection conn = ...)` whose outer catch already handles `SQLException`, so the added `catch(RuntimeException)`'s `conn.rollback()` compiles — if the compiler flags an unhandled `SQLException` from `rollback()`, wrap that single call in a nested `try{ conn.rollback(); } catch(SQLException re){ LOG.warn("rollback failed: {}", re.getMessage()); }` and still rethrow the original RuntimeException.)

- [ ] **Step 2: Compile**

Run: `mvn -q -pl wikantik-main test-compile 2>&1 | tail -5`
Expected: BUILD SUCCESS (no unhandled-exception compile error; if there is one, apply the nested-try fallback from Step 1).

- [ ] **Step 3: Regression-guard with the existing suite (if Docker available)**

Run: `mvn test -pl wikantik-main -Dtest=EmbeddingIndexServiceTest -q 2>&1 | tail -15`
Expected: PASS (behavior unchanged). If the pgvector Testcontainer is unavailable in this environment, note that in the report and rely on the compile + code review (the change is behavior-preserving).

- [ ] **Step 4: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/search/embedding/EmbeddingIndexService.java
git commit -m "fix(embedding): explicit rollback on RuntimeException in indexAll/reconcile (E1 hygiene)"
```

---

### Task 2: Eval-harness median mode + robustness minors (D2 + E3)

Add opt-in `--samples N` (default 1) multi-sample median scoring, and fix three robustness minors surfaced during the eval build.

**Files:**
- Modify: `eval/agent-grounding/config.py` (`--samples` arg, default 1)
- Modify: `eval/agent-grounding/run_eval.py` (loop each arm N times, tag rows with `sample`; guard the `--run-id` last-token IndexError — M11)
- Modify: `eval/agent-grounding/report.py` (group by (arm,qid), take per-question median score before the mean — D2)
- Modify: `eval/agent-grounding/grade.py` (SCORE regex: line-anchored `^SCORE:` + last-match — M13)
- Modify: `eval/agent-grounding/bundle_client.py` (raise on non-2xx HTTP status — M7)
- Test: `eval/agent-grounding/test_report.py`, `test_grade.py`, `test_config.py`, `test_run_eval.py`, `test_bundle_client.py` (extend existing)

**Interfaces:**
- Consumes: existing `config.load_config`, `run_eval.run_all`, `report.py`'s score-aggregation, `grade.py`'s SCORE parsing, `bundle_client.fetch_bundle(base_url, query, lexical, http)`.
- Produces: `cfg.samples` (int, default 1); `raw.json` rows gain a `sample` field; `report.py` reduces multiple samples per (arm,qid) to their median before averaging; `grade.py` SCORE parse is anchored; `fetch_bundle` raises `RuntimeError` on non-2xx.

- [ ] **Step 1: Write failing tests**

In `test_config.py`: assert `load_config([])` yields `samples == 1`, and `load_config(["--samples","3"])` yields `samples == 3`.

In `test_report.py`: feed a graded set (rows are dicts with `arm`, `qid`, `correctness`, ...) with the SAME (arm,qid) appearing 3× with `correctness` values `[2,1,2]`; assert the reduction collapses them to ONE row with `correctness == 2` (median, not the mean 1.67), and that single-sample data passes through unchanged (one row in → one row out, same value). (Note the graded score field is named **`correctness`**, not `score`.)

In `test_grade.py`: assert a rationale body containing the literal text `"... I considered SCORE: 0 but chose ..."` followed by a real final line `SCORE: 2` parses to `2` (line-anchored, last-match), not `0`.

In `test_bundle_client.py`: assert `fetch_bundle` with an injected `http` returning `(500, "")` raises `RuntimeError` (not silently returns empty).

In `test_run_eval.py`: assert calling `main(["--run-id"])` (flag present, value missing) exits with the usage message rather than raising `IndexError`.

- [ ] **Step 2: Run the tests, confirm they fail**

Run: `cd eval/agent-grounding && python3 -m pytest test_config.py test_report.py test_grade.py test_bundle_client.py test_run_eval.py -q`
Expected: the five new assertions FAIL.

- [ ] **Step 3: Implement**

- `config.py`: add `p.add_argument("--samples", type=int, default=1)`; include `samples=args.samples` in the returned namespace.
- `run_eval.py`: in `run_all`, wrap the three `_safe(...)` appends in `for s in range(cfg.samples):` and add `"sample": s` to each row (and to the `_safe` error-row dict). Guard the `--run-id` parse: if `--run-id` is the last token (no following value), fall through to the existing `sys.exit("pass --run-id ...")` instead of indexing `argv[i+1]`.
- `report.py`: add a `_reduce_samples(graded)` helper that groups rows by `(r["arm"], r["qid"])`, and for each group emits ONE row: a copy of a representative row (the first in the group) with `correctness` overwritten by `statistics.median([r["correctness"] for r in group])` (use `int(statistics.median(...))` so an even-count median stays an integer score). Call `_reduce_samples` at the very start of the processing pipeline (before `summarize`, the per-question `by_q` table at line ~44, and the mcp-vs-bundle `hurt` comparison at line ~70) so every downstream aggregation sees one row per `(arm,qid)`. With samples=1 each group has one row → identity. This keeps the existing `sum(r["correctness"])/n` mean (line ~16) correct (now a mean over per-question medians) without further change.
- `grade.py`: change the SCORE extraction to scan lines, match `^\s*SCORE:\s*([0-2])\s*$` (anchored), and take the LAST match.
- `bundle_client.py`: in `fetch_bundle`, after `status, body = http(url)`, `if not (200 <= status < 300): raise RuntimeError("bundle HTTP %d" % status)`.

- [ ] **Step 4: Run all harness tests green**

Run: `cd eval/agent-grounding && python3 -m pytest -q`
Expected: all pass (the new five + the existing suite).

- [ ] **Step 5: Commit**

```bash
git add eval/agent-grounding/config.py eval/agent-grounding/run_eval.py eval/agent-grounding/report.py \
        eval/agent-grounding/grade.py eval/agent-grounding/bundle_client.py \
        eval/agent-grounding/test_config.py eval/agent-grounding/test_report.py \
        eval/agent-grounding/test_grade.py eval/agent-grounding/test_bundle_client.py \
        eval/agent-grounding/test_run_eval.py
git commit -m "feat(eval): opt-in --samples median mode + robustness fixes (M7/M11/M13) (D2+E3)"
```

---

## Self-Review

**Spec coverage:** E1 explicit rollback → Task 1 ✓; D2 median mode → Task 2 (config/run_eval/report) ✓; D1 → dropped (reference correct, documented in Global Constraints) ✓; D3 citation caveat → already present in `report.py` (no-op) ✓; E3 minors M7 (bundle status) / M11 (run-id IndexError) / M13 (SCORE anchoring) → Task 2 ✓; E2 (MEMORY.md tool count) → already done by controller ✓; extractor synonym-map → deferred (measure-first, out of this plan).

**Placeholder scan:** none — exact files, exact arg/flag names, exact regex, exact test cases.

**Type/name consistency:** `cfg.samples`, row `"sample"` key, `statistics.median`, `fetch_bundle(base_url, query, lexical, http)` signature all consistent across tasks; the `--run-id` guard matches the existing `main()` parse in `run_eval.py`.
