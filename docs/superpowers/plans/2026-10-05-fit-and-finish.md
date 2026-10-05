# Fit-and-Finish + Code-Truth Documentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Leave Wikantik needing no attention for 1–2 months — project rules hold, known defects closed — and make the GitHub documentation code-true and organised by audience.

**Architecture:** Phase 0 settles every file path (clutter removal, doc moves, a link checker that enforces it). Phase 1 runs three lanes concurrently: a **code lane** (Maven; tasks strictly sequential because they share modules), a **frontend lane** (npm only), and a **docs lane** (no builds; tasks parallel, each owning a disjoint file set). Phase 2 gates and reviews; Phase 3 touches production only with per-step user confirmation.

**Tech Stack:** Java 25 / Maven reactor, JUnit 5 + Mockito, React 19 / Vite 8 / vitest 4, Python 3 (tooling), bash test suites under `bin/tests/`, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-05-fit-and-finish-design.md`

## Global Constraints

- Work directly on `main`; no branches/PRs. Never `git add -A`; stage named paths only.
- **Concurrent lanes share one working tree.** Commit with path-limited commits so another lane's staged files are never swept in: `git commit -m "<msg>" -- <path> <path> …`. On `index.lock` contention, wait 5 s and retry (max 5). Never `git stash`, never `git reset`, never `git checkout -- .`.
- Every commit message ends with the line `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`; subject ≤ 72 chars, body 0–2 lines.
- TDD: a failing test before every behavioural fix; run it red, then green.
- Never swallow exceptions: every catch logs (`LOG.warn` for unexpected failures, `LOG.debug` only for expected-bad-input fallbacks, with a comment saying why the fallback is correct) or rethrows.
- Only the code lane runs Maven. Long Maven runs (> ~5 min) go through `bin/agent-build.sh start|status|wait|tail` — never a bare foreground call, never end a turn waiting for a detached build.
- Unit runs: `mvn test -pl <module> -Dtest=<Class> -q` (add `-am` only if a dependency changed; follow with `mvn test-compile` after signature changes).
- Coverage floors only go up, never above `0.90`; floor = measured value rounded **down** to a whole percent.
- PMD baseline (`build-support/pmd-complexity-baseline.properties`) entries only ever come out.
- Versioned migrations stay DDL-only; this plan adds none.
- `docs/ConfigurationReference.md` stays at that exact path (hard-coded in `bin/config-reference.sh` + `ConfigReferenceRegressionTest`); never hand-edit it.
- `docs/wikantik-pages/Main.md` is generated from `Main.pins.yaml` — never edit it by hand.
- Shell `grep` is ugrep and silently skips some files — use `grep -rIa` (or `git grep`).
- Production (wiki.wikantik.com, docker1) is touched **only** in Task 24, one step at a time, each after explicit user confirmation.

### Code-truth verification procedure (applies to every docs-lane task)

For every concrete claim in a doc you touch, check it against its source of truth; fix it or delete it. Never invent a claim you did not verify.

| Claim | Source of truth (check with `git grep -n` / `ls` / `--help`) |
|---|---|
| HTTP path, servlet | `wikantik-war/src/main/webapp/WEB-INF/web.xml`; SPA routes `wikantik-frontend/src/main.jsx`; `SpaRoutingFilter.SPA_EXACT` |
| Property key + default | `wikantik-main/src/main/resources/ini/wikantik.properties`, `wikantik-admin-mcp/src/main/resources/wikantik-mcp.properties`, `wikantik-tools/src/main/resources/wikantik-tools.properties` |
| Script + flags | `bin/<script> --help` and the script body |
| Migration / schema | `ls bin/db/migrations/` |
| MCP tools | `McpProtocolIT.EXPECTED_TOOLS` (admin), `wikantik-mcp-instructions.txt`, `KnowledgeMcpInitializer` (knowledge), `wikantik-tools` OpenAPI servlet |
| Connector types | `ConnectorConfigCodec.UI_TYPES` |
| Versions | root `pom.xml`, `Dockerfile`, `bin/deploy-local.sh`, `docker-compose.yml`, `wikantik-frontend/package.json` |
| UI behaviour | the component under `wikantik-frontend/src/` (name the file) |

Volatile numbers (tool counts, servlet counts, migration numbers) appear in **one** place per audience — `docs/admin/McpAgents.md` owns tool counts, `docs/developer/Architecture.md` owns module/servlet counts — and are linked, not repeated, elsewhere.

Every docs-lane task ends its report with a **claims table** (`claim | source checked | result: ok / fixed / removed`) covering at least every path, key, script and count in the files it touched, and runs `python3 bin/check-md-links.py` to zero.

Writing style for docs: second person, present tense, task-oriented headings ("Add a connector", not "Connector addition"); each doc opens with one paragraph saying who it is for and what it covers; no marketing adjectives; code blocks for every command.

## Review Focus

1. **Links from outside the default link-check scope** — the repo wiki corpus (`docs/wikantik-pages/`), `marketing/`, `clients/`, scripts and tests that name doc paths, and absolute `github.com/jakefearsd/wikantik/blob/main/docs/...` URLs — must not break when docs move. Owner: Task 3 (rewrite step + `--all` report).
2. **System pages that leave the en jar become ordinary pages wherever they still exist in a page store** — they would appear in search, sitemap and recent changes. The IT test-repo copy must be cleaned with the jar (Task 9), and prod must have them deleted in the same release window (Task 24 D1).
3. **Expected bad input must not become WARN noise** — e.g. `?limit=abc` on `SelfApiKeysResource`/`ToolsOpenApiServlet`/`BundleResource`, unknown principal lookups in SCIM/group code. Owner: Task 4 (classification table in the task + per-site review).
4. **Token replacement must not change the light theme's look** — e.g. `var(--surface, #fff)` → `var(--bg-elevated)` must resolve to the same light colour. Owner: Task 13 (light-theme parity step + browser check in Task 23).
5. **A missing `RecentArticlesTemplate` page** must leave the RecentArticles plugin/servlet rendering its default format, not throwing. Owner: Task 9 (test step).

---

## Phase 0 — Settle paths (sequential, before anything else)

### Task 1: Markdown link checker

**Files:**
- Create: `bin/check-md-links.py`
- Create: `bin/tests/test-check-md-links.sh`
- Modify: `.github/workflows/quality-gates.yml` (add a step to the ops-tooling job, after "Deploy + ops tooling tests")

**Interfaces:**
- Produces: `python3 bin/check-md-links.py [--all] [--root DIR]` — exit 0 when no broken relative link in scope, exit 1 otherwise; prints `path:line: broken link -> target`. Default scope = tracked `*.md` **excluding** `docs/wikantik-pages/`, `docs/superpowers/`, `docs/archive/`, `docs/clusters/`, `eval/`, `marketing/`, `CHANGELOG.md`. `--all` = every tracked `*.md` except `docs/wikantik-pages/`, report-only (always exit 0).

- [ ] **Step 1: Write the failing test suite**

```bash
#!/usr/bin/env bash
# bin/tests/test-check-md-links.sh — exercises bin/check-md-links.py against a throwaway git repo.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECKER="${HERE}/check-md-links.py"
fail=0
pass() { echo "PASS: $1"; }
bad()  { echo "FAIL: $1"; fail=1; }

repo="$(mktemp -d)"; trap 'rm -rf "${repo}"' EXIT
git -C "${repo}" init -q
mkdir -p "${repo}/docs/admin" "${repo}/docs/archive" "${repo}/bin"
echo '#!/bin/sh' > "${repo}/bin/tool.sh"
cat > "${repo}/README.md" <<'EOF'
[ok](docs/admin/Guide.md) [ok-anchor](docs/admin/Guide.md#setup) [web](https://example.com/x.md)
[mail](mailto:a@b.c) [frag](#local) [dir](bin/) ![img](docs/admin/missing.png)
```
[ignored-in-fence](nope.md)
```
`[ignored-inline](nope2.md)`
EOF
cat > "${repo}/docs/admin/Guide.md" <<'EOF'
[up](../../bin/tool.sh) [broken](../user/Nope.md)
[ref]: ../../README.md
EOF
echo '[stale](../../gone.md)' > "${repo}/docs/archive/Old.md"
git -C "${repo}" add -A >/dev/null

out="$(python3 "${CHECKER}" --root "${repo}" || true)"
grep -q 'docs/admin/Guide.md:1: broken link -> ../user/Nope.md' <<<"${out}" && pass "reports broken relative link" || bad "missed broken link: ${out}"
grep -q 'README.md:2: broken link -> docs/admin/missing.png' <<<"${out}" && pass "checks image links" || bad "missed broken image: ${out}"
grep -q 'nope.md\|nope2.md' <<<"${out}" && bad "checked links inside code" || pass "ignores code fences and inline code"
grep -q 'example.com\|mailto\|#local' <<<"${out}" && bad "checked external/anchor-only link" || pass "skips external and anchor-only links"
grep -q 'archive/Old.md' <<<"${out}" && bad "default scope included docs/archive" || pass "default scope excludes docs/archive"
python3 "${CHECKER}" --root "${repo}" >/dev/null && bad "exit 0 despite broken links" || pass "exit 1 on broken links"

all="$(python3 "${CHECKER}" --root "${repo}" --all)"; rc=$?
grep -q 'docs/archive/Old.md:1: broken link -> ../../gone.md' <<<"${all}" && pass "--all reports archive" || bad "--all missed archive: ${all}"
[ "${rc}" -eq 0 ] && pass "--all is report-only" || bad "--all exited ${rc}"

sed -i 's#\[broken\](../user/Nope.md)##' "${repo}/docs/admin/Guide.md"
sed -i 's#!\[img\](docs/admin/missing.png)##' "${repo}/README.md"
python3 "${CHECKER}" --root "${repo}" >/dev/null && pass "exit 0 when clean" || bad "non-zero exit on a clean tree"
exit "${fail}"
```

- [ ] **Step 2: Run it — expect FAIL**

Run: `bash bin/tests/test-check-md-links.sh`
Expected: non-zero exit; `python3: can't open file '.../check-md-links.py'`.

- [ ] **Step 3: Implement the checker**

```python
#!/usr/bin/env python3
"""Fail on broken relative links in tracked Markdown files.

Usage: bin/check-md-links.py [--all] [--root DIR]

Default scope is the maintained documentation: every tracked *.md except the
wiki corpus, historical/design records and generated or third-party trees.
--all widens to every tracked *.md except the wiki corpus and only reports.
"""
import argparse
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import unquote

EXCLUDE_ALWAYS = ("docs/wikantik-pages/",)
EXCLUDE_DEFAULT = ("docs/superpowers/", "docs/archive/", "docs/clusters/", "eval/", "marketing/")
EXCLUDE_DEFAULT_FILES = {"CHANGELOG.md"}

INLINE = re.compile(r"\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)")
REFDEF = re.compile(r"^\s{0,3}\[[^\]]+\]:\s*<?(\S+?)>?(?:\s+\"[^\"]*\")?\s*$")
FENCE = re.compile(r"^\s{0,3}(```|~~~)")
INLINE_CODE = re.compile(r"`[^`]*`")


