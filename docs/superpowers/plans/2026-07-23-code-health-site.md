# Code-Health Site Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a robust, published Maven site that serves as a single code-health report for the Wikantik reactor — aggregate unit+IT coverage, module coupling, static analysis, duplication, tech-debt, dependency health, and browsable source/Javadoc — with a polished "Code Health" landing dashboard and per-module drill-down, deployed to `https://wikantik.com/site`.

**Architecture:** Extend the existing root `<reporting>` block with the missing reports (taglist, dependency-updates, CPD, aggregate coverage). Add a **module coupling** page from `depgraph-maven-plugin` (bound to the `pre-site` phase, SVG via graphviz, `.dot` fallback). Replace the generic landing page with a hand-written dashboard. Wrap the two-phase build (coverage build → `mvn site site:stage`) in `bin/site.sh`, and publish `target/staging/` with `bin/deploy-site.sh` (a clone of `bin/deploy-marketing.sh` targeting `/var/www/www.wikantik.com/site/`).

**Tech Stack:** Maven site lifecycle, maven-site-plugin 3.22.0 + fluido skin, JaCoCo 0.8.15, maven-pmd-plugin 3.28.0 (PMD+CPD), spotbugs 4.10.2.0 + find-sec-bugs, maven-surefire-report 3.5.6, maven-javadoc 3.12.0 (UMLDoclet), maven-jxr 3.6.0, `org.codehaus.mojo:taglist-maven-plugin`, `org.codehaus.mojo:versions-maven-plugin`, `com.github.ferstl:depgraph-maven-plugin`, graphviz (site-build prerequisite).

## Global Constraints

- Build JDK: **25** (`mvn -version` must show JDK 25). Maven **3.9+**. Node **20.19+** (only for the WAR build, not the site).
- **Never run a full `mvn site` as a bare foreground call.** Any Maven invocation that can exceed ~5 min MUST go through `bin/agent-build.sh` (detached, `EXIT=<code>` sentinel). Poll `status`/`wait`; never end the turn "to wait for a notification."
- This work **only reports** — it MUST NOT add any build-failing gate or change `coverage`/`complexity-gate` profile gating.
- **No new Maven modules** are introduced (so the "every new module needs mockito-core" rule does not apply).
- Stage files by exact name — **never `git add -A`**.
- Commit messages 1–3 lines, ending with:
  ```
  Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01RmDRawNAWsgDg1wux3unHc
  ```
- Deploy host is ssh alias `cloudflare`; docroot `/var/www/www.wikantik.com` (owned by `www-data`); sudo there is **not** passwordless — the privileged copy runs over `ssh -t` and must be invoked interactively (via `! bin/deploy-site.sh` inside a session).
- Reporting/coupling plugin versions below are pinned to a known-good value; if one fails to resolve, run `mvn versions:display-plugin-updates` and bump to the current release (a bounded fix, not a placeholder).

**"Test" model for this plan:** these are build-config tasks, so each task's "failing test" is a **verification command** whose output shows the report/artifact is *absent*, and the "passing test" is the same (or a scoped) command showing it now *renders*. Per-module reports are verified with a fast single-module `mvn -pl <module> site`; aggregate reports (root-only, need the full reactor) are verified in Task 8's full generation.

---

### Task 1: Prerequisite + coupling-tool spike (graphviz + depgraph)

Verify the coupling toolchain works on this box before wiring it into the pom, and record graphviz as a site-build prerequisite.

**Files:**
- Modify: `CLAUDE.md` (Prerequisites table — add graphviz, site-build only)

**Interfaces:**
- Produces: confidence that `depgraph:aggregate -DgraphFormat=svg` yields `target/dependency-graph.svg`; the exact depgraph version that resolves; whether graphviz `dot` is present.

- [ ] **Step 1: Baseline — confirm depgraph is not yet usable and check graphviz**

Run:
```bash
which dot || echo "NO GRAPHVIZ"
mvn -q com.github.ferstl:depgraph-maven-plugin:4.0.3:aggregate \
    -DgraphFormat=svg -Dincludes='com.wikantik*:*' -N 2>&1 | tail -20
```
Expected: either "NO GRAPHVIZ", and/or the plugin runs but (without graphviz) emits a `.dot` rather than a rendered `.svg`. Note which.

- [ ] **Step 2: Ensure graphviz is available for rendering**

If `dot` is missing, install it (Debian/Ubuntu dev box):
```bash
sudo apt-get update && sudo apt-get install -y graphviz
dot -V
```
Expected: `dot - graphviz version ...`. (If the box genuinely cannot have graphviz, the `.dot` fallback in Task 5 still yields a linked artifact — proceed either way.)

- [ ] **Step 3: Verify depgraph renders a module SVG**

