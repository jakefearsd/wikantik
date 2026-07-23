# Code-Health Site Design

**Date:** 2026-07-23
**Status:** Approved (brainstorming) — ready for implementation plan
**Author:** Jake Fear (with Claude)

## Goal

Produce a robust, polished Maven-generated **site** that serves as a single
code-health report for the Wikantik reactor: aggregate test coverage, module
coupling, static-analysis findings, duplication, tech-debt markers, dependency
health, and browsable source/Javadoc. The site is published to
`https://wikantik.com/site` and regenerated manually.

Modeled visually and structurally on canonical Apache Maven project sites
(Apache Commons, Maven core): a landing "Code Health" dashboard, a **Project
Information** section, and a **Project Reports** section, with per-module
drill-down.

## Decisions (locked in brainstorming)

| Decision | Choice |
|---|---|
| Site shape | **Both** — root aggregate dashboard **and** per-module drill-down (`site:stage`) |
| Publishing | **wikantik.com/site**, via a rsync/privileged-copy script (NOT `site:deploy`) |
| Coverage depth | **Unit + IT aggregate** (the honest health number) |
| Cadence | **Manual command only** (no CI automation) |

## Current state (what already exists — do NOT rebuild)

The root `pom.xml` already has a mature `<reporting>` block (modern plugin
versions) and a `wikantik-coverage-report` aggregator module:

- `maven-project-info-reports-plugin` 3.9.0 — summary, dependencies, plugins, licenses, modules, team, etc.
- `maven-jxr-plugin` 3.6.0 — aggregate source cross-reference.
- `maven-javadoc-plugin` 3.12.0 — per-module + aggregate Javadoc, **UMLDoclet** (`nl.talsmasoftware:umldoclet`, pure-Java UML — no native graphviz).
- `maven-surefire-report-plugin` 3.5.6 — aggregate unit-test results.
- `jacoco-maven-plugin` 0.8.15 — per-module coverage report.
- `maven-pmd-plugin` 3.28.0 — aggregate PMD against `build-support/pmd-ruleset.xml`, `linkXRef=true`.
- `spotbugs-maven-plugin` 4.10.2.0 — effort Max / threshold Low, `includeTests=false`, `build-support/spotbugs-exclude.xml`, **find-sec-bugs** plugin.
- `maven-site-plugin` 3.22.0 — with `maven-fluido-skin`.
- `wikantik-coverage-report` module — `report-aggregate` over unit `jacoco.exec` **and** Cargo IT `jacoco-it.exec`, output `target/site/jacoco-aggregate/`.
- Per-module `src/site/site.xml` decoration files exist for most modules; root `src/site/site.xml` uses fluido and references `parent`/`modules`/`reports` menus.

Reports only run under `mvn site`; normal `mvn install` is unaffected.

### Known stale/incorrect bits to fix

- Root `src/site/site.xml` points the issue tracker at **Apache JIRA JSPWIKI**
  (`http://issues.apache.org/jira/browse/JSPWIKI`) — this is our fork; retarget
  to the GitHub issue tracker (`github.com/jakefearsd/wikantik/issues`).
- `<distributionManagement><site>` id `local-site`, url `https://wikantik.com/site`
  — kept for identity, but we do **not** use `mvn site:deploy` (no wagon).

## Gaps to fill

1. **Coupling** — nothing today. Add module dependency graph.
2. **Aggregate coverage not surfaced** — `wikantik-coverage-report` produces the
   number but it isn't a first-class site report linked from the dashboard.
3. **Duplication** — no CPD report.
4. **Tech debt** — no TODO/FIXME/`@deprecated` tracking.
5. **Dependency health** — no outdated-dependency report.
6. **Landing page** — generic default; needs a real "Code Health" dashboard.

## Report set (final)

Grouped as canonical Maven sites do — **Project Information** and **Project
Reports**.

**Project Information** (project-info-reports, already configured): summary,
dependencies, dependency-management, plugins, plugin-management, licenses,
modules, team, scm, ci-management, issue-management.

**Project Reports:**

| Report | Plugin | Scope | New? |
|---|---|---|---|
| Aggregate coverage (unit+IT) | jacoco `report-aggregate` | root/coverage module | wire-up |
| Per-module coverage | jacoco `report` | per module | have |
| Module coupling graph | `com.github.ferstl:depgraph-maven-plugin` | root (aggregate) | **new** |
| Duplication (CPD) | `maven-pmd-plugin:cpd` / `cpd-aggregate` | root (aggregate) | **new** |
| PMD static analysis | maven-pmd-plugin | root (aggregate) | have |
| SpotBugs + find-sec-bugs | spotbugs-maven-plugin | per module | have |
| Test results | maven-surefire-report | root (aggregate) | have |
| Tech-debt markers | `maven-taglist-plugin` (TODO/FIXME/@deprecated) | per module + aggregate | **new** |
| Outdated dependencies | `versions-maven-plugin:dependency-updates-report` | root | **new** |
| Source cross-reference | maven-jxr | aggregate | have |
| Javadoc + UML | maven-javadoc (UMLDoclet) | per module + aggregate | have |

### Coupling — approach and rationale

Classic **JDepend** (afferent/efferent coupling, instability, abstractness) is
effectively dead on JDK 25: its analysis engine cannot parse post-Java-8 class
files and the Maven plugin is retired. We do **not** use it.

Instead:

- **`depgraph-maven-plugin`** renders the inter-**module** (Maven artifact)
  dependency graph. Module boundaries are this project's central architectural
  discipline (enforced by `DecompositionArchTest`), so module-level coupling is
  the meaningful, honest axis. Output as a self-contained image embedded in a
  site report page. Prefer a rendering that needs no native `dot` (PlantUML/SVG
  or the plugin's built-in renderer); if a `.dot`/`.puml` intermediate is
  produced, convert with a bundled/pure-Java renderer, never a system graphviz
  dependency.
- **PMD design/coupling rules** (already active via `build-support/pmd-ruleset.xml`
  and the complexity gate) cover class-level coupling (excessive imports,
  coupling-between-objects, law-of-Demeter where enabled).

We explicitly do **not** attempt package-level Ca/Ce/I/A metrics — no maintained
JDK-25-capable tool exists, and re-deriving them is out of scope.

## Site mechanics

- **New `site` Maven profile** carries the *added* reporting plugins (depgraph,
  taglist, cpd, versions) and any site-only config, so a bare `mvn install`
  stays fast and unaffected. The existing `<reporting>` plugins remain where
  they are.
- Coverage- and test-dependent reports (JaCoCo, Surefire) require exec data +
  surefire XML on disk, so the health site is a **two-phase** build:

  ```
  # Phase 1 — produce coverage + test data (unit + IT aggregate)
  mvn clean install -Pcoverage -T 1C -DskipITs      # unit exec + surefire XML
  bin/run-tests.sh --it   (under -Pcoverage)         # Cargo IT jacoco-it.exec
  mvn -Pcoverage -pl wikantik-coverage-report \
      org.jacoco:jacoco-maven-plugin:report-aggregate # aggregate coverage

  # Phase 2 — generate + assemble the site tree
  mvn site site:stage -Pcoverage -Psite              # -> target/staging/
  ```

- **`bin/site.sh`** wraps both phases into one command, run through
  `bin/agent-build.sh` (the site build is long: UMLDoclet × ~20 modules +
  SpotBugs Max + PMD/CPD aggregate). Flags:
  `--unit-only` (skip IT coverage), `--skip-build` (reuse existing exec data,
  regenerate site only), `--no-stage` (root site only).
- Aggregate coverage is surfaced as a first-class report: configure the
  `wikantik-coverage-report` module's `<reporting>` to emit `report-aggregate`
  so `site:stage` includes it, and link it prominently from the landing page.
- `site:stage` assembles `target/staging/` = root dashboard (aggregate reports)
  with every module's own site linked under the **Modules** menu → satisfies
  "both aggregate + drill-down".

## Landing page

Replace the default generic project page with a hand-written
`src/site/markdown/index.md` "Code Health" dashboard:

- Headline aggregate coverage %, module count, test count.
- A "Reports" table linking each report (coverage, coupling, PMD, SpotBugs,
  CPD, tests, taglist, dependency-updates, javadoc, xref).
- A short "how this is generated" note (points to `bin/site.sh`).
- Uses the fluido skin already configured; refine skin options (nav, ToC,
  source-highlighting) during the iteration loop.

Root `src/site/site.xml` decoration: fix the issue-tracker link, ensure the
`reports` and `modules` menus render, and give the site a clear title/nav.

## Publishing

**`bin/deploy-site.sh`** — structurally a clone of `bin/deploy-marketing.sh`:

1. rsync `target/staging/` → `cloudflare:~/wikantik-deploy/code-health-site/`
   (unprivileged staging).
2. `ssh -t cloudflare` privileged copy into `/var/www/www.wikantik.com/site/`,
   chown back to `www-data:www-data` (docroot is www-data-owned, sudo not
   passwordless — interactive prompt, so run via `! bin/deploy-site.sh`).
3. On-origin verify (`Host: www.wikantik.com`, port 8000) that
   `/site/index.html` and a couple of key report pages return 200.

Config via env overrides matching `deploy-marketing.sh` conventions
(`MKT_HOST`, docroot, staging, owner, origin port).

**SEO hygiene:** the marketing domain has an active robots.txt/sitemap and heavy
SEO focus. Prevent 20 modules of Javadoc from being crawled into that surface:
add `Disallow: /site/` to the marketing `robots.txt` (and/or ship a
`target/staging/robots.txt` with `X-Robots-Tag: noindex`). The site is public
(convenient to open) but not indexed.

## Iteration loop (the "get it right" part)

After the first successful generation:

1. Open `target/staging/index.html` locally; inspect the landing page and each
   report renders and links resolve.
2. Compare against canonical Maven sites (Apache Commons / Maven core) for
   structure, nav, and polish; adjust skin config, landing content, and report
   selection.
3. Confirm the coupling graph is legible at 23 modules (group/collapse if
   noisy), coverage aggregate shows the real unit+IT number, CPD/taglist/
   dependency-updates render.
4. Only then deploy via `bin/deploy-site.sh` and verify on-origin.

## Out of scope

- CI/release-time automation (explicitly deferred).
- `mvn site:deploy` wagon setup (superseded by the rsync script).
- Package-level JDepend-style coupling metrics (no JDK-25-capable tool).
- Any change to the existing `coverage`/`complexity-gate` profiles' gating
  behavior — this work only *reports*, it does not add build-failing gates.

## Risks / notes

- **Build time:** full site is long; always via `bin/agent-build.sh`, never a
  bare foreground `mvn site`.
- **New-module maintenance:** `wikantik-coverage-report` lists production
  modules explicitly; any new module must be added there (existing constraint,
  unchanged).
- **depgraph output portability:** verify the chosen renderer needs no system
  graphviz so the site builds on any dev box and in a headless deploy.
- **mockito-core rule:** no new Maven modules are introduced, so the
  "every new module needs mockito-core" gotcha does not apply.