def in_scope(rel: str, widen: bool) -> bool:
    if any(rel.startswith(p) for p in EXCLUDE_ALWAYS):
        return False
    if widen:
        return True
    return rel not in EXCLUDE_DEFAULT_FILES and not any(rel.startswith(p) for p in EXCLUDE_DEFAULT)


def is_external(target: str) -> bool:
    return target.startswith(("#", "mailto:", "tel:")) or "://" in target


def targets(text: str):
    in_fence = False
    for lineno, line in enumerate(text.splitlines(), start=1):
        if FENCE.match(line):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        line = INLINE_CODE.sub("", line)
        for m in INLINE.finditer(line):
            yield lineno, m.group(1)
        m = REFDEF.match(line)
        if m:
            yield lineno, m.group(1)


def check_file(path: Path, root: Path) -> list:
    problems = []
    rel = path.relative_to(root).as_posix()
    for lineno, target in targets(path.read_text(encoding="utf-8", errors="replace")):
        if is_external(target):
            continue
        clean = unquote(target.split("#", 1)[0].split("?", 1)[0])
        if not clean:
            continue
        resolved = (root / clean.lstrip("/")) if clean.startswith("/") else (path.parent / clean)
        if not resolved.exists():
            problems.append(f"{rel}:{lineno}: broken link -> {target}")
    return problems


def tracked_markdown(root: Path) -> list:
    out = subprocess.run(["git", "-C", str(root), "ls-files", "-z", "--", "*.md"],
                         check=True, capture_output=True).stdout.decode()
    return [p for p in out.split("\0") if p]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--all", action="store_true", help="every tracked *.md except the wiki corpus; report only")
    ap.add_argument("--root", default=None, help="repository root (default: git toplevel)")
    args = ap.parse_args()
    root = Path(args.root) if args.root else Path(subprocess.run(
        ["git", "rev-parse", "--show-toplevel"], check=True, capture_output=True, text=True).stdout.strip())
    problems = []
    for rel in tracked_markdown(root):
        if in_scope(rel, args.all) and (root / rel).is_file():
            problems.extend(check_file(root / rel, root))
    for p in problems:
        print(p)
    if args.all:
        print(f"{len(problems)} broken link(s) (report only)", file=sys.stderr)
        return 0
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
```

`chmod +x bin/check-md-links.py`.

- [ ] **Step 4: Run the suite — expect PASS**

Run: `bash bin/tests/test-check-md-links.sh`
Expected: every line `PASS:`; exit 0.

- [ ] **Step 5: Record the current baseline (do not fix yet)**

Run: `python3 bin/check-md-links.py | tee /tmp/claude-1000/-home-jakefear-source-jspwiki/930f1222-efe0-43b1-90e3-a512b9152a9f/scratchpad/links-baseline.txt | wc -l`
Expected: some count ≥ 0. These are fixed in Task 3; do not add the CI step's enforcement until then (Step 6 adds it, Task 3 makes it green before anything is pushed).

- [ ] **Step 6: Add the CI step** — in `.github/workflows/quality-gates.yml`, in the ops-tooling job directly after the "Deploy + ops tooling tests" step:

```yaml
      - name: Markdown relative links
        # Docs moved into docs/{user,admin,developer,archive} on 2026-10-05; this keeps them linked.
        run: python3 bin/check-md-links.py