Run at the reactor root (needs the reactor to be installed; if artifacts are stale run `mvn -q -DskipTests -T 1C install` first via `bin/agent-build.sh`):
```bash
mvn -q com.github.ferstl:depgraph-maven-plugin:4.0.3:aggregate \
    -DgraphFormat=svg -Dincludes='com.wikantik*:*' -DshowGroupIds=false
ls -la target/dependency-graph.svg
```
Expected: `target/dependency-graph.svg` exists and, opened, shows the ~20 `wikantik-*` modules with dependency arrows between them.

- [ ] **Step 4: Record the prerequisite**

Add a row to the Prerequisites table in `CLAUDE.md`:
```markdown
| Graphviz (`dot`) | any recent | Only for the code-health **site** build (module coupling graph). `apt-get install graphviz`. Optional — without it depgraph emits a linked `.dot` instead of an SVG. |
```

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md
git commit -m "build(site): record graphviz as optional site-build prerequisite for module coupling graph

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RmDRawNAWsgDg1wux3unHc"
```

---

### Task 2: Tech-debt (taglist) + dependency-updates reports

Add TODO/FIXME/`@deprecated` tracking and an outdated-dependency report to the root `<reporting>` block.

**Files:**
- Modify: `pom.xml` (inside `<reporting><plugins>`, after the spotbugs entry, ~line 1476)

**Interfaces:**
- Produces (site pages the landing page will link): `taglist.html` (aggregate tech-debt), `dependency-updates-report.html` (outdated deps).

- [ ] **Step 1: Baseline — confirm the reports are absent on a leaf module**

Run:
```bash
mvn -q -pl wikantik-util -DskipTests install
mvn -pl wikantik-util site 2>&1 | tail -5
ls wikantik-util/target/site/taglist.html 2>&1 || echo "NO TAGLIST (expected)"
```
Expected: `NO TAGLIST (expected)`.

- [ ] **Step 2: Add the plugins to `<reporting><plugins>`**

Insert before the closing `</plugins>` of the `<reporting>` block (after the spotbugs reporting plugin, ~line 1476):
```xml
      <!-- Tech-debt markers: TODO / FIXME / @deprecated -->
      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>taglist-maven-plugin</artifactId>
        <version>3.2.1</version>
        <configuration>
          <aggregate>true</aggregate>
          <tagListOptions>
            <tagClasses>
              <tagClass>
                <displayName>Action items</displayName>
                <tags>
                  <tag><matchString>TODO</matchString><matchType>ignoreCase</matchType></tag>
                  <tag><matchString>FIXME</matchString><matchType>ignoreCase</matchType></tag>
                  <tag><matchString>XXX</matchString><matchType>exact</matchType></tag>
                </tags>
              </tagClass>
              <tagClass>
                <displayName>Deprecations</displayName>
                <tags>
                  <tag><matchString>@deprecated</matchString><matchType>ignoreCase</matchType></tag>
                </tags>
              </tagClass>
            </tagClasses>
          </tagListOptions>
        </configuration>
      </plugin>

      <!-- Dependency health: which declared deps/plugins have newer releases -->
      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>versions-maven-plugin</artifactId>
        <version>2.18.0</version>
        <reportSets>
          <reportSet>
            <reports>
              <report>dependency-updates-report</report>
              <report>plugin-updates-report</report>
              <report>property-updates-report</report>
            </reports>
          </reportSet>
        </reportSets>
      </plugin>
```

- [ ] **Step 3: Verify taglist renders per-module**

Run:
```bash
mvn -pl wikantik-util site 2>&1 | tail -5
ls -la wikantik-util/target/site/taglist.html
```
Expected: `taglist.html` exists. (The `dependency-updates-report` and the taglist *aggregate* are root-only and reach out to Maven Central; they are verified in Task 8's full run.)

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "build(site): add taglist (TODO/FIXME/@deprecated) + versions dependency-updates reports

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RmDRawNAWsgDg1wux3unHc"
```

---

### Task 3: Duplication report (CPD)

Surface copy-paste duplication as an aggregate site report, alongside the existing PMD aggregate.

**Files:**
- Modify: `pom.xml` (the existing `maven-pmd-plugin` entry in `<reporting>`, ~lines 1443-1456)

**Interfaces:**
- Produces: `cpd.html` (aggregate duplication) at the root site — landing page links it.

- [ ] **Step 1: Baseline — confirm CPD is not emitted**

The PMD reporting plugin currently declares no explicit `<reportSets>`, so it runs its default reports (which include `cpd` per-module but NOT the aggregate). Confirm the aggregate CPD page is absent:
```bash
ls target/site/cpd.html 2>&1 || echo "NO AGGREGATE CPD (expected)"
```
Expected: `NO AGGREGATE CPD (expected)`.

- [ ] **Step 2: Add explicit reportSets to the PMD reporting plugin**

