# Dependency Upgrade Log — 2026-03-14

Each upgrade was applied individually and tested with
`mvn clean install -T 1C -DskipITs -Dmaven.javadoc.skip=true`
(parallel build, all unit tests, skip integration tests). Full green baseline
confirmed before any changes.

## Upgrades Applied (all passed)

| # | Dependency | From | To | Source |
|---|-----------|------|----|--------|
| 1 | mockito | 5.20.0 | 5.21.0 | Dependabot PR #6 |
| 2 | jakarta.xml.bind-api | 4.0.4 | 4.0.5 | Dependabot PR #9 |
| 3 | maven-jar-plugin | 3.4.2 | 3.5.0 | Dependabot PR #10 |
| 4 | maven-resources-plugin | 3.3.1 | 3.4.0 | Dependabot PR #8 |
| 5 | maven-source-plugin | 3.3.1 | 3.4.0 | Dependabot PR #7 |
| 6 | commons-text | 1.14.0 | 1.15.0 | Maven Central check |
| 7 | snakeyaml | 2.2 | 2.4 | Maven Central check |
| 8 | lucene | 10.3.2 | 10.4.0 | Maven Central check |
| 9 | tomcat (embedded) | 11.0.14 | 11.0.18 | Maven Central check |

## Upgrades Rejected

| Dependency | From | To | Reason |
|-----------|------|----|--------|
| commons-fileupload2 | 2.0.0-M4 | 2.0.0-M5 | Breaking API change: `setFileSizeMax(int)` removed in M5. Compilation failure in `AttachmentServlet.java:406`. Needs code changes. |
| log4j | 2.25.2 | 3.0.0-beta2 | Major version jump (beta), not a minor upgrade |
| slf4j | 2.0.17 | 2.1.0-alpha1 | Alpha release, not stable |

## Already Current

commons-lang3 (3.20.0), tika (3.2.3), rome (2.1.0), jaxb-runtime (4.0.6),
jacoco (0.8.14), commons-collections4 (4.5.0), selenide (7.12.1)

## Notes

- The 5 Dependabot PRs can be closed after this commit since the upgrades are included here.
- The commons-fileupload M5 upgrade would require updating `AttachmentServlet.java` to use the new API (likely `setFileSizeMax(long)` or a builder pattern). Worth doing as a separate task.
- The local Tomcat at `tomcat/tomcat-11` is a separate installation from the embedded Tomcat used in tests — it may need a manual update separately.

---

# Dependency Upgrade Sweep — 2026-08-16

Latest-stable sweep across the Maven reactor and the frontend. Verified with
`bin/run-tests.sh --parallel 4` (ALL PASSED, 6m11s), `npm test` (1551 tests,
162 files), and `npm run lint` (0 errors). `npm audit`: 0 vulnerabilities.

## Applied — Maven

| Property | From | To | Kind |
|---|---|---|---|
| `anthropic-java.version` | 2.52.0 | 2.54.0 | minor |
| `archunit.version` | 1.4.2 | 1.5.0 | minor |
| `commons-collections.version` | 4.5.0 | 4.6.0 | minor |
| `junit.version` | 6.1.2 | 6.1.3 | patch |
| `lucene.version` | 10.5.0 | 10.5.1 | patch |
| `nekohtml.version` | 3.0.3 | 3.0.4 | patch |
| `plugin.docker.version` | 0.48.1 | 0.49.0 | minor |
| `sec.jackson3.version` | 3.2.1 | 3.2.2 | patch |
| `selenium.version` | 4.46.0 | 4.47.0 | minor |

## Applied — frontend

| Package | From | To | Kind |
|---|---|---|---|
| `@testing-library/jest-dom` | 6.9.1 | 7.0.1 | **major** (devDep; only `src/setupTests.js`) |
| `@codemirror/lang-markdown` | 6.5.1 | 6.5.2 | patch |
| `@codemirror/view` | 6.43.7 | 6.43.8 | patch |
| `cytoscape` | 3.34.0 | 3.34.1 | patch |
| `eslint` | 10.8.0 | 10.8.1 | patch |
| `globals` | 17.9.0 | 17.11.0 | minor |
| `happy-dom` | 20.11.1 | 20.11.2 | patch |
| `vite` | 8.2.0 | 8.2.1 | patch |