```

(The `bin/tests/test-*.sh` glob already picks up the new suite.)

- [ ] **Step 7: Commit**

```bash
git commit -m "build: add markdown relative-link checker + CI step" -- bin/check-md-links.py bin/tests/test-check-md-links.sh .github/workflows/quality-gates.yml
```
(First `git add` the two new files by name.)

### Task 2: Root clutter (spec §3.10)

**Files:** deletions/moves listed below; referrers listed per item.

**Interfaces:**
- Produces: final root layout used by every later task (`bin/mcp_call.sh`, `bin/bkupcmd.sh`, `bin/wiki2markdown.py`, `docs/agents/math-authoring.md`, `docs/audits/*`, `docs/articles_to_create.csv`, `docs/adr/0000-extract-manager-interfaces-to-api.md`). `IndexingSupport.md` is moved in Task 3, not here.

- [ ] **Step 1: Verify the MarketRecoveryCoefficients copy**

Run: `ls docs/wikantik-pages/MarketRecoveryCoefficients.md && diff <(sed '1,/^---$/d' wikantik-wikipages/MarketRecoveryCoefficients.md | sed '1,/^---$/d') <(sed '1,/^---$/d' docs/wikantik-pages/MarketRecoveryCoefficients.md | sed '1,/^---$/d') | head`
If the corpus copy exists → delete the stray. If it does not → `git mv wikantik-wikipages/MarketRecoveryCoefficients.md docs/wikantik-pages/`.

- [ ] **Step 2: Delete**

```bash
git rm -q .asf.yaml KEYS UPGRADING ReleaseNotes Jenkinsfile fulltestsuite.sh mcd deep-research-architect.skill wikantik-wikipages/.corepages
git rm -rq tools testfiles
git rm -q wikantik-wikipages/MarketRecoveryCoefficients.md   # only if Step 1 found the corpus copy
git rm -rq --cached .gemini
```

- [ ] **Step 3: Move**

```bash
git mv mcp_call.sh bin/mcp_call.sh
git mv bkupcmd.sh bin/bkupcmd.sh
git mv scripts/wiki2markdown.py bin/wiki2markdown.py
git ls-files scripts                              # move any remaining file (e.g. its test) to bin/tests/ with the same base name
git mv AGENT.md docs/agents/math-authoring.md
git mv articles_to_create.csv docs/articles_to_create.csv
for f in $(git ls-files audits); do git mv "$f" docs/audits/; done
git mv docs/adrs/001-extract-manager-interfaces-to-api.md docs/adr/0000-extract-manager-interfaces-to-api.md
```

Merge `docs/adrs/README.md` into `docs/adr/README.md`: add one row/line for ADR-0000 ("legacy, predates the numbered series"), keep the active-series wording, then `git rm -q docs/adrs/README.md`.

- [ ] **Step 4: `.gitignore`** — the `.claude/` ignore currently hides tracked skills. Replace the `.claude` ignore line with:

```
.claude/*
!.claude/skills/
```
Verify: `git status --short .claude` shows nothing new; `git check-ignore -v .claude/skills/wiki-audit/SKILL.md` prints nothing.

- [ ] **Step 5: Update referrers**

Run: `git grep -nI -e 'mcp_call.sh' -e 'bkupcmd' -e 'scripts/wiki2markdown' -e 'AGENT.md' -e 'articles_to_create' -e '\baudits/' -e 'docs/adrs' -e 'UPGRADING' -e 'fulltestsuite' -e 'Jenkinsfile' -e '\.asf\.yaml' -e 'testfiles/' -- ':!docs/wikantik-pages' ':!CHANGELOG.md'`
Rewrite each live reference to the new path; in historical records (`docs/superpowers/**`, `docs/research_history.md`, `docs/full_rebrand_project.md`) rewrite paths mechanically, do not edit prose. `bin/mcp_call.sh`, `bin/bkupcmd.sh`: add a `--help` usage header if missing (project convention: every bin script has `--help`).

- [ ] **Step 6: Verify nothing still needs the deleted files**

Run: `mvn -q -o validate 2>&1 | tail -5` (reactor still resolves; zh/es/ru modules untouched here) and `bash bin/tests/test-check-md-links.sh`.
Expected: no errors.

- [ ] **Step 7: Commit** (list every removed/moved/edited path explicitly)

```bash
git commit -m "chore: remove ASF leftovers and one-offs; move stray root files" -- <every path from steps 2–5>
```

### Task 3: Documentation layout moves

**Files:**
- Create: `docs/user/`, `docs/admin/`, `docs/admin/templates/`, `docs/developer/`, `docs/archive/` (via `git mv`)
- Modify: every file that links to a moved doc (found by grep), incl. `CLAUDE.md`, `README.md`, `CONTRIBUTING.md`, `bin/*`, `build-support/versions-stable-rules.xml`, `.github/dependabot.yml`, `eval/**`, `docs/superpowers/**`, tests that name doc paths.

**Interfaces:**
- Consumes: `bin/check-md-links.py` (Task 1).
- Produces: the path map below. Docs-lane tasks edit files **at these paths**; merges in Task 15 create new names and delete the sources.

- [ ] **Step 1: Move with `git mv`** (names unchanged except where noted)

| From `docs/` | To |
|---|---|
| CommentsAndMentions, Frontmatter, MarkdownLinks, MathematicalNotation, PersonalZone | `docs/user/` |
| ApiKeys, AuditLog, AwsAccountSetup, AzureAccountSetup, GcpAccountSetup, BackupAndRecovery, CloudDeployment, Connectors, CostTiers, DatabaseUpdates, ProductionDBWorkflow, DockerDeployment, production-container-architecture, GettingStartedGuide, HubDiscovery, IndexRebuild, KgInclusionPolicy, LoggingConfig, ObservabilityDesign, OntologyManagement, PageOwnership, PostgreSQLLocalDeployment, DevelopingWithPostgresql, RelationalUserDatabase, RagContextBundle, RetrievalQuality, ScimProvisioning, SendingEmailFromTheWiki, SeoAndCrawling, SingleSignOn, WikantikOperations | `docs/admin/` |
| `/IndexingSupport.md` (repo root) | `docs/admin/IndexingSupport.md` |
| PrivacyPolicy, TermsOfService | `docs/admin/templates/` |
| MvnCheatSheet → `Building.md`; CodeQuality; `ci-cd-step-by-step.md` → `CI.md`; LoadTesting; ScalingCharacterization; `NewUI.md` → `FrontendArchitecture.md`; ProjectReference; dependency-upgrade-log | `docs/developer/` |
| ArchitectureCritique, FullOAuth, OAuthImplementation, full_rebrand_project, jsp-dead-code-catalog, KnowledgeGraphRerank, migration-1.0-to-1.1, PerformanceEvaluation, RefactorToPatterns, research_history, semantic_wiki_thoughts, Sitemap, SitemapOptimization, complete_markdown_migration | `docs/archive/` |

`docs/ConfigurationReference.md`, `docs/adr/`, `docs/agents/`, `docs/superpowers/`, `docs/wikantik-pages/`, `docs/audits/`, `docs/clusters/`, `docs/brand/`, `docs/marketing/`, `docs/articles_to_create.csv` do not move.

- [ ] **Step 2: Archive banner** — prepend to every file in `docs/archive/` (below its H1 if the first line is an H1, else at the top), replacing any existing status banner so there is exactly one:

```markdown
> **Historical document — not current behaviour.** Kept for the decision record. For how Wikantik works today, start at [docs/README.md](../README.md).
```

- [ ] **Step 3: Rewrite links to moved files repo-wide**

Write the old→new map as a two-column file in the scratchpad (`moves.tsv`, one `old<TAB>new` per moved file, repo-relative), then for each referrer found by `git grep -lI -e '<old basename>'` (excluding `docs/wikantik-pages/`, `node_modules`, `target`), rewrite **relative** links by recomputing the relative path from the referrer's directory to the new location (`os.path.relpath`). Use a small throwaway Python script in the scratchpad — do not hand-edit 100+ links. Also rewrite:
- links *inside* moved files that point elsewhere (`../bin/...`, `../deploy/...`, `../loadtest/...`, sibling docs) — they are now one level deeper;
- non-markdown referrers: `bin/config-reference.sh` (unchanged target, verify), `bin/deploy-site.sh`, `bin/profile-iteration.sh`, `bin/deploy-release.sh`, `build-support/versions-stable-rules.xml`, `.github/dependabot.yml`, `eval/bundle-corpus/*`, any `*.java`/`*.jsx` string naming a doc path;
- absolute `github.com/jakefearsd/wikantik/blob/main/docs/<Name>.md` URLs anywhere in the repo **including** `docs/wikantik-pages/` and `marketing/`;
- `CLAUDE.md` links (ProjectReference, KgInclusionPolicy, IndexingSupport, ConfigurationReference).

- [ ] **Step 4: Link check to zero**

Run: `python3 bin/check-md-links.py`
Expected: no output, exit 0. Fix every remaining line (pre-existing breakage included — a link to a file that no longer exists either points to its successor or loses its link markup).
Then run `python3 bin/check-md-links.py --all 2>&1 | tail -3` and fix any line caused by this task's moves inside `docs/superpowers/` (other pre-existing historical breakage there is out of scope; record the count in the task report).

- [ ] **Step 5: Corpus/marketing reference sweep (Review Focus 1)**

Run: `git grep -nI -E 'docs/(Api|Audit|Aws|Azure|Gcp|Backup|Cloud|Connectors|Cost|Database|Docker|Getting|Hub|Index|Kg|Logging|Observ|Ontology|Page|Postgre|Develop|Relational|Rag|Retrieval|Scim|Sending|Seo|Single|Wikantik|Privacy|Terms|Mvn|Code|ci-cd|Load|Scaling|NewUI|Project|dependency|Architecture|Full|OAuth|full_|jsp-|Knowledge|migration|Perf|Refactor|research|semantic|Sitemap|complete|Comments|Frontmatter|Markdown|Mathematical|Personal)' -- docs/wikantik-pages marketing clients`
Expected: every hit already points at the new path (from Step 3). Record in the report which corpus pages changed — prod copies of those pages need the same edit in Task 24 D1.

- [ ] **Step 6: Build smoke** — `mvn -q -o -pl wikantik-war -am -DskipTests package 2>&1 | tail -5` only if any `*.java`/resource was edited in Step 3; otherwise skip.

- [ ] **Step 7: Commit**

```bash
git commit -m "docs: reorganise into user/admin/developer/archive; fix all links" -- docs CLAUDE.md README.md CONTRIBUTING.md IndexingSupport.md <every other edited path>
```

---

## Phase 1 — Code lane (sequential; one task at a time)

### Task 4: No swallowed exceptions — guard test + fix every site (A1)

**Files:**
- Create: `wikantik-war/src/test/java/com/wikantik/architecture/NoSwallowedExceptionsTest.java`
- Modify: every site the test reports (expected list below).

**Interfaces:**
- Consumes: `ConfigSurfaceDriftTest.repoRoot()` (package-private static, same package).
- Produces: `NoSwallowedExceptionsTest.scanSource(String source) -> List<Integer>` (1-based line numbers of violating `catch`), used only by this test.

- [ ] **Step 1: Write the test (scanner + self-tests + repo scan)**

```java
/* (ASF license header — copy verbatim from TestSchemaSingleSourceTest.java) */
package com.wikantik.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Project rule (CLAUDE.md): never swallow an exception. A catch in main code must log or rethrow;
 * a body that is empty, comment-only, or only {@code return …;} / {@code continue;} / {@code break;} fails.
 */
class NoSwallowedExceptionsTest {

    private static final Pattern CATCH = Pattern.compile( "\\bcatch\\s*\\([^)]*\\)\\s*\\{" );
    private static final Pattern SILENT = Pattern.compile( "(return\\b[^;]*;|continue\\s*;|break\\s*;)?" );
    private static final Set< String > SKIP_DIRS = Set.of( "target", "node_modules", ".git", ".worktrees", ".claude", "tomcat" );

    @Test
    void scannerFlagsEmptyCommentOnlyAndSilentBodies() {
        final String src = String.join( "\n",
            "class A {",
            "  void a() { try { x(); } catch( final Exception e ) { } }",
            "  void b() { try { x(); } catch( final Exception e ) { // expected",
            "  } }",
            "  int c() { try { return x(); } catch( final NumberFormatException e ) { return -1; } }",
            "  void d() { for(;;) { try { x(); } catch( final Exception e ) { continue; } } }",
            "  void e() { try { x(); } catch( final Exception e ) { LOG.debug( \"why\", e ); } }",
            "  void f() { try { x(); } catch( final Exception e ) { throw new IllegalStateException( e ); } }",
            "  void g() { try { x(); } catch( final InterruptedException e ) { Thread.currentThread().interrupt(); } }",
            "  void h() { String s = \"catch( x ) { }\"; }",
            "}" );
        assertEquals( List.of( 2, 3, 5, 6 ), scanSource( src ) );
    }