Replace the existing `<reporting>` PMD plugin block (the one at ~line 1443 with `<configuration><linkXRef>true</linkXRef>...`) so it keeps its config and adds aggregate PMD + aggregate CPD:
```xml
      <!-- Static analysis — PMD + copy/paste duplication (CPD), aggregated -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-pmd-plugin</artifactId>
        <version>${plugin.pmd.version}</version>
        <configuration>
          <linkXRef>true</linkXRef>
          <targetJdk>${jdk.version}</targetJdk>
          <aggregate>true</aggregate>
          <rulesets>
            <ruleset>${maven.multiModuleProjectDirectory}/build-support/pmd-ruleset.xml</ruleset>
          </rulesets>
        </configuration>
        <reportSets>
          <reportSet>
            <id>aggregate</id>
            <inherited>false</inherited>
            <reports>
              <report>pmd</report>
              <report>cpd</report>
            </reports>
          </reportSet>
        </reportSets>
      </plugin>
```
Note: with `<aggregate>true</aggregate>` + `<inherited>false</inherited>`, the `pmd`/`cpd` reports run once at the root over all modules, producing `pmd.html` and `cpd.html`.

- [ ] **Step 3: Verify (root aggregate — Task 8 does the full check)**

This is an aggregate report requiring the full reactor; a scoped single-module check is not representative. Confirm the pom parses and the plugin config is accepted:
```bash
mvn -q -N help:effective-pom 2>&1 | grep -A2 'cpd' | head
```
Expected: no XML/model errors; `cpd` appears in the effective reporting config.

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "build(site): aggregate PMD + CPD duplication report via explicit reportSet

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RmDRawNAWsgDg1wux3unHc"
```

---

### Task 4: Aggregate coverage as a first-class site report

Make the existing `wikantik-coverage-report` module emit its unit+IT `report-aggregate` as a **site report** so `site:stage` includes it and the landing page can link it.

**Files:**
- Modify: `wikantik-coverage-report/pom.xml` (add a `<reporting>` section)

**Interfaces:**
- Consumes: unit `jacoco.exec` + Cargo IT `jacoco-it.exec` on disk (produced by a `-Pcoverage` build + IT run).
- Produces: `wikantik-coverage-report/jacoco-aggregate/index.html` in the staged site — the landing page's headline coverage link.

- [ ] **Step 1: Baseline — the aggregate is a build report, not a site report**

Confirm the module currently produces the aggregate only under the `verify` build goal, not `site`:
```bash
grep -c '<reporting>' wikantik-coverage-report/pom.xml || echo "NO REPORTING SECTION (expected)"
```
Expected: `NO REPORTING SECTION (expected)` (grep prints `0`, or the echo fires).

- [ ] **Step 2: Add a `<reporting>` section to the coverage module**

Insert a `<reporting>` block just before the closing `</project>` in `wikantik-coverage-report/pom.xml` (after `</build>`):
```xml
  <reporting>
    <plugins>
      <plugin>
        <groupId>org.jacoco</groupId>
        <artifactId>jacoco-maven-plugin</artifactId>
        <version>${jacoco-version}</version>
        <reportSets>
          <reportSet>
            <reports>
              <report>report-aggregate</report>
            </reports>
            <configuration>
              <!-- Same data sources as the build-phase aggregate: unit + Cargo IT exec. -->
              <dataFileIncludes>
                <dataFileInclude>**/jacoco.exec</dataFileInclude>
                <dataFileInclude>**/jacoco-it.exec</dataFileInclude>
              </dataFileIncludes>
            </configuration>
          </reportSet>
        </reportSets>
      </plugin>
    </plugins>
  </reporting>
