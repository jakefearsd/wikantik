# Code Quality Baseline & Tooling

Baseline captured **2026-06-06**. This is the standing reference for the three
quality dimensions we track: **duplication**, **cyclomatic/cognitive complexity**,
and **line coverage**. Re-run the commands below to refresh the numbers.

All three tools are opt-in (kept out of the default build so it stays fast).

---

## How to run each analysis

| Dimension | Command | Output |
|---|---|---|
| **Duplication (CPD)** | `mvn -fae org.apache.maven.plugins:maven-pmd-plugin:3.28.0:cpd` | `*/target/cpd.xml` (+ `reports/cpd.html`) |
| **Complexity (PMD)** | `mvn -fae -Pcomplexity-report org.apache.maven.plugins:maven-pmd-plugin:3.28.0:pmd` | `*/target/pmd.xml` |
| **Coverage — unit only** | `mvn clean install -Pcoverage -DskipITs -T 1C` | `*/target/site/jacoco/jacoco.csv` |
| **Coverage — unit + IT (combined)** | unit build above, then run the IT modules with `-Pcoverage` (agent rides the Cargo JVM → `jacoco-it.exec`), then `mvn -Pcoverage org.jacoco:jacoco-maven-plugin:report-aggregate` | `wikantik-coverage-report/target/site/jacoco-aggregate/` |
| Bug-finding (PMD, existing) | `mvn -fae org.apache.maven.plugins:maven-pmd-plugin:3.28.0:pmd` | uses `build-support/pmd-ruleset.xml` |

- The bug-finding ruleset (`build-support/pmd-ruleset.xml`) is deliberately tuned
  for real bugs and **excludes complexity/metrics** rules.
- The complexity ruleset (`build-support/pmd-complexity-ruleset.xml`, run via the
  `complexity-report` profile) holds the metric rules, with thresholds set
  **above** PMD defaults to surface only genuine hotspots.

---

## Baseline (2026-06-06)

### Duplication — CPD (≥100 tokens)
- **31 duplicated blocks, 448 duplicated lines** across the tree. Low for the size.
- Top offenders:
  - `ScimUserResource` ↔ `ScimGroupResource` (~44 lines, 2 blocks) — **fixed**:
    extracted into `AbstractScimServlet` (shared `parseBody`/`parseIntParam`/
    `sendScim`/`sendError` + `CONTENT_TYPE`/`GSON`).
  - `ExperimentAggSweep`/`FinalSweep`/`GrandFinale` (retrieval research harness) —
    near-identical sweep scaffolding; low priority (throwaway research scripts).
  - `GraphRerankConfig` ↔ `HybridConfig` (20 lines) — config records; candidate
    for a shared base if they keep drifting together.
  - Intra-file blocks in `AdminPolicyResource`, `AdminHubDiscoveryResource`,
    `OllamaEmbeddingClient`, `BlogResource`.

### Complexity — PMD (above-default thresholds)
Violation counts: CyclomaticComplexity **118**, CognitiveComplexity **81**,
GodClass **71**, NPathComplexity **34**, NcssCount **23**, ExcessiveParameterList **11**.

Worst **class** cyclomatic totals (highest single method in parens):
| Class | total CC | hardest method |
|---|---|---|
| `AdminKnowledgeResource` | 303 | 25 |
| `WikiEngine` | 208 | 13 |
| `TextUtil` | 154 | 16 |
| `DefaultContextRetrievalService` | 133 | — |
| `ScimUserResource` | 125 | 24 |
| `AdminUserResource` | 119 | 26 |
| `DefaultKnowledgeGraphService` | 122 | — |
| `VersioningFileProvider` | 125 | 17 |

Worst **cognitive** methods: `ExtractionResponseParser:66` (52),
`ExtractionBatchRunner:150` (43), `WikiEngine:1418` (42),
`KeyFactsExtractor:61` (41), `AdminKnowledgeResource:550` (41).

> Most of the high-CC **classes** are managers/REST resources whose size is
> structural (many endpoints / branches). These are not a sweep — they want
> targeted method extraction, tracked in the backlog below. The single hardest
> *methods* (cognitive ≥40) are the better first targets.