    @Test
    void mainCodeNeverSwallowsAnException() throws IOException {
        final Path root = ConfigSurfaceDriftTest.repoRoot();
        final List< String > violations = new ArrayList<>();
        Files.walkFileTree( root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory( final Path dir, final BasicFileAttributes attrs ) {
                return SKIP_DIRS.contains( dir.getFileName().toString() ) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile( final Path file, final BasicFileAttributes attrs ) {
                final String rel = root.relativize( file ).toString().replace( '\\', '/' );
                if( rel.endsWith( ".java" ) && rel.contains( "/src/main/java/" ) ) {
                    try {
                        for( final int line : scanSource( Files.readString( file ) ) ) {
                            violations.add( rel + ":" + line );
                        }
                    } catch( final IOException e ) {
                        throw new UncheckedIOException( e );
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        } );
        assertTrue( violations.isEmpty(),
            "catch blocks that swallow the exception (log with context, or rethrow):\n  " + String.join( "\n  ", violations ) );
    }

    /** Returns the 1-based line of every catch whose body neither logs nor does anything but return/continue/break. */
    static List< Integer > scanSource( final String source ) {
        final String code = blankStringsAndComments( source );
        final List< Integer > hits = new ArrayList<>();
        final Matcher m = CATCH.matcher( code );
        while( m.find() ) {
            int depth = 1;
            int i = m.end();
            while( i < code.length() && depth > 0 ) {
                final char c = code.charAt( i++ );
                if( c == '{' ) depth++;
                else if( c == '}' ) depth--;
            }
            final String body = code.substring( m.end(), Math.max( m.end(), i - 1 ) ).trim().replaceAll( "\\s+", " " );
            if( SILENT.matcher( body ).matches() ) {
                hits.add( lineOf( source, m.start() ) );
            }
        }
        return hits;
    }

    /** Replaces comment and string/char-literal contents with spaces, preserving offsets and newlines. */
    private static String blankStringsAndComments( final String s ) {
        final StringBuilder out = new StringBuilder( s );
        int i = 0;
        while( i < s.length() ) {
            final char c = s.charAt( i );
            if( c == '/' && i + 1 < s.length() && s.charAt( i + 1 ) == '/' ) {
                while( i < s.length() && s.charAt( i ) != '\n' ) out.setCharAt( i++, ' ' );
            } else if( c == '/' && i + 1 < s.length() && s.charAt( i + 1 ) == '*' ) {
                final int end = s.indexOf( "*/", i + 2 );
                final int stop = end < 0 ? s.length() : end + 2;
                for( ; i < stop; i++ ) if( s.charAt( i ) != '\n' ) out.setCharAt( i, ' ' );
            } else if( c == '"' && s.startsWith( "\"\"\"", i ) ) {
                final int end = s.indexOf( "\"\"\"", i + 3 );
                final int stop = end < 0 ? s.length() : end + 3;
                for( ; i < stop; i++ ) if( s.charAt( i ) != '\n' ) out.setCharAt( i, ' ' );
            } else if( c == '"' || c == '\'' ) {
                int j = i + 1;
                while( j < s.length() && s.charAt( j ) != c && s.charAt( j ) != '\n' ) j += s.charAt( j ) == '\\' ? 2 : 1;
                for( int k = i; k <= Math.min( j, s.length() - 1 ); k++ ) out.setCharAt( k, ' ' );
                i = j + 1;
            } else {
                i++;
            }
        }
        return out.toString();
    }

    private static int lineOf( final String s, final int offset ) {
        int line = 1;
        for( int i = 0; i < offset; i++ ) if( s.charAt( i ) == '\n' ) line++;
        return line;
    }
}
```

(Logging is detected implicitly: any body containing a statement other than one bare `return/continue/break` passes. A body of `LOG.debug(...); return -1;` passes.)

- [ ] **Step 2: Run — expect self-test PASS, repo scan FAIL**

Run: `mvn test -pl wikantik-war -Dtest=NoSwallowedExceptionsTest -q 2>&1 | tail -70`
Expected: `scannerFlagsEmptyCommentOnlyAndSilentBodies` passes; `mainCodeNeverSwallowsAnException` fails listing ~52 sites (recon list: `swallow.txt` in the session scratchpad — `WikiEngine:984`, `WikiPageNameValidator:74`, `HttpUtil:170`, `ClassUtil:115,347`, `ExpectedCtrCurveParser:135,143,154`, `AdminApiKeysResource:302`, `RestServletBase:150`, `FrontmatterValidateResource:71`, `MyMentionsResource:200`, `SelfApiKeysResource:221,231`, `BundleResource:159`, `AdminPageOwnershipResource:112,266`, `ConfigValidationSupport:86`, `ScimUserResource:134`, `ScimGroupResource:126,207,384`, `McpToolUtils:283,400`, `DefaultCommandResolver:300`, `DefaultVariableManager:143`, `ConnectorConfigService:426`, `DerivedReflowService:258`, `Preferences:182`, `PingSearchEnginesTool:289`, `RenderingSubsystemFactory:88`, `SearchSubsystemFactory:155`, `SchemaDrivenFrontmatterValidator:260,267,273`, `RerankerConfig:46`, `DefaultGroupManager:84`, `JDBCGroupDatabase:305`, `SSOLoginModule:211`, `SSOAutoProvisionService:85,179`, `UserDatabase:157`, `JDBCUserDatabase:288,335`, `CookieAuthenticationLoginModule:160`, `ClusterAction:32`, `ExclusionReason:42`, `ToolsOpenApiServlet:214`, `InternalNetworkFilter:119`, `McpTestClient:183,186`, `KnowledgeMcpInitializer:320`). If the test reports a site not on this list, treat it the same way.

- [ ] **Step 3: Fix each site** — classify, then apply the matching shape. Use the class's existing logger; if none, add `private static final Logger LOG = LogManager.getLogger( <Class>.class );` with the module's existing import style (`org.apache.logging.log4j`).

| Class | Sites | Shape |
|---|---|---|
| **Unexpected failure → WARN** | `KnowledgeMcpInitializer:320`, `RestServletBase:150`, `FrontmatterValidateResource:71`, `ConnectorConfigService:426`, `Preferences:182`, `RenderingSubsystemFactory:88`, `SearchSubsystemFactory:155`, `CookieAuthenticationLoginModule:160`, `ExpectedCtrCurveParser:135`, `ClassUtil:115,347` (class-loading probe: check — if it's a "try optional class" probe it is DEBUG), `InternalNetworkFilter:119`, `McpTestClient:183,186` | `LOG.warn( "<what was being attempted> for <identifying value>: {}", e.getMessage() );` — include the input that failed (page name, key, path); add `, e` as last arg when a stack trace is useful (subsystem factories, initializer). |
| **Expected bad input / absent principal → DEBUG** | all `NumberFormatException`, `DateTimeParseException`, `IllegalArgumentException`-from-`valueOf`, `URISyntaxException` (config validation returns an error elsewhere), `NoSuchPrincipalException`, `NoSuchMethodException` (`DefaultVariableManager`) sites | `LOG.debug( "<value> is not a <type>; <fallback taken>: {}", e.getMessage() );` **plus** a one-line comment above stating why the fallback is correct, e.g. `// Absent user is the normal "create" path, not an error.` |

Review Focus 3: no request-parameter parse site may log at WARN — they are triggered by client input. Keep the existing control flow (same return/continue/break) after the log line.

- [ ] **Step 4: Compile + run the guard + the touched modules' tests**

Run: `mvn test -pl wikantik-war -Dtest=NoSwallowedExceptionsTest -q` → PASS.
Then via agent-build (long): `bin/agent-build.sh start a1 -- mvn clean install -DskipITs -T 1C -q` and poll `status`/`wait a1 540` until terminal. Expected: SUCCESS. (If `-T 1C` produces the known wikantik-main security-policy-init cascade, rerun without `-T 1C` before investigating.)

- [ ] **Step 5: Commit**

```bash
git commit -m "fix: log every caught exception; add NoSwallowedExceptionsTest guard" -- wikantik-war/src/test/java/com/wikantik/architecture/NoSwallowedExceptionsTest.java <every modified source file>
```

### Task 5: Deprecated code, test warnings, IT plugin pins (A3)

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/auth/sso/SSOAutoProvisionService.java` (delete deprecated overload ~line 135)
- Modify tests: `SSOAutoProvisionServiceTest`, `SSOAutoProvisionServiceEdgeCasesTest`, `PageCanonicalIdsDaoTest`, `JDBCUserDatabaseTest`, `GroupPermissionTest`, `SystemPageRegistryTest`, `LuceneMissingPageSweepObservabilityTest` (find each with `git ls-files | grep -a <Name>.java`)
- Modify: the 5 IT module poms that use `properties-maven-plugin` / `exec-maven-plugin` without a version → pin in `wikantik-it-tests/pom.xml` `<pluginManagement>` (or the root pom's, wherever the IT poms inherit from).

**Interfaces:** Consumes: the 3-arg `provisionIfNeeded(...)` that `SSOLoginModule` already uses (read its exact signature from the source).

- [ ] **Step 1: Capture the warnings baseline**

Run: `mvn -q -pl wikantik-main,wikantik-it-tests -am test-compile -Dmaven.compiler.showDeprecation=true -Dmaven.compiler.showWarnings=true 2>&1 | grep -a 'WARNING' | sort -u | tee /tmp/claude-1000/-home-jakefear-source-jspwiki/930f1222-efe0-43b1-90e3-a512b9152a9f/scratchpad/warn-before.txt | wc -l`
Expected: > 0 (deprecation warnings in the test files above; "'version' for org.codehaus.mojo:… is missing" for IT poms).

- [ ] **Step 2: Migrate test callers to the 3-arg overload**, keeping each test's assertions identical. For the 2-arg call `svc.provisionIfNeeded( name, profile )` substitute exactly what the deprecated overload passed as the third argument (read its body — it delegates).

- [ ] **Step 3: Delete the deprecated overload** and its javadoc. `git grep -n 'provisionIfNeeded' -- '*.java'` must show only 3-arg calls.

- [ ] **Step 4: Clear the other deprecations** — replace each deprecated API with its documented successor (e.g. `new URL( s )` → `URI.create( s ).toURL()`; deprecated DAO/profile accessors → the non-deprecated accessor the main code uses). Do not add `@SuppressWarnings`.

- [ ] **Step 5: Pin plugin versions** — use the version already resolved in the build: `mvn -q help:effective-pom -pl <one IT module> | grep -a -A2 'properties-maven-plugin\|exec-maven-plugin' | grep -a version`. Declare that version once in pluginManagement.

- [ ] **Step 6: Re-run Step 1** — expected: no deprecation warnings in the listed files, no missing-version warnings. Then `mvn test -pl wikantik-main -Dtest='SSOAutoProvisionService*Test,PageCanonicalIdsDaoTest,JDBCUserDatabaseTest,GroupPermissionTest,SystemPageRegistryTest,LuceneMissingPageSweepObservabilityTest' -q` → PASS.

- [ ] **Step 7: Commit** — `git commit -m "chore: drop deprecated SSO provision overload; clear test deprecations; pin IT plugins" -- <paths>`

### Task 6: Inherited FIXME/TODO triage (A4)

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/attachment/AttachmentServlet.java`; test `AttachmentServletTest` (find/create in wikantik-main test tree)
- Modify: `wikantik-main/src/main/java/com/wikantik/providers/VersioningFileProvider.java`
- Modify: `wikantik-rest/src/main/java/com/wikantik/rest/admin/AgentGradeAuditResource.java` (+ the servlet `AdminAgentGradeAuditServlet` if it owns paging); test `AgentGradeAuditResourceTest` / `AdminAgentGradeAuditServletTest`
- Modify: `wikantik-main/src/test/java/com/wikantik/diff/ContextualDiffProviderTest.java`
- Modify: comment-only edits in the files listed in Step 5.

- [ ] **Step 1: AttachmentServlet — failing test.** Read `AttachmentServlet` around the two FIXMEs ("In case of exceptions should absolutely remove the uploaded file", "Does not delete the received files"). Write a test that drives an upload whose `AttachmentManager.storeAttachment(...)` throws (Mockito `doThrow`), captures the temp file the servlet wrote (spy the file-creation seam the servlet already uses, or point the servlet's upload temp dir at a JUnit `@TempDir`), and asserts the temp dir is empty after the request and the response is an error status.

Run: `mvn test -pl wikantik-main -Dtest=AttachmentServletTest -q` → FAIL (temp file left behind).

- [ ] **Step 2: Fix** — wrap the store in `try { … } finally { if( !stored ) Files.deleteIfExists( tmp ) — logging a WARN if delete fails }`; remove both FIXME comments. Re-run → PASS.

- [ ] **Step 3: VersioningFileProvider "Should log errors"** — read the method under that FIXME; replace silent error paths with `LOG.warn` (context: page name + version). Covered behaviourally by existing `VersioningFileProviderTest`; run it → PASS. Replace "no rollback" FIXMEs with an accurate one-line comment stating the actual guarantee (e.g. `// Not transactional: a failure after the move leaves the previous version in OLD/; see DefaultPageRepository.`) — only if that is what the code does; read it.

- [ ] **Step 4: AgentGradeAudit pagination** — failing test: seed > 1000 pages in the test's page source (mock the page enumeration to return 1,203 names), request the audit, assert every page is accounted for (sum across pages/cursor equals 1,203) and a `next`/cursor field is present until the last page. Implement offset/cursor paging consistent with the other admin list endpoints in `wikantik-rest` (read one — e.g. `AdminContentResource` — and copy its `limit`/`offset` parameter names and response fields). Remove the TODO. Run → PASS.

- [ ] **Step 5: Comment cleanup** — for each remaining TODO/FIXME (list: `git grep -nE 'TODO|FIXME' -- '*/src/main/java/*.java'`), decide: (a) concern no longer applies → delete the comment; (b) still true → rewrite as a plain explanatory comment without TODO/FIXME; (c) real, small (< 30 min) → fix with a test. Keep `HubDiscoveryService`'s emitted `<!-- TODO: describe this hub -->` (it is generated page content, asserted by a test). In `ContextualDiffProviderTest:168`: if the test below the comment runs and passes, delete the comment; if it is commented out, either make it pass or delete the dead test body. Record each decision in the task report (file:line → a/b/c).

- [ ] **Step 6: Module tests** — `bin/agent-build.sh start a4 -- mvn clean install -DskipITs -pl wikantik-main,wikantik-rest -am -q`, poll to SUCCESS.

- [ ] **Step 7: Commit** — `git commit -m "fix: clean up failed attachment uploads; page agent-grade audit; retire stale FIXMEs" -- <paths>`

### Task 7: Security close-out + issue hygiene (A7, A11)

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/derived/ConnectorConfigService.java` (`endpointHostErrors`)
- Test: `wikantik-main/src/test/java/com/wikantik/derived/ConnectorConfigServiceTest.java`

- [ ] **Step 1: Failing tests** — in `ConnectorConfigServiceTest`, next to the existing host-binding test (added by `8d1b6a8c98`; copy its arrange block), add:
  - same host, scheme `https`→`http` while credentials exist → update refused, error names `base_url`;
  - same host, port `443`→`8443` → refused;
  - same host, explicit default port (`https://h` → `https://h:443`) → **allowed** (normalise default ports);
  - host change with **no** stored credentials → allowed (unchanged behaviour).

Run: `mvn test -pl wikantik-main -Dtest=ConnectorConfigServiceTest -q` → the scheme and port cases FAIL.

- [ ] **Step 2: Implement** — compare an origin tuple `(scheme lower-case, host lower-case, effective port)` where effective port = explicit port or 443/80 by scheme. Re-run → PASS.

- [ ] **Step 3: Commit** — `git commit -m "fix(security): bind connector credentials to scheme+host+port" -- <2 paths>`

- [ ] **Step 4: Issue comments** (no closing yet — closure is Task 24):
  - `gh issue comment 64 --body "Fixed in 8d1b6a8c98 (v2.4.26); hardened to scheme+host+port in <sha>. Closing with the next release."`
  - `gh issue comment 63 --body "Code fixed in 8d1b6a8c98 (v2.4.26). Legacy kg_nodes with null source_page now fail closed (hidden from the public ontology) until a prod KG re-materialisation; closing after that runs."`
  - `gh issue comment 49 --body "Deferred during the 2026-10 fit-and-finish pass: not reproducible; needs the next failure's server log."`
  - `gh issue comment 44 --body "Deferred: blocked on billable cloud credentials/apply."` and the same for 45, 46.
  - `gh issue comment 62 --body "Out of scope for the 2026-10 fit-and-finish pass (new feature, touches every admin MCP call)."`

### Task 8: #47 small items (A8)

**Files:** read `gh issue view 47` first; it names each item's files. Expected set: the container entrypoint script + a regression test under `bin/tests/` or the module that owns it; `docker-compose*.yml` / `deploy/**` image tags; a vitest beside `CapabilitiesProvider` in `wikantik-frontend/src`; the javadoc mentioning `CF-Connecting-IP`.

- [ ] **Step 1:** For each actionable item: write the failing test first where the item is behavioural (entrypoint guard → `bin/tests/test-*.sh` style suite; `CapabilitiesProvider` rejection → vitest asserting the provider's state when `/api/capabilities` rejects); make it pass; for pinning, replace floating tags (`:latest`, major-only) with the exact version currently deployed (`grep -a image: docker-compose*.yml deploy -r`); fix the javadoc to describe the current header handling (read the filter that consumes it).
- [ ] **Step 2:** Run the new tests (`bash bin/tests/<suite>`, `cd wikantik-frontend && npx vitest run <file>`).
- [ ] **Step 3:** Commit — `git commit -m "chore: close out #47 small follow-ups" -- <paths>`; `gh issue comment 47` listing what landed (with sha) and what remains.

### Task 9: Shipped default wiki pages (spec §4.6)

**Files:**
- Delete from `wikantik-wikipages/en/src/main/resources/` **and** `docs/wikantik-pages/`: LeftMenu, LeftMenuFooter, MoreMenu, TitleBox, RecentArticlesTemplate, CSSRibbon, CSSStripedText, CSSThemeCleanBlue, CSSThemeDark, CSSBackgroundPatterns, CSSInstagramFilters, CSSPrettifyThemePrism, CSSPrettifyThemeTomorrowNightBlue, ApprovalRequiredForUserProfiles, CopyrightNotice, WikiWiki, InstallationTips, Community, EditFindAndReplaceHelp (see Step 2)
- Rewrite in both places: About, Main (en jar only — `docs/wikantik-pages/Main.md` is generated, see Step 5), EditPageHelp, TextFormattingRules, SearchPageHelp, LoginHelp, SystemInfo, OneMinuteWiki, WikiName, PageAlias
- Light touch: PageIndex, RecentChanges, FullRecentChanges, UnusedPages, UndefinedPages, RejectedMessage, WikiEtiquette, SandBox
- Delete dirs: `wikantik-wikipages/es`, `ru`, `zh_CN`
- Modify: `wikantik-wikipages/pom.xml` (modules), `wikantik-bom/pom.xml` (drop `-es`/`-ru`), root `pom.xml` (`build-wikipages-zips` profile + RAT excludes naming those dirs), delete `wikantik-wikipages/assembly/zip.xml` if only that profile used it, `wikantik-wikipages/src/site/markdown/index.md` (+ `en/src/site` if present — remove the false "extracted on first deployment" claim)
- Modify ITs: `wikantik-it-tests/wikantik-selenide-tests/src/**/McpSystemPageProtectionIT.java`, `wikantik-it-tests/wikantik-it-test-rest/src/**/SitemapIT.java` (lines ~114–117), and the IT test-repo `wikantik-it-tests/wikantik-selenide-tests/src/test/resources/test-repo/` (delete the same pages there)
- Test: a RecentArticles missing-template test in wikantik-main

**Interfaces:** Consumes `DefaultSystemPageRegistry` (anchor `About.md`; all siblings in the en jar are system pages).

- [ ] **Step 1: Missing-template safety (Review Focus 5) — failing-first check.** Find `RecentArticlesManager.TEMPLATE_PAGE_NAME` users (`git grep -n TEMPLATE_PAGE_NAME`). Write a test (in the existing `DefaultRecentArticlesManager*Test` file) that renders recent articles on an engine whose page store has **no** `RecentArticlesTemplate` page and asserts non-empty default output and no exception. Run. If it already passes, keep it as a regression guard and note "passed first run — guard only"; if it fails, fix the manager to fall back to its default format.

- [ ] **Step 2: Editor find/replace check** — `git grep -n -i 'search\|replace\|openSearchPanel' -- wikantik-frontend/src/components/editor`. If CodeMirror's search keymap is wired, rewrite EditFindAndReplaceHelp as a short page (Mod-F / Mod-Alt-F style keys as actually bound); otherwise delete it.

- [ ] **Step 3: Deletions** (both locations + IT test-repo), languages, poms, profile, site docs. Then update the ITs: `McpSystemPageProtectionIT.SYSTEM_PAGE = "TextFormattingRules"` (survives as a system page); in `SitemapIT` replace the four absent-page assertions with assertions for surviving system pages (`TextFormattingRules`, `SystemInfo`, `PageIndex` absent from sitemap).

- [ ] **Step 4: Rewrites** — Markdown only (no JSPWiki `!heading`, `__bold__`, `[[link]`, `{{mono}}`). Content sources: the components named in the spec §4.4 bullet list and recon (editor = `wikantik-frontend/src/components/editor/*` + `PageEditor.jsx`; search = `QuickOverlay`, `SearchResultsPage`, `SearchFacets`; login = `LoginForm`, `SsoLoginButton`, `ResetPassword`, `ChangePassword`). Each help page ≤ ~80 lines, links to the GitHub user docs for depth (`https://github.com/jakefearsd/wikantik/blob/main/docs/user/<Doc>.md` — these files are created by Tasks 19–20; use exactly: `Editing.md`, `Search.md`, `Reading.md`, `Linking.md`, `AccountAndLogin.md`, `Frontmatter.md`, `MathematicalNotation.md`). SystemInfo: list every `[{$var}]` it uses; verify each resolves (`git grep -n '"<var>"' -- wikantik-main/src/main/java/com/wikantik/variables`); drop any that do not. RejectedMessage: confirm the variable name the SpamFilter sets. Keep the frontmatter already present in each `docs/wikantik-pages/` copy (`cluster`, `canonical_id`, …) byte-identical; only the body changes. About must keep its filename.

- [ ] **Step 5: Main** — rewrite only `wikantik-wikipages/en/src/main/resources/Main.md` (welcome + links to surviving help pages + docs). Do **not** touch `docs/wikantik-pages/Main.md`; if `Main.pins.yaml` pins a deleted page, remove that pin and regenerate with the generator `MainPageRegressionTest` names (read the test's failure message for the command).

- [ ] **Step 6: Build + tests** — `bin/agent-build.sh start wp -- mvn clean install -DskipITs -q`, poll to SUCCESS (catches `MainPageRegressionTest`, `SystemPageRegistryTest`, BOM resolution). Then `bin/run-tests.sh --module <selenide module name>` is **not** run here — the IT gate runs once in Task 23.

- [ ] **Step 7: Record for Task 24** — write `scratchpad/prod-page-changes.md`: deleted page names; rewritten page names; corpus pages edited by Task 3 Step 5.

- [ ] **Step 8: Commit** — two commits: `chore(wikipages): drop es/ru/zh_CN and JSPWiki-era system pages` and `docs(wikipages): rewrite help pages for the React UI`, each path-limited.

### Task 10: PMD complexity burn-down — timeboxed 3 h of agent work (A5)

**Files:** `build-support/pmd-complexity-baseline.properties` + the classes below and their tests.

Order (stop at the timebox; each item is one commit):
1. `com.wikantik.knowledge.extraction.AsyncEntityExtractionListener=ExcessiveParameterList` — introduce a `record` parameter object for the over-long constructor/factory; update callers (`git grep -n 'new AsyncEntityExtractionListener'`).
2. `com.wikantik.knowledge.subsystem.KnowledgeWiringHelper=ExcessiveParameterList` — same technique.
3. `com.wikantik.search.hybrid.LuceneBm25ChunkIndex=GodClass` (#55) — extract the query-building/scoring cohesive group into a package-private collaborator; comment on #55 with the sha; close #55 if the entry is gone.
4. `com.wikantik.mcp.tools.McpToolUtils=GodClass`, 5. `com.wikantik.util.ClassUtil=GodClass`, 6. `com.wikantik.tools.ToolsOpenApiServlet=GodClass`.

Per item:
- [ ] **Step 1:** Remove the class's entry (or just the rule) from the baseline file.
- [ ] **Step 2:** Run `mvn -q pmd:check -Pcomplexity-gate -pl <module>` → FAIL naming the class (this is the "red").
- [ ] **Step 3:** Refactor structurally — move methods, introduce a collaborator or parameter object. No behaviour change; existing tests unmodified except constructor/wiring plumbing.
- [ ] **Step 4:** `mvn -q pmd:check -Pcomplexity-gate -pl <module>` → PASS; `mvn test -pl <module> -q` → PASS.
- [ ] **Step 5:** Commit — `git commit -m "refactor: burn down PMD baseline — <Class>" -- <paths>`.

If a refactor needs more than ~45 min or would change a public API used across modules, restore its baseline entry, stop that item, and record why in the report.

### Task 11: Coverage floors (A6)

**Files:** tests in `wikantik-api`, `wikantik-jdbc`, `wikantik-ingest`, `wikantik-connectors`, `wikantik-extract-cli`; each module's `pom.xml` `<wikantik.coverage.line.minimum>`.

Per module (in that order):
- [ ] **Step 1:** Measure: `mvn -q clean install -Pcoverage -DskipITs -pl <module> -am` then read `<module>/target/site/jacoco/jacoco.csv`; list the 5 classes with the most missed lines.
- [ ] **Step 2:** Write tests for the uncovered **behaviour** of those classes (real assertions on outputs/side effects; no `if (x.size() > 0) assert…` guards; for DB code use `@RequiresPostgres` + `PostgresTestDb`).
- [ ] **Step 3:** Re-measure; set the floor to the new value rounded down to a whole percent (cap 0.90).
- [ ] **Step 4:** `mvn -q install -Pcoverage -DskipITs -pl <module>` → PASS (the floor check runs).
- [ ] **Step 5:** Commit — `git commit -m "test(<module>): raise line-coverage floor to 0.NN" -- <paths>`.

Target: `wikantik-api` ≥ 0.80; others +3 points each or to 0.85, whichever is higher. Stop a module when the remaining misses are trivial getters/records.

### Task 12: Dependency sweep (A9)

- [ ] **Step 1:** Follow the existing workflow in `docs/developer/dependency-upgrade-log.md` (versions-plugin `display-dependency-updates` / `display-plugin-updates` honouring `build-support/versions-stable-rules.xml`; OSV.dev querybatch over `mvn dependency:list`; `npm outdated` + `npm audit` in `wikantik-frontend`).
- [ ] **Step 2:** Apply patch/minor bumps only; respect known holds (apache parent 39 forces release=8; libthrift 0.24 breaks Jena TDB2 — stay on 0.23).
- [ ] **Step 3:** `bin/agent-build.sh start deps -- mvn clean install -DskipITs -q` → SUCCESS; `cd wikantik-frontend && npm ci --ignore-scripts && npm run build && npx vitest run` → PASS.
- [ ] **Step 4:** Append a dated entry to `docs/developer/dependency-upgrade-log.md`; commit — `git commit -m "build: dependency sweep 2026-10" -- <poms> wikantik-frontend/package.json wikantik-frontend/package-lock.json docs/developer/dependency-upgrade-log.md`.

---

## Phase 1 — Frontend lane (runs concurrently with the code lane)

### Task 13: Undefined CSS tokens (A2)

**Files:**
- Create: `wikantik-frontend/src/styles/tokensUsage.test.js`
- Modify: `wikantik-frontend/src/styles/globals.css` (aliases only where needed), and the consumers: `MentionChunks.jsx`, `pagegraph/graph.css`, `ExistingHubDrilldown.jsx`, `ContentEmbeddingsTab.jsx`, `PolicyGrantFormModal`, `RevealedTokenModal`, `AddConnectorWizard`, `GroupFormModal`, `UserFormModal`, `AdminAuditPage`, `AuditRecordModal`, `BacklinksPanel`, `SimilarPagesPanel`, `HubProposalsTab` (locate with `git grep -n -e '--<token>' -- wikantik-frontend/src`), `globals.css` uses of `--radius`/`--shadow-lg`.

- [ ] **Step 1: Failing test**

```js
/** Every var(--token) read anywhere under src/ must be declared in globals.css (or be a known dynamic family). */
import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';

const SRC = resolve(process.cwd(), 'src');
const css = readFileSync(join(SRC, 'styles/globals.css'), 'utf8');
const declared = new Set([...css.matchAll(/(--[\w-]+)\s*:/g)].map((m) => m[1]));
// Built at runtime from a callout kind, e.g. `--callout-${kind}`; declared per kind in globals.css.
const DYNAMIC = [/^--callout-$/, /^--cm-callout-tint$/];

function walk(dir) {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) return walk(p);
    return /\.(css|jsx?|tsx?)$/.test(name) && !/\.test\./.test(name) ? [p] : [];
  });
}

describe('CSS custom property usage', () => {
  const used = new Map();
  for (const file of walk(SRC)) {
    for (const m of readFileSync(file, 'utf8').matchAll(/var\(\s*(--[\w-]+)/g)) {
      if (!used.has(m[1])) used.set(m[1], file.slice(SRC.length + 1));
    }
  }

  it('finds usages', () => {
    expect(used.size).toBeGreaterThan(50);
  });

  it('reads only tokens globals.css declares', () => {
    const undefinedTokens = [...used.entries()]
      .filter(([t]) => !declared.has(t) && !DYNAMIC.some((re) => re.test(t)))
      .map(([t, f]) => `${t} (${f})`)
      .sort();
    expect(undefinedTokens).toEqual([]);
  });
});
```

Run: `cd wikantik-frontend && npx vitest run src/styles/tokensUsage.test.js`
Expected: FAIL listing the 15 tokens (`--accent-soft`, `--bg-base`, `--bg-hover`, `--bg-warning-subtle`, `--border-color`, `--color-bg-subtle`, `--color-border`, `--color-muted`, `--color-surface`, `--color-surface-alt`, `--color-text-muted`, `--radius`, `--shadow-lg`, `--surface`, `--surface-secondary`). If other `--callout-*`/`--code-*` names appear, check they are declared or dynamic before adding to `DYNAMIC`.

- [ ] **Step 2: Light-theme parity table (Review Focus 4)** — for each token, record the fallback value currently rendered in light mode (the `var(--x, <fallback>)` literal, or "none → property dropped") and the replacement's light value from `:root`. Replace uses with existing tokens:

| Undefined | Replace with |
|---|---|
| `--surface`, `--color-surface` | `--bg-elevated` |
| `--surface-secondary`, `--color-surface-alt`, `--color-bg-subtle` | `--bg-sidebar` |
| `--border-color`, `--color-border` | `--border` |
| `--color-muted`, `--color-text-muted` | `--text-secondary` (AA-safe; `--text-muted` fails AA on small text per `tokens.test.js`) |
| `--bg-base` | `--bg` |
| `--bg-warning-subtle` | `--warning-bg` |
| `--radius` | `--radius-md` |
| `--shadow-lg` | `--shadow-strong` |
| `--bg-hover`, `--accent-soft` | **declare** in both `:root` and `[data-theme="dark"]`: `--bg-hover` = the light/dark value of `--row-selected-bg`'s family (pick a subtle tint distinct from selection: `color-mix(in srgb, var(--text) 6%, transparent)`); `--accent-soft: color-mix(in srgb, var(--accent) 14%, transparent)` |

Where the parity table shows a visible light-mode change (e.g. a pure `#fff` fallback vs a cream `--bg-elevated`), prefer the token anyway (it is the design system's intent) but list it in the report for the browser check.

- [ ] **Step 3: Run** — `npx vitest run src/styles/` → PASS (both `tokens.test.js` and the new test); `npx eslint .` → clean; `npm run test:coverage` → PASS.
- [ ] **Step 4: Commit** — `git commit -m "fix(ui): replace undefined CSS tokens; guard every var() usage" -- <paths>`

---

## Phase 1 — Docs lane (tasks run in parallel; disjoint file sets; no Maven)

Each docs task: follow the **Code-truth verification procedure** (Global Constraints), end with the claims table, run `python3 bin/check-md-links.py` to zero, commit path-limited.

### Task 15: Admin merges + ProjectReference split

**Files (owned exclusively):**
- Create: `docs/admin/PostgreSQL.md` ← merge `docs/admin/PostgreSQLLocalDeployment.md` (canonical base), `docs/admin/DevelopingWithPostgresql.md`, `docs/admin/RelationalUserDatabase.md`; delete the three.
- Create: `docs/admin/DatabaseMigrations.md` ← merge `docs/admin/DatabaseUpdates.md` + `docs/admin/ProductionDBWorkflow.md` (resolve every PENDING status against `bin/db/` and migrations; state what is actually implemented); delete both.
- Modify: `docs/admin/DockerDeployment.md` ← absorb `docs/admin/production-container-architecture.md` (topology + lifecycle sections); delete it.
- Modify: `docs/admin/WikantikOperations.md` ← absorb `docs/admin/ObservabilityDesign.md` (what is shipped: `/api/health`, `/metrics` IP restriction, correlation IDs, structured log envelope; jakemon owns monitoring) and the **admin runbooks** from `docs/developer/ProjectReference.md` (remote/container deployment, load-test operations, entity extractor); delete ObservabilityDesign.
- Modify: `docs/developer/ProjectReference.md` — keep only design-doc status blocks; add a pointer line to `docs/admin/WikantikOperations.md`.
- Modify: `docs/admin/SeoAndCrawling.md` — drop the one-time session-record half; fold in the live facts from `docs/archive/Sitemap.md` / `SitemapOptimization.md` (the actual sitemap property keys — verify in the ini — and "Google ignores changefreq/priority").
- Modify: `CLAUDE.md` — only the lines that link ProjectReference / the merged files.
- Modify: every referrer of the deleted files (grep; includes `bin/*`, `docs/superpowers/**` path rewrites).

- [ ] **Step 1:** Do the merges, writing the merged docs as one coherent guide each (not concatenations): opening paragraph (audience + scope), prerequisites, task sections, troubleshooting.
- [ ] **Step 2:** Code-truth verify every claim in the five resulting docs.
- [ ] **Step 3:** Rewrite referrers; `python3 bin/check-md-links.py` → clean.
- [ ] **Step 4:** Commit — `git commit -m "docs(admin): merge PostgreSQL, migrations, container and ops docs" -- <paths>`.

### Task 16: Admin verification — set A

**Files (owned):** `docs/admin/{ApiKeys,AuditLog,AwsAccountSetup,AzureAccountSetup,GcpAccountSetup,BackupAndRecovery,CloudDeployment,Connectors,CostTiers,GettingStartedGuide,HubDiscovery,IndexRebuild}.md`

- [ ] **Step 1:** Verify every claim. Known drift to fix: ApiKeys "known gap" callout (V058 widened the scope check — describe current `mcp_read`/`mcp` scopes and how to issue each); AzureAccountSetup must say plainly there is no `deploy/azure` Terraform module (what the doc does give you, and what you must do by hand). GettingStartedGuide becomes the admin entry point: link it first from Task 22's index; make sure its quick start matches `bin/deploy-local.sh` and `bin/container.sh` exactly.
- [ ] **Step 2:** Link check; commit — `git commit -m "docs(admin): verify deployment, keys, audit, connectors docs against code" -- <paths>`.

### Task 17: Admin verification — set B

**Files (owned):** `docs/admin/{IndexingSupport,KgInclusionPolicy,LoggingConfig,OntologyManagement,PageOwnership,RagContextBundle,RetrievalQuality,ScimProvisioning,SendingEmailFromTheWiki,SingleSignOn}.md`, `docs/admin/templates/{PrivacyPolicy,TermsOfService}.md`

- [ ] **Step 1:** Verify every claim (SSO: `wikantik.sso.*` keys + JAAS bridge behaviour, `/sso/login`, `/sso/callback`, identity claim default `sub`; SCIM: `wikantik.scim.token`, endpoints; RagContextBundle: `/api/bundle`, `/api/briefing`, `wikantik.bundle.*` keys and defaults). Templates: add a one-line header saying they are operator templates with placeholders to replace.
- [ ] **Step 2:** Link check; commit — `git commit -m "docs(admin): verify integration and policy docs against code" -- <paths>`.

### Task 18: New admin docs

**Files (owned, create):** `docs/admin/AdminPanel.md`, `docs/admin/Security.md`, `docs/admin/McpAgents.md`, `docs/admin/DerivedPagesAndIngest.md`, `docs/admin/DriftDashboard.md`, `docs/admin/ContentIntelligence.md`

Required content (each verified against code):
- [ ] **AdminPanel.md** — one section per admin SPA route under `/admin/*` (enumerate from `wikantik-frontend/src/main.jsx` + the admin nav component): what it is for, what you can do there, which REST endpoint backs it (from `web.xml`), link to the deep doc where one exists.
- [ ] **Security.md** — authentication options (DB, LDAP, container, SSO; remember-me cookie + SameSite), authorisation (policy grants table + `/admin/security`, groups, the Admin **group** requirement for `/admin/*`, page ACL syntax `[{ALLOW view Admin}]`), bootstrap admin (`wikantik.admin.bootstrap`), API-key scopes (`mcp_read`, `mcp`), rate limiting (tiers and exempt paths — read `RateLimitFilter`), SSRF egress guard (`wikantik.connectors.egress.allowPrivate`), viewer-sensitive render-cache isolation, `JDBCPlugin` flag (`wikantik.plugin.jdbc.enabled`), password policy (NIST 800-63B), audit log pointer, public RDF surfaces' public/restricted split. Link `SECURITY.md` for reporting vulnerabilities.
- [ ] **McpAgents.md** — owns the tool counts (derive from `McpProtocolIT.EXPECTED_TOOLS` and the knowledge initializer; state "as of 2.4.53"); issuing a key; endpoint URLs; session protocol (`initialize` → `Mcp-Session-Id` → `notifications/initialized`); example client config for Claude Code (`.mcp.json` shape) and a curl session; `/tools/*` OpenAPI for non-MCP clients; the full tool catalogue as a table (name, one-line purpose, read/write) generated from `wikantik-mcp-instructions.txt` + the knowledge tool registrations.
- [ ] **DerivedPagesAndIngest.md** — what a derived page is (`derived_from` frontmatter; body machine-owned, ADR-0004), `POST /api/ingest` (multipart, `createPages`), `bin/ingest-documents.sh --help`, reflow + status (`/admin/derived/*`), how connectors produce derived pages (link Connectors.md).
- [ ] **DriftDashboard.md** — every panel/category on `/admin/drift` (read the servlet + SPA page), including structural conflicts (`StructuralConflict.Kind` values) and `/admin/drift/citations`; what to do about each.
- [ ] **ContentIntelligence.md** — `/admin/insights/{acquisition,backlog,ingest}`, what data feeds it, opportunity backlog + snooze (and the MCP pair), how to ingest search-visibility data.
- [ ] **Step final:** Link check; commit — `git commit -m "docs(admin): add admin panel, security, MCP, derived-pages, drift, insights guides" -- <paths>`.

### Task 19: User docs — existing + Editing, Search, Reading

**Files (owned):** `docs/user/{CommentsAndMentions,Frontmatter,MathematicalNotation,PersonalZone}.md` (verify), `git mv docs/user/MarkdownLinks.md docs/user/Linking.md` (extend), create `docs/user/{Editing,Search,Reading}.md`.

- [ ] **Linking.md** — `[text](PageName)` internal links, `[[Page]]` / `[[Page|label]]` / `[[Page#Heading]]` wikilinks, `![[Page]]` embeds (`GET /api/pages/{name}/embed`), how missing targets render, plugin syntax `[{Plugin}]`.
- [ ] **Editing.md** — create/edit flow, toolbar and every shortcut actually bound (read `EditorToolbar` + the editor keymap — list exact keys), live preview (Mod-E, Markdown pages only), wikilink completion, @mentions, unlinked-mentions rail, attachments upload, page templates (`GET /api/page-templates`), drafts, change note, structured frontmatter editor with live validation and Save-gating, math validation messages, page ACLs.
- [ ] **Search.md** — quick switcher/command palette (open key, fuzzy match, commands, create page, Ctrl/Cmd+Enter new tab), full-text search page, facets (cluster, tags, type, Modified), which query syntax actually works (read `SearchResource` and the Lucene query parser config — do not copy the old JSPWiki help).
- [ ] **Reading.md** — TOC, breadcrumbs, backlinks, link previews, similar pages, cluster status badge, history + diff, derived-page provenance banner, Page Graph (`/page-graph?focus=`) vs Knowledge Graph (`/knowledge-graph`, only when enabled — check `capabilities.knowledgeGraph`), dark mode, export.
- [ ] **Step final:** Link check; commit — `git commit -m "docs(user): verify user docs; add editing, search, reading, linking guides" -- <paths>`.

### Task 20: User docs — ClustersAndHubs, Citations, ObsidianImportExport, AccountAndLogin

**Files (owned, create):** `docs/user/{ClustersAndHubs,Citations,ObsidianImportExport,AccountAndLogin}.md`

- [ ] **ClustersAndHubs.md** — author view: a cluster exists iff exactly one `type: hub` page declares `cluster: <path>`; joining a cluster (scalar or list; first = primary and what "primary" drives); sub-clusters `parent/child` one level; what the cluster status badge states mean; who to ask for a rename (`/admin/clusters/rename`).
- [ ] **Citations.md** — `cite://` markup syntax (read the parser in `com.wikantik.citation`), what staleness grades mean, where stale citations surface.
- [ ] **ObsidianImportExport.md** — export (`GET /api/export`, `export` permission, what the zip contains), import (`POST /api/import/obsidian/{plan,apply}`, plan-hash confirmation, job status, `createPages` permission, how links/embeds/frontmatter are converted — read `com.wikantik.importer`), limits.
- [ ] **AccountAndLogin.md** — login, SSO button, reset password, forced change-password on first login, preferences, personal API keys (`MyApiKeys`), sign out.
- [ ] **Step final:** Link check; commit — `git commit -m "docs(user): add clusters, citations, Obsidian, account guides" -- <paths>`.

### Task 21: Developer docs

**Files (owned):** `docs/developer/{Building,CodeQuality,CI,FrontendArchitecture,LoadTesting,ScalingCharacterization}.md` (verify/refresh), create `docs/developer/{Architecture,Testing}.md`, modify `CONTRIBUTING.md`.

- [ ] **Building.md** — rewrite from MvnCheatSheet: prerequisites table (versions verified), standard / fast / parallel builds, `-DskipTests` vs `-Dmaven.test.skip` trap, `bin/agent-build.sh`, frontend build, local deploy loop (`bin/deploy-local.sh`, `bin/redeploy.sh`); drop obsolete `eclipse:eclipse`/`idea:idea` rows and the ASF header.
- [ ] **Architecture.md** — owns module/servlet counts: module map (one line each, dependency direction rules: `wikantik-insights` depends on neither api nor main, `wikantik-jdbc` is the only DB primitive, MCP core cycle-break), core components (WikiEngine, managers, providers, events), rendering pipeline, Page Graph vs Knowledge Graph vs citations, link ADRs. Counts derived from `web.xml` (`/api/*` and `/admin/*` servlet + url-pattern counts) at time of writing.
- [ ] **Testing.md** — unit vs IT layout, `PostgresTestDb`/`@RequiresPostgres`/`FaultInjectingDataSource`, no hand-written `CREATE TABLE`, `bin/run-tests.sh --parallel 4` as the canonical gate, Docker/embedder requirements, `--module dense`, known flake handling, coverage ratchets, PMD gate, frontend vitest/ESLint.
- [ ] **CodeQuality.md** — replace June numbers with current ratchet facts (floors per module from the poms, PMD baseline entry count, SpotBugs config) — numbers that will change go in one table with a "measured on" date.
- [ ] **CI.md** — what each workflow in `.github/workflows/` runs and when (note the Java suite runs weekly only; self-hosted runner jobs are dormant), including the new link-check step.
- [ ] **FrontendArchitecture.md / LoadTesting.md / ScalingCharacterization.md** — verify; ScalingCharacterization gets a "dated study (2026-05)" banner line.
- [ ] **CONTRIBUTING.md** — point at `docs/developer/Building.md`, `Testing.md`, `Architecture.md`; keep its process content.
- [ ] **Step final:** Link check; commit — `git commit -m "docs(developer): building, architecture, testing; refresh quality + CI docs" -- <paths>`.

### Task 22: README + docs index (after Tasks 15–21)

**Files (owned):** `README.md`, create `docs/README.md`, modify `CLAUDE.md` (only its doc-path links and the "Prerequisites"/doc pointers that moved), `ROADMAP.md` (fix the ArchitectureCritique "live self-review" framing → archive).

- [ ] **docs/README.md** — sections: **Users** (Reading, Editing, Linking, Search, Frontmatter, ClustersAndHubs, MathematicalNotation, CommentsAndMentions, Citations, PersonalZone, AccountAndLogin, ObsidianImportExport), **Admins** grouped: Install & deploy (GettingStartedGuide first, PostgreSQL, DockerDeployment, CloudDeployment + account setups, DatabaseMigrations, BackupAndRecovery), Configure (ConfigurationReference, CostTiers, LoggingConfig, SendingEmailFromTheWiki), Secure (Security, SingleSignOn, ScimProvisioning, ApiKeys, AuditLog, PageOwnership), Integrate (McpAgents, RagContextBundle, IndexingSupport, Connectors, DerivedPagesAndIngest), Operate (WikantikOperations, AdminPanel, IndexRebuild, DriftDashboard, RetrievalQuality, KgInclusionPolicy, OntologyManagement, HubDiscovery, ContentIntelligence, SeoAndCrawling), Templates; **Developers** (Building, Testing, Architecture, FrontendArchitecture, CodeQuality, CI, LoadTesting, ScalingCharacterization, ProjectReference, dependency-upgrade-log, ADRs, design specs); **Archive** (one line each). One-line description per entry, written from the doc's actual content.
- [ ] **README.md** — ≤ ~150 lines: tagline, badges (verify each badge URL/version), what it is (2 paragraphs), key capabilities (one line each, each linking its doc; no counts except via link to McpAgents/Architecture), Why Wikantik table (verify each Wikantik cell), Mermaid architecture diagram (verify every path label against web.xml; drop counts from node labels), quick start (≤ 10 lines: docker path + link to GettingStartedGuide), Documentation (links to the three index sections), Roadmap/Changelog/License/Contributing/Security links.
- [ ] **CLAUDE.md** — update any remaining doc paths; do not change its rules.
- [ ] **Step final:** `python3 bin/check-md-links.py` → clean; commit — `git commit -m "docs: front-door README and docs index" -- README.md docs/README.md CLAUDE.md ROADMAP.md`.

---

## Phase 2 — Gate and review

### Task 23: Full gate, browser pass, independent drift audit, code review

- [ ] **Step 1: Full gate** — `bin/agent-build.sh start gate -- bin/run-tests.sh --parallel 4`; poll to terminal. Expected SUCCESS. Sum unit totals from `phase1-unit.log` (not the last-module line). Any red → `superpowers:systematic-debugging`, fix, re-run; never defer as pre-existing.
- [ ] **Step 2:** `bin/agent-build.sh start cov -- mvn clean install -Pcoverage -DskipITs`; `mvn -q pmd:check -Pcomplexity-gate`; `cd wikantik-frontend && npx eslint . && npm run test:coverage`; `python3 bin/check-md-links.py`; `for s in bin/tests/test-*.sh; do bash "$s" || echo "FAIL $s"; done`. All green.
- [ ] **Step 3: Browser pass** (local deploy: `mvn clean install -DskipTests -T 1C && bin/redeploy.sh`) — light + dark: admin modals (PolicyGrant, Group, User, RevealedToken), AddConnectorWizard, Audit page, Backlinks/SimilarPages panels, page graph hover, hub proposals, embeddings tab, mentions page; and the rewritten help pages (Main, About, EditPageHelp, TextFormattingRules, SearchPageHelp, LoginHelp, SystemInfo) render as Markdown with no raw JSPWiki markup and no `[{$…}]` left unresolved. Screenshot any defect, fix, recheck.
- [ ] **Step 4: Independent drift audit** — a fresh agent picks 40 random concrete claims across `docs/user`, `docs/admin`, `docs/developer`, README, re-derives each from source without reading the claims tables, reports mismatches; fix all.
- [ ] **Step 5: Code review** — `superpowers:requesting-code-review` over `57c3b75dad..HEAD`; address findings.

## Phase 3 — Production (each step only after explicit user confirmation)

### Task 24: Production steps

- [ ] **D1 — prod page store.** From `scratchpad/prod-page-changes.md`: for each page, read the **prod** copy first via MCP (`read_page`/`get_page`), then apply the deletion (`delete_pages`) or body rewrite (`update_page`, preserving prod's frontmatter). Includes Task 3 Step 5 corpus link fixes if the prod copy has the same link. Confirm with the user before running; report per-page result.
- [ ] **D2 — KG re-materialisation (#63).** Confirm with the user; run the documented rebuild (per `docs/admin/WikantikOperations.md` / `/admin/ontology/rebuild` after the KG step); verify `/sparql` entity count recovered and a known restricted-page entity is absent; close #63 citing `8d1b6a8c98`.
- [ ] **D3 — patch release.** Confirm with the user; `bin/cut-release.sh` (finish with `git push origin main vX.Y.Z` if it stops at the no-TTY push prompt); deploy per `docs/admin/DockerDeployment.md` / `bin/deploy-release.sh`; close #64 (and #55 if Task 10 removed its entry).