```

- [ ] **Step 3: Verify the report renders from existing exec data**

Requires a prior `-Pcoverage` build so exec files exist. If none, run one first (via `bin/agent-build.sh`, see Task 7). Then:
```bash
mvn -pl wikantik-coverage-report -Pcoverage site 2>&1 | tail -5
ls -la wikantik-coverage-report/target/site/jacoco-aggregate/index.html
```
Expected: `jacoco-aggregate/index.html` exists and shows a combined coverage percentage across production modules.

- [ ] **Step 4: Commit**

```bash
git add wikantik-coverage-report/pom.xml
git commit -m "build(site): expose unit+IT aggregate coverage as a site report (report-aggregate)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RmDRawNAWsgDg1wux3unHc"
```

---

### Task 5: Module coupling — depgraph generation bound to `pre-site`

Generate the inter-module dependency graph as an SVG (with a `.dot` fallback) into the site's generated-resources dir so a coupling page can embed it — without slowing down `mvn install`.

**Files:**
- Modify: `pom.xml` (add a `depgraph-maven-plugin` execution to `<build><plugins>` — NOT pluginManagement — bound to `pre-site`)

**Interfaces:**
- Produces: `target/generated-site/resources/images/module-coupling.svg` (and `.dot`) at the reactor root. The site plugin auto-merges `target/generated-site/resources/` into the site output, so the file is reachable at `images/module-coupling.svg` from the root site. Task 6's `coupling.md` embeds it.

- [ ] **Step 1: Baseline — no coupling image is generated by the site lifecycle**

Run:
```bash
rm -rf target/generated-site
mvn -N pre-site 2>&1 | tail -3
ls target/generated-site/resources/images/module-coupling.svg 2>&1 || echo "NO COUPLING IMAGE (expected)"
```
Expected: `NO COUPLING IMAGE (expected)`.

- [ ] **Step 2: Add the depgraph build-plugin execution bound to `pre-site`**

Add to the root `pom.xml` `<build><plugins>` (a real plugin execution, so it runs in the reactor's `pre-site` phase — which `mvn site` triggers but `mvn install` does not):
```xml
      <!-- Module coupling graph for the code-health site. Bound to pre-site so it
           only runs as part of `mvn site`, never during a normal install/build.
           Renders SVG when graphviz `dot` is on PATH; otherwise leaves the .dot
           source, which the coupling page links as a fallback. -->
      <plugin>
        <groupId>com.github.ferstl</groupId>
        <artifactId>depgraph-maven-plugin</artifactId>
        <version>4.0.3</version>
        <inherited>false</inherited>
        <executions>
          <execution>
            <id>module-coupling</id>
            <phase>pre-site</phase>
            <goals><goal>aggregate</goal></goals>
            <configuration>
              <graphFormat>svg</graphFormat>
              <includes><include>com.wikantik*:*</include></includes>
              <showGroupIds>false</showGroupIds>
              <showVersions>false</showVersions>
              <mergeScopes>true</mergeScopes>
              <outputDirectory>${project.build.directory}/generated-site/resources/images</outputDirectory>
              <outputFileName>module-coupling</outputFileName>
            </configuration>
          </execution>
        </executions>
      </plugin>
```

- [ ] **Step 3: Verify the image is generated into the site resources dir**

Run:
```bash
rm -rf target/generated-site
mvn pre-site 2>&1 | tail -5
ls -la target/generated-site/resources/images/module-coupling.*
```
Expected: `module-coupling.svg` (graphviz present) or `module-coupling.dot` (fallback) exists.

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "build(site): generate module coupling graph (depgraph) into site resources at pre-site

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RmDRawNAWsgDg1wux3unHc"
```

---

### Task 6: Landing dashboard + coupling page + decoration fixes

Replace the generic landing page with a "Code Health" dashboard, add the coupling page that embeds the graph, and fix the stale issue-tracker link.

**Files:**
- Create: `src/site/markdown/index.md` (Code Health dashboard)
- Create: `src/site/markdown/coupling.md` (embeds the coupling graph + explains PMD design rules)
- Modify: `src/site/site.xml` (fix issue tracker link; add Reports/Code-Health menu entries)

**Interfaces:**
- Consumes: report paths produced by Tasks 2–5 and the existing `<reporting>` plugins.
- Produces: `index.html`, `coupling.html` at the root site.

- [ ] **Step 1: Baseline — the current landing page is the generic default**

Run:
```bash
ls src/site/markdown/index.md 2>&1 || echo "NO LANDING MARKDOWN (expected)"
grep -n 'JSPWIKI' src/site/site.xml
```
Expected: `NO LANDING MARKDOWN (expected)`; the grep shows the stale Apache JIRA link on the issue-tracker item.

- [ ] **Step 2: Write the Code Health landing page**

Create `src/site/markdown/index.md`:
```markdown
# Wikantik Code Health

Generated code-health dashboard for the Wikantik reactor. Regenerate with
`bin/site.sh`; publish with `bin/deploy-site.sh`.

## Reports

| Area | Report |
|------|--------|
| **Coverage (unit + IT, aggregate)** | [JaCoCo aggregate](wikantik-coverage-report/jacoco-aggregate/index.html) |
| **Module coupling** | [Coupling graph](coupling.html) |
| **Static analysis (PMD)** | [PMD aggregate](pmd.html) |
| **Duplication (CPD)** | [Copy/paste report](cpd.html) |
| **Bugs / security (SpotBugs + find-sec-bugs)** | per module — see each module's *SpotBugs* report |
| **Tests (aggregate)** | [Surefire results](surefire-report.html) |
| **Tech debt (TODO/FIXME/@deprecated)** | [Tag list](taglist.html) |
| **Dependency health** | [Dependency updates](dependency-updates-report.html) |
| **Source cross-reference** | [JXR](xref/index.html) |
| **API docs (with UML)** | [Javadoc](apidocs/index.html) |

## How this is generated

Two-phase build: a `-Pcoverage` install produces coverage/test data, then
`mvn site site:stage` assembles the aggregate dashboard plus every module's
own site (linked under **Modules**). See `docs/superpowers/specs/2026-07-23-code-health-site-design.md`.
```

- [ ] **Step 3: Write the coupling page**

