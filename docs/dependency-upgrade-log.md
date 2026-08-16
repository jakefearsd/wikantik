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
- **junrar 7.6.0 → 8.1.0 — no gain, real risk.** `sec.junrar.version` is a
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