## Held deliberately — do not "fix" these

- **katex 0.16.47 → 0.18.4 — BLOCKED by `rehype-katex`.** `rehype-katex@7.0.1`
  is the *latest* release and carries a hard dependency (not a peer) on
  `katex: ^0.16.0`. Bumping the app's katex to 0.18 would resolve **two** katex
  copies into the bundle and let the editor preview (rehype-katex → 0.16) and
  the reader (`src/utils/math.js` → 0.18) render math with different engines and
  CSS. Revisit only when rehype-katex widens its range.
- **junrar: on 7.6.1; the 8.x MAJOR stays held.** *(Updated later the same day:*
  *Dependabot replaced the 8.1.0 PR with a 7.6.**1** patch, which was taken —*
  *its changelog carries "prevent directory creation outside target directory",*
  *a path-traversal fix in a RAR parser reachable from `POST /api/ingest`.*
  *That is a patch on Tika's own 7.6 line, not the held major.)*
  The major remains refused: `sec.junrar.version` is a
  security pin of a Tika transitive (CVE-2026-41245, fixed in 7.6.0). Tika 3.3.2
  *itself* declares `junrar.version` = **7.6.0**, so the pin now matches upstream
  exactly. Forcing an untested major under Tika's RAR parser buys no security and
  risks the ingest path — same reasoning as the libthrift/Jena hold.

## Not taken — pre-release only

No stable release exists above the current pin for: log4j2 (3.0.0-beta2), tika
(4.0.0-beta-1), slf4j (2.1.0-alpha1), maven-{clean,compiler,install,jar,resources,source}
(4.0.0-beta-*), maven-site (4.0.0-M16), surefire (3.6.0-M1), and the jakarta
`*-M*` APIs. Scan with the stable-only rules file or these are recommended in error.

## Gotcha hit during this sweep

`mvn clean` **deletes `wikantik-frontend/node_modules`** (an explicit
`maven-clean-plugin` fileset in the root pom). Running `npm run lint`/`npm test`
concurrently with a Maven build therefore fails in a way that looks like a broken
upgrade — `npm run lint` silently falls back to whatever `eslint` is on `PATH`
(here a system-wide 6.4.0, which cannot read flat config). Run npm checks and
Maven builds sequentially, not in parallel.

---

# npm Supply-Chain Audit — 2026-08-16

Posture review of the frontend dependency tree, prompted by the ongoing wave of
npm registry compromises. Scope: `wikantik-frontend` (32 direct deps, **406**
lockfile entries).

## Findings — the tree itself is clean

| Check | Result |
|---|---|
| Known vulnerabilities (`npm audit`) | **0** |
| Registry signatures (`npm audit signatures`) | **382/382 verified**, 91 with provenance attestations |
| Lockfile integrity | 406/406 entries carry `integrity`; **all sha512**, none weaker |
| Package origin | 406/406 resolved from `registry.npmjs.org` — no git/http/tarball/file sources |
| Packages with install scripts | **1** — `fsevents` (dev-only, macOS-only, optional) |
| Deprecated packages | 0 |
| Typosquat scan (short/odd names) | `ajv`, `ms`, `uri-js`, `ws`, `zod` — all legitimate, high-reputation |

## Findings — the *build* was the weak point

1. **The shipped artifact was built with `npm install`, not `npm ci`.** The
   Dockerfile runs `mvn package -pl wikantik-war`, which invoked
   `npm install --no-audit --no-fund`. Every one of the 406 packages is on a
   caret range, so `npm install` re-resolves at build time: a malicious patch
   release published after the last lockfile update would be pulled silently
   into the production bundle. CI's `quality-gates.yml` already used `npm ci`,
   so the tree that was *tested* was not guaranteed to be the tree that
   *shipped*. **Fixed** — the WAR build now uses
   `npm ci --no-audit --no-fund --ignore-scripts`.
2. **`npx vite build`.** `npx` silently downloads a package from the registry
   when it cannot resolve one locally, turning a broken install into an unpinned
   fetch. **Fixed** — now `npm run build`, which only ever executes
   `node_modules/.bin`.
3. **No Dependabot coverage for npm at all.** `.github/dependabot.yml` declared
   only the `maven` ecosystem, so the 406-package tree received no automated
   vulnerability or update PRs — on the more actively attacked of the two
   registries. **Fixed** — npm ecosystem added, dev-toolchain updates grouped.
4. **No supply-chain gate on the path this repo actually uses.**
   `dependency-review.yml` is `on: [pull_request]`; it does fire (Dependabot
   opens PRs) but never sees a direct push to main, which is how this repo is
   developed. **Fixed** — `quality-gates.yml` (runs on every push to main) now
   runs `npm audit signatures`, `npm audit`, and asserts the lockfile was not
   rewritten by the install.
5. **No `.npmrc`.** Added, with `ignore-scripts=true` (blocks the install-time
   RCE vector — verified it does *not* block explicit `npm run`), an explicit
   `registry=` pin so a stray env var or user-level `~/.npmrc` cannot redirect
   resolution, and `audit-level=high`.

## Also found — the earlier Maven sweep was incomplete

The root-only (`-N`) property scan misses dependencies declared in submodules
with literal versions. A recursive scan found four: `crawler-commons` (1.4),
`jsoup` (1.23.1), and `h2` (2.4.240, in four modules). **Scan recursively, not
just `-N` at the root.**

- `crawler-commons` 1.4 → **1.6** applied (usage is limited to the robots.txt
  parser: `SimpleRobotRulesParser`, `BaseRobotRules`, `SimpleRobotRules`).

## Held — bouncycastle 1.85, do not bump the shared property

`versions-plugin` reports `bcprov-jdk18on` 1.85.2. That patch was released for
**bcprov only** — `bcpkix`, `bcjmail` and `bcutil` have no 1.85.2 (verified: 404
on Maven Central). One property, `sec.bouncycastle.version`, drives all four, so
bumping it fails the build outright (`bcjmail-jdk18on:jar:1.85.2 was not found`).
Splitting the property would mix Bouncy Castle artifact versions, which upstream
advises against. Revisit when the full 1.85.2 set ships.

## Flakes observed during verification (not regressions) — running tally

Six full `bin/run-tests.sh --parallel 4` runs were needed to land this work.
**Four passed, two failed — on two different tests, both timing/async, both
passing on immediate re-run.** Recorded here so the rate can be compared over
time rather than re-litigated each session.

| Run | Result | Failing test |
|---|---|---|
| 1 | PASS | — |
| 2 | FAIL | `KnowledgeTabIT.addEntitiesAndConformantRelation` (IT, parallel x4) |
| 3 | PASS | — (same module in isolation: 110 tests, 0 failures) |
| 4 | PASS | — |
| 5 | FAIL | `PluginCoverageTest$SearchPluginTests` (Phase 1 unit, `-T 1C`) |
| 6 | PASS | — |

**Flake 1 — `KnowledgeTabIT`:** `kg-add-entity-btn` still `disabled` at the 5s
Selenide timeout. The identical 4-wide gate passed both before and after, and the
module passes in isolation, so it is contention under parallel IT execution.
Same class as the known `EditIT` CodeMirror flake.

**Flake 2 — `PluginCoverageTest$SearchPluginTests`:** Lucene
`IndexNotFoundException: no segments* file found` while awaiting the search
index. This is the documented `wikantik-main` parallel-flakiness under `-T 1C`
(filesystem races, no code regression) — not related to the junrar bump it
appeared under, which is confined to Tika's RAR parser.

Neither was caused by this changeset. A ~2-in-6 intermittent failure rate on the
pre-commit gate is worth watching: if it persists, the two tests need explicit
waits rather than fixed timeouts, not a longer timeout.

Original note:

`KnowledgeTabIT.addEntitiesAndConformantRelation` failed once under
`--parallel 4` — `kg-add-entity-btn` still `disabled` at the 5s Selenide
timeout. Evidence it is an intermittent parallel-execution flake and not caused
by this changeset: the identical 4-wide gate passed both **before** the failure
(with selenium 4.47.0 already applied) and **after** it, and
`bin/run-tests.sh --module custom-jdbc` passes in isolation (110 tests, 0
failures — the same test count as the failing run, so the test did execute).
Same class as the known `EditIT` CodeMirror flake: a timing assertion that loses
its race when four IT modules contend for the box.