Create `src/site/markdown/coupling.md`:
```markdown
# Module Coupling

Inter-module dependency graph for the Wikantik reactor (Maven artifact edges,
`com.wikantik*` only). Module boundaries are enforced in code by
`DecompositionArchTest`; this graph is the visual companion.

![Module coupling graph](images/module-coupling.svg)

If the graph above is missing, the site was generated without graphviz — see the
raw [Graphviz source](images/module-coupling.dot).

## Class-level coupling

Class-level coupling (excessive imports, coupling-between-objects) is reported by
the PMD design rules in the [PMD aggregate report](pmd.html). Package-level
afferent/efferent metrics (JDepend) are intentionally not produced — no
JDK-25-capable tool exists.
```

- [ ] **Step 4: Fix the decoration (issue tracker + menu)**

In `src/site/site.xml`, replace the stale issue-tracker `<item>` and ensure a Code Health menu. Change:
```xml
            <item name="Issue Tracker" href="http://issues.apache.org/jira/browse/JSPWIKI"/>
```
to:
```xml
            <item name="Issue Tracker" href="https://github.com/jakefearsd/wikantik/issues"/>
```
And replace the `<menu name="Wikantik">` block with:
```xml
        <menu name="Code Health">
            <item name="Overview" href="index.html"/>
            <item name="Coverage" href="wikantik-coverage-report/jacoco-aggregate/index.html"/>
            <item name="Module Coupling" href="coupling.html"/>
            <item name="PMD" href="pmd.html"/>
            <item name="Duplication (CPD)" href="cpd.html"/>
            <item name="Tests" href="surefire-report.html"/>
            <item name="Tech Debt" href="taglist.html"/>
            <item name="Dependency Updates" href="dependency-updates-report.html"/>
        </menu>
```

- [ ] **Step 5: Verify the landing + coupling pages render**

Run (root-only site render is enough to confirm the markdown compiles and links resolve locally):
```bash
mvn -N site 2>&1 | tail -5
ls -la target/site/index.html target/site/coupling.html
grep -o 'jacoco-aggregate/index.html' target/site/index.html | head -1
```
Expected: both HTML files exist; the coverage link string is present. (Cross-module link targets resolve only in the staged tree — verified in Task 8.)

- [ ] **Step 6: Commit**

```bash
git add src/site/markdown/index.md src/site/markdown/coupling.md src/site/site.xml
git commit -m "build(site): Code Health landing dashboard + coupling page + fix issue-tracker link

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RmDRawNAWsgDg1wux3unHc"
```

---

### Task 7: `bin/site.sh` — two-phase generation wrapper

One command that runs the coverage build then generates + stages the site, through `bin/agent-build.sh`.

**Files:**
- Create: `bin/site.sh` (executable)

**Interfaces:**
- Consumes: `bin/agent-build.sh` (start/status/wait), `bin/run-tests.sh` (for IT coverage), the `coverage` profile.
- Produces: `target/staging/` (the assembled site tree) for Task 8 / `bin/deploy-site.sh`.

- [ ] **Step 1: Baseline — no site wrapper exists**

Run:
```bash
ls bin/site.sh 2>&1 || echo "NO SITE SCRIPT (expected)"
```
Expected: `NO SITE SCRIPT (expected)`.

- [ ] **Step 2: Write `bin/site.sh`**

Create `bin/site.sh`:
```bash
#!/usr/bin/env bash
#
# bin/site.sh — generate the Wikantik code-health site (target/staging/).
#
# Two phases:
#   1. Coverage build: produce jacoco.exec + surefire XML (unit; + Cargo IT
#      under -Pcoverage for the aggregate unit+IT coverage number).
#   2. Site: `mvn site site:stage -Pcoverage` — aggregate dashboard + per-module
#      drill-down assembled into target/staging/.
#
# Long build → always via bin/agent-build.sh (detached, survives harness kills).
#
# Usage:
#   bin/site.sh                 # full: unit+IT coverage, then site
#   bin/site.sh --unit-only     # skip the IT reactor (unit coverage only)
#   bin/site.sh --skip-build    # reuse existing exec/test data, regenerate site only
#   bin/site.sh -h|--help
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

UNIT_ONLY=0; SKIP_BUILD=0
for arg in "$@"; do
  case "${arg}" in
    --unit-only) UNIT_ONLY=1 ;;
    --skip-build) SKIP_BUILD=1 ;;
    -h|--help) awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"; exit 0 ;;
    *) echo "site: unknown argument: ${arg}" >&2; exit 2 ;;
  esac
done

AB="bin/agent-build.sh"

if [[ "${SKIP_BUILD}" -eq 0 ]]; then
  echo "==> Phase 1: coverage build (unit)"
  "${AB}" start sitecov -- mvn clean install -Pcoverage -T 1C -DskipITs
  "${AB}" wait sitecov 1200 || { "${AB}" status sitecov; echo "site: coverage build failed" >&2; exit 1; }

  if [[ "${UNIT_ONLY}" -eq 0 ]]; then
    echo "==> Phase 1b: IT reactor under coverage (aggregate unit+IT)"
    "${AB}" start siteit -- bin/run-tests.sh --parallel 4 -Pcoverage
    "${AB}" wait siteit 1800 || { "${AB}" status siteit; echo "site: IT coverage run failed" >&2; exit 1; }
    echo "==> Aggregate coverage exec"
    mvn -q -Pcoverage -pl wikantik-coverage-report \
        org.jacoco:jacoco-maven-plugin:report-aggregate
  fi
fi

echo "==> Phase 2: site + stage"
"${AB}" start sitegen -- mvn site site:stage -Pcoverage
"${AB}" wait sitegen 1800 || { "${AB}" status sitegen; echo "site: site generation failed" >&2; exit 1; }

echo "==> Site staged at: ${REPO_ROOT}/target/staging/index.html"
```
Note: confirm `bin/run-tests.sh --parallel 4` forwards extra args like `-Pcoverage`; if it does not accept a trailing profile arg, replace that line with the module-by-module IT-coverage invocation the `wikantik-coverage-report` module documents.