### Coverage — JaCoCo
- **Unit-only: 76.3%** line (34 570 / 45 326), branch 65.8%.
- **Combined unit + ALL ITs: 82.6%** (37 362 / 45 229) — measured via the Cargo-JVM agent +
  `report-aggregate` (rest + sso + custom-jdbc under `-Pcoverage`). **Excluding the
  research/CLI tooling** (experiment harness + CLI mains, 3 068 lines at ~43%): **85.5%**.
  Goal: 90%.
- The unit-only number understates reality because classes exercised **only by
  integration tests** show 0% there even though they are well-tested:
  - `ScimUserResource` (329 lines) + `ScimGroupResource` (305) → covered by
    `ScimUsersIT`/`ScimGroupsIT`.
  - `AdminAuditResource`, `AdminProfilingServlet`, `AdminFrontmatterIssuesResource`,
    `AdminAgentGradeAuditServlet` → covered by the REST IT suite.
- **Research / entry-point code** also depresses the number and isn't meant for
  unit coverage: the `search.embedding.experiment` package (~900 lines, 8.5%) is
  the retrieval-experiment harness; `*Cli` / `EmbeddingCli` / `extractcli.*` are
  CLI mains; `WikiBootstrapServletContextListener` is container bootstrap.
- Genuine production packages worth raising (below 80%, excluding the above):
  `search.subsystem` (44%), `mcp.resources` (45%), `audit` (62%),
  `attachment` (69%), `render.subsystem.spam` (69%), `variables` (72%),
  `knowledge.extraction` (73%), `auth` (80%-).

---

## Prioritised remediation backlog

1. ✅ **DONE — Wired JaCoCo into the IT/Cargo runs + measured all ITs.** Agent on the Cargo
   Tomcat JVM + `wikantik-coverage-report` `report-aggregate` over rest + sso + custom-jdbc.
   Combined coverage 82.6% (85.5% ex research/CLI); SCIM/admin resources no longer read 0%.
   (Follow-up: add a coverage mode to `run-tests.sh` so the combined report is one command.)
2. ✅ **DONE (partial) — Cut the worst cognitive methods.** `ExtractionResponseParser.parse`
   (52) and `PageExtractionResponseParser.parse` (28) extracted to linear methods, below
   threshold, behind their tests. `ExtractionBatchRunner` (43) deferred — it has **no test**
   and is concurrency-orchestration code; write a test first before refactoring.
3. ✅ **DONE (partial) — Raised the lowest genuine packages.** `mcp.resources` 45% → ~70%
   (`WikiResources` 39 → 80%) and `mcp.prompts` 41% → ~95% (`WikiPrompts` 41 → 100%), via
   focused handler tests. **Still genuinely low (more-involved tests — `Context` /
   `RedirectException` / provider machinery):** `scim` 67.5% (error/edge paths),
   `render.subsystem.spam` 69% (`DefaultSpamPatternMatcher`/`ExternalSignals`),
   `attachment` 69% (providers).
4. **(duplication) Consolidate the config records** (`GraphRerankConfig`/
   `HybridConfig`) if they keep co-evolving.
5. **(scope decision) Decide whether to exclude the experiment/CLI research
   tooling from the coverage denominator** so the metric reflects shippable code.
   Deferred pending a call on what counts as "production".

---

## Done
- Stood up the complexity tooling (`pmd-complexity-ruleset.xml` + `complexity-report`
  profile) — previously only bug-finding PMD existed.
- Captured the baseline above.
- Removed the top duplication: `AbstractScimServlet` extraction (SCIM Users/Groups).
- **Wired JaCoCo into the Cargo IT runs** + `wikantik-coverage-report` aggregate →
  combined unit + IT coverage over all three IT modules (76.3% unit-only → 82.6%
  combined, 85.5% excluding research/CLI; SCIM/admin no longer 0%).
- **Cut the two worst response-parser cognitive methods** (52, 28 → below threshold).
- **Raised the two lowest genuine packages**: `WikiResources` 39 → 80%, `WikiPrompts`
  41 → 100% (focused MCP handler tests).
