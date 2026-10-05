# Code Quality Gates and Tooling

This page is for developers who need to know which quality checks exist, how to run them, and what the current ratchet values are. Numbers that change live in one table below with a measurement date; everything else is stable procedure.

## Read the current numbers

Measured on 2026-10-05 from the poms and `build-support/`. Coverage floors may rise later; the pom is authoritative.

| Item | Value | Source |
|------|-------|--------|
| Root default line-coverage floor | 0.80 | `wikantik.coverage.line.minimum` in the root `pom.xml` |
| Floor 0.90 | `wikantik-insights`, `wikantik-main`, `wikantik-ontology` | module poms |
| Floor 0.89 | `wikantik-observability`, `wikantik-util` | module poms |
| Floor 0.88 | `wikantik-mcp-core` | module pom |
| Floor 0.87 | `wikantik-admin-mcp` | module pom |
| Floor 0.86 | `wikantik-http`, `wikantik-rest` | module poms |
| Floor 0.85 | `wikantik-cache`, `wikantik-event`, `wikantik-knowledge` | module poms |
| Floor 0.84 | `wikantik-cache-memcached`, `wikantik-scim`, `wikantik-tools` | module poms |
| Floor 0.83 | `wikantik-connectors`, `wikantik-extract-cli` | module poms |
| Floor 0.81 | `wikantik-ingest` | module pom |
| Floor 0.80 | `wikantik-jdbc` | module pom |
| Floor 0.71 | `wikantik-api` | module pom |
| PMD complexity baseline entries | 105 | non-comment `=` lines in `build-support/pmd-complexity-baseline.properties` |
| `TestSchemaSingleSourceTest` baseline entries | 1 (the `JDBCPluginCITest` carve-out) | `wikantik-war/src/test/resources/test-ddl-baseline.txt` |
| Frontend (Vitest) thresholds | lines 87, statements 85, functions 85, branches 76 | `wikantik-frontend/vite.config.js` |

`wikantik-bom`, `wikantik-war`, `wikantik-wikipages`, `wikantik-it-tests` and `wikantik-frontend` declare no Java floor of their own.

## Run the ratchets

Floors only go up, are never set above 0.90, and are the measured value rounded down to a whole percent. Baseline entries only ever come out.

```bash
# Per-module line-coverage floors (JaCoCo check goal, coverage-check execution)
mvn clean install -Pcoverage -DskipITs

# Complexity ratchet: fails on any NEW PMD design-rule violation not in the baseline
mvn pmd:check -Pcomplexity-gate

# Persistence ratchets in wikantik-war
mvn -pl wikantik-war test -Dtest='JdbcAccessArchTest,TestSchemaSingleSourceTest'
```

The war's classpath resolves sibling modules from `~/.m2`, so after you change a repository run `mvn install -DskipTests` before you trust the architecture tests.

The complexity rules are in `build-support/pmd-complexity-ruleset.xml` (CyclomaticComplexity, CognitiveComplexity, NPathComplexity, NcssCount, ExcessiveParameterList, GodClass, TooManyMethods), with thresholds above PMD defaults. To reduce debt, shrink a class until it clears its rules and delete its line from the baseline. A new baseline line needs a justification in the commit message.

## Run the reports

| Dimension | Command | Output |
|-----------|---------|--------|
| Duplication (CPD) | `mvn -fae org.apache.maven.plugins:maven-pmd-plugin:3.28.0:cpd` | `*/target/cpd.xml` |
| Complexity (PMD, report) | `mvn -fae -Pcomplexity-report org.apache.maven.plugins:maven-pmd-plugin:3.28.0:pmd` | `*/target/pmd.xml` |
| Bug-finding (PMD) | `mvn -fae org.apache.maven.plugins:maven-pmd-plugin:3.28.0:pmd` | uses `build-support/pmd-ruleset.xml` |
| Coverage, unit only | `mvn clean install -Pcoverage -DskipITs -T 1C` | `*/target/site/jacoco/jacoco.csv` |
| Code-health site (coverage, coupling, PMD and more) | `bin/site.sh` (`--unit-only`, `--skip-build`) | `target/staging/index.html` |

`bin/site.sh` needs Graphviz (`dot`) for the module-coupling SVG; without it the site links the raw `.dot` file. `wikantik-coverage-report` (added only under the `coverage` profile) aggregates JaCoCo data across modules.

## Run SpotBugs

The root pom configures `spotbugs-maven-plugin` with `effort=Max`, `threshold=Low`, `includeTests=false`, and the find-sec-bugs plugin. Reasoned suppressions are in `build-support/spotbugs-exclude.xml`. No workflow runs SpotBugs and it is not in the default build; Run it on demand:

```bash
mvn -fae com.github.spotbugs:spotbugs-maven-plugin:check
```

## Know the architecture and drift tests

| Test | Module | Guards |
|------|--------|--------|
| `JdbcAccessArchTest` | `wikantik-war` | No JDBC connection or transaction calls outside `com.wikantik.jdbc..` (`JDBCPlugin` excepted); see [ADR-0010](../adr/0010-one-data-access-primitive.md) |
| `TestSchemaSingleSourceTest` | `wikantik-war` | No hand-written `CREATE TABLE` in tests beyond the baseline file; `JDBCPluginCITest` is the permanent carve-out |
| `ConfigSurfaceDriftTest` | `wikantik-war` | Every `wikantik.*` key read in code is declared in `ini/wikantik.properties` with a default, description and type |
| `DecompositionArchTest` | `wikantik-main` | No new `getManager` callers or late-bound service fields on `WikiEngine` |