- [ ] **Step 3: Make it executable and lint**

```bash
chmod +x bin/site.sh
bash -n bin/site.sh && echo "SYNTAX OK"
bin/site.sh --help | head -5
```
Expected: `SYNTAX OK`; the help text prints.

- [ ] **Step 4: Commit**

```bash
git add bin/site.sh
git commit -m "build(site): bin/site.sh two-phase code-health site generator (coverage -> site:stage)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RmDRawNAWsgDg1wux3unHc"
```

---

### Task 8: First full generation + iteration to polish

Generate the real site, inspect it against canonical Maven sites, and refine until it reads as a genuine health dashboard.

**Files:**
- Modify (as needed during iteration): `src/site/site.xml`, `src/site/markdown/*.md`, `pom.xml` report config.

**Interfaces:**
- Consumes: Tasks 1–7. Produces: a verified `target/staging/` ready to deploy.

- [ ] **Step 1: Generate the full site**

```bash
bin/site.sh --unit-only    # first pass: faster (skip IT reactor); switch to full once shape is right
```
Poll to completion. Expected: `Site staged at: .../target/staging/index.html`.

- [ ] **Step 2: Verify every linked report exists in the staged tree**

```bash
cd target/staging
for f in index.html coupling.html pmd.html cpd.html surefire-report.html \
         taglist.html dependency-updates-report.html \
         wikantik-coverage-report/jacoco-aggregate/index.html \
         apidocs/index.html xref/index.html images/module-coupling.svg; do
  [[ -e "$f" ]] && echo "OK  $f" || echo "MISSING  $f"
done
cd "${OLDPWD}"
```
Expected: all `OK`. For any `MISSING`, fix the responsible task's config (e.g. a report needing full-reactor data, or a wrong link path) and regenerate.

- [ ] **Step 3: Visual inspection**

Open `target/staging/index.html` in a browser (or use the Chrome MCP tools to screenshot the landing page, the coupling page, and the coverage aggregate). Compare against a canonical Maven site (e.g. `https://commons.apache.org/proper/commons-lang/` → Project Reports) for structure/nav/polish. Confirm:
- the coupling SVG is legible at ~20 modules (if cluttered, add `<mergeTypes>true</mergeTypes>` / prune to compile+runtime scope in Task 5's depgraph config),
- the landing dashboard's headline links resolve,
- the fluido skin nav shows the Code Health menu.

Refine `site.xml` / markdown / depgraph config as needed and regenerate (`bin/site.sh --skip-build` for fast site-only iterations).

- [ ] **Step 4: Full (unit+IT) generation once the shape is right**

```bash
bin/site.sh            # full run incl. IT coverage for the true aggregate number
```
Expected: the coverage aggregate now reflects unit **and** IT execution.

- [ ] **Step 5: Commit any iteration fixes**

```bash
git add -- src/site/site.xml src/site/markdown/index.md src/site/markdown/coupling.md pom.xml
git commit -m "build(site): polish code-health dashboard after first full generation

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RmDRawNAWsgDg1wux3unHc"
```

---

### Task 9: `bin/deploy-site.sh` — publish to wikantik.com/site

Publish `target/staging/` to `/var/www/www.wikantik.com/site/`, mirroring `bin/deploy-marketing.sh`, and keep it out of the SEO crawl surface.

**Files:**
- Create: `bin/deploy-site.sh` (executable)
- Modify: `marketing/robots.txt` (add `Disallow: /site/`)

**Interfaces:**
- Consumes: `target/staging/` from Task 8; ssh alias `cloudflare`; the marketing docroot.
- Produces: the live site at `https://wikantik.com/site/`.

- [ ] **Step 1: Baseline — no deploy script; robots does not exclude /site/**

```bash
ls bin/deploy-site.sh 2>&1 || echo "NO DEPLOY SCRIPT (expected)"
grep -n '/site/' marketing/robots.txt || echo "SITE NOT EXCLUDED (expected)"
```
Expected: both "expected" messages.

- [ ] **Step 2: Write `bin/deploy-site.sh`**

Create `bin/deploy-site.sh` (structure copied from `bin/deploy-marketing.sh`; publishes the staged site into a `/site` subdir of the marketing docroot):
```bash
#!/usr/bin/env bash
#
# bin/deploy-site.sh — publish the generated code-health site to
# https://wikantik.com/site (served from the marketing nginx docroot).
#
# Source is target/staging/ (produced by bin/site.sh). This rsyncs the staged
# tree to an unprivileged staging dir on the marketing host, then copies it into
# the nginx docroot's /site subdir under sudo (docroot is www-data-owned) and
# chowns it back.
#
# The marketing host's sudo is NOT passwordless — the privileged copy runs over
# an interactive `ssh -t` session (you will be prompted). Run via
# `! bin/deploy-site.sh` inside a Claude session so the prompt is answerable.
#
# Usage: bin/deploy-site.sh [--dry-run] [-h|--help]
#
# Env overrides (defaults match the live setup):
#   MKT_HOST         ssh alias for the marketing box   (default: cloudflare)
#   MKT_DOCROOT      nginx docroot for the vhost        (default: /var/www/www.wikantik.com)
#   MKT_STAGING      unprivileged staging dir on host   (default: ~/wikantik-deploy/code-health-site)
#   MKT_OWNER        docroot owner:group                (default: www-data:www-data)
#   MKT_ORIGIN_PORT  nginx listen port for verify       (default: 8000)
#   SITE_SRC         local staged site dir              (default: target/staging)
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

MKT_HOST="${MKT_HOST:-cloudflare}"
MKT_DOCROOT="${MKT_DOCROOT:-/var/www/www.wikantik.com}"
MKT_STAGING="${MKT_STAGING:-~/wikantik-deploy/code-health-site}"
MKT_OWNER="${MKT_OWNER:-www-data:www-data}"
MKT_ORIGIN_PORT="${MKT_ORIGIN_PORT:-8000}"
SITE_SRC="${SITE_SRC:-target/staging}"
DRY_RUN=0

print_help() { awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"; }
for arg in "$@"; do
  case "${arg}" in
    --dry-run) DRY_RUN=1 ;;
    -h|--help) print_help; exit 0 ;;
    *) echo "deploy-site: unknown argument: ${arg}" >&2; exit 2 ;;
  esac
done
run() { if [[ "${DRY_RUN}" -eq 1 ]]; then echo "[dry-run] $*"; else "$@"; fi; }

if [[ ! -f "${SITE_SRC}/index.html" ]]; then
  echo "deploy-site: ${SITE_SRC}/index.html not found — run bin/site.sh first" >&2
  exit 1
fi

echo "==> Publishing code-health site to ${MKT_HOST}:${MKT_DOCROOT}/site"

# 1. rsync staged site -> unprivileged staging dir (trailing slash: contents).
run rsync -avz --delete-after "${SITE_SRC}/" "${MKT_HOST}:${MKT_STAGING}/"

# 2. Privileged copy staging -> docroot/site, restore www-data ownership.
REMOTE_PUBLISH="set -e
sudo mkdir -p '${MKT_DOCROOT}/site'
sudo rsync -a --delete ${MKT_STAGING}/ '${MKT_DOCROOT}/site/'
sudo chown -R ${MKT_OWNER} '${MKT_DOCROOT}/site'"
if [[ "${DRY_RUN}" -eq 1 ]]; then
  echo "[dry-run] ssh -t ${MKT_HOST} <<publish>>"
else
  ssh -t "${MKT_HOST}" "${REMOTE_PUBLISH}"
fi

# 3. On-origin verify (bypasses Cloudflare) that key pages return 200.
echo "==> Verifying on-origin (http://localhost:${MKT_ORIGIN_PORT}, Host: www.wikantik.com)"
if [[ "${DRY_RUN}" -eq 1 ]]; then echo "[dry-run] curl /site/index.html + /site/coupling.html"; exit 0; fi
for path in /site/index.html /site/coupling.html /site/pmd.html; do
  line="$(ssh "${MKT_HOST}" "curl -s -o /dev/null -w '%{http_code} %{content_type}' \
      -H 'Host: www.wikantik.com' http://localhost:${MKT_ORIGIN_PORT}${path}")"
  echo "    ${path} -> ${line}"
  case "${line}" in 200\ *) ;; *) echo "deploy-site: ${path} != 200 (${line})" >&2; exit 1 ;; esac
done
echo "==> Code-health site published to https://wikantik.com/site/"
```

- [ ] **Step 3: Exclude /site/ from the SEO crawl surface**

Append to `marketing/robots.txt`:
```
# Generated code-health site (Javadoc/reports) — not for indexing
Disallow: /site/
```
Then re-verify the marketing robots is still valid and redeploy it later via `bin/deploy-marketing.sh` (or note it for the next marketing deploy).

- [ ] **Step 4: Lint the deploy script + dry-run**

```bash
chmod +x bin/deploy-site.sh
bash -n bin/deploy-site.sh && echo "SYNTAX OK"
bin/deploy-site.sh --dry-run 2>&1 | tail -15
```
Expected: `SYNTAX OK`; the dry-run prints the rsync + publish + verify plan without touching the host.

- [ ] **Step 5: Commit**

```bash
git add bin/deploy-site.sh marketing/robots.txt
git commit -m "build(site): bin/deploy-site.sh publishes code-health site to wikantik.com/site (+robots noindex)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RmDRawNAWsgDg1wux3unHc"
```

- [ ] **Step 6: Live publish (interactive — user runs it)**

The privileged copy needs the sudo password on the marketing host, so run it yourself:
```
! bin/deploy-site.sh
```
Expected: the three on-origin checks print `200`, ending with `published to https://wikantik.com/site/`. Then browse `https://wikantik.com/site/` to confirm through Cloudflare.

---

### Task 10: Document the runbook

Record how to regenerate and publish the site so it's discoverable later.

**Files:**
- Modify: `docs/ProjectReference.md` (add a "Code-health site" runbook subsection)
- Modify: `CLAUDE.md` (one-line pointer under Code Quality)

**Interfaces:** none (docs only).

- [ ] **Step 1: Add the runbook to `docs/ProjectReference.md`**

Add a subsection (place near the other operational runbooks):
```markdown
### Code-health site

A published Maven site aggregating coverage (unit+IT), module coupling, PMD/CPD,
SpotBugs, tests, tech-debt, and dependency health, with per-module drill-down.

- Generate: `bin/site.sh` (full unit+IT) or `bin/site.sh --unit-only` (fast).
  Two phases (coverage build → `mvn site site:stage`), run via `bin/agent-build.sh`.
  Output: `target/staging/index.html`.
- Publish: `! bin/deploy-site.sh` (interactive sudo on host `cloudflare`) →
  https://wikantik.com/site/ . Excluded from indexing via `marketing/robots.txt`.
- Requires graphviz (`dot`) on the generating box for the coupling SVG (optional;
  falls back to a linked `.dot`).
- Design: `docs/superpowers/specs/2026-07-23-code-health-site-design.md`.
```

- [ ] **Step 2: Add the CLAUDE.md pointer**

Under the **Code Quality** section of `CLAUDE.md`, add:
```markdown
# Full code-health site (coverage/coupling/PMD/CPD/SpotBugs/tests/tech-debt/deps)
bin/site.sh                 # -> target/staging/index.html
! bin/deploy-site.sh        # publish to https://wikantik.com/site (interactive sudo)
```

- [ ] **Step 3: Commit**

```bash
git add docs/ProjectReference.md CLAUDE.md
git commit -m "docs: code-health site runbook (bin/site.sh + bin/deploy-site.sh)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RmDRawNAWsgDg1wux3unHc"
```

---

## Self-Review

**Spec coverage:**
- Report set (coverage/coupling/PMD/SpotBugs/CPD/tests/taglist/deps/xref/javadoc) → Tasks 2–6 ✓
- Coupling = depgraph module graph + PMD design rules, no JDepend → Tasks 1, 5, 6 ✓
- Aggregate unit+IT coverage surfaced as a report → Task 4 ✓
- Two-phase build wrapped in `bin/site.sh` via agent-build → Task 7 ✓
- Both aggregate + per-module drill-down (`site:stage`) → Tasks 7, 8 ✓
- Landing "Code Health" dashboard + fix stale issue-tracker link → Task 6 ✓
- Publish to wikantik.com/site via deploy-marketing clone + robots noindex → Task 9 ✓
- Iteration loop (inspect vs canonical Maven sites, refine) → Task 8 ✓
- Runbook docs → Task 10 ✓
- Deviations noted for review: dropped the `site` profile (reporting only runs under `site` anyway; depgraph bound to `pre-site`); graphviz as a site-build prerequisite instead of pure-Java rendering.

**Placeholder scan:** No "TBD"/"handle edge cases"; every config step shows exact XML/bash. Two bounded "if it fails to resolve, do X" notes (plugin versions; `run-tests.sh` arg forwarding) are concrete conditional instructions, not placeholders.

**Type/name consistency:** Report link paths in the landing page (Task 6) match the outputs of Tasks 2–5 (`taglist.html`, `dependency-updates-report.html`, `pmd.html`, `cpd.html`, `wikantik-coverage-report/jacoco-aggregate/index.html`, `images/module-coupling.svg`) and are the exact paths verified in Task 8 Step 2. Script names (`bin/site.sh`, `bin/deploy-site.sh`) are consistent across Tasks 7, 9, 10.
