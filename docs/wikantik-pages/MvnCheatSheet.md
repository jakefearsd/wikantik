---
type: article
tags:
- uncategorized
canonical_id: 01KQ0P44SW7R21KKGKCC03WQ00
cluster: wikantik-development
title: Maven Cheat Sheet
summary: Quick reference for the Maven commands used in Wikantik development — build,
  test, run, and release workflows.
---
1. 1. IDE Specific

| Maven Command       | Description                                                        |
|---------------------|--------------------------------------------------------------------|
| mvn eclipse:eclipse | generates Eclipse project files (alternatively, you could use m2e) |
| mvn idea:idea       | generates IDEA IntelliJ project files                              |


1. 2. Build Specific

| Maven Command (1)                                               | Description                                                                                                                         |
|-----------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------|
| mvn                                                             | runs the root pom's default goal, `verify apache-rat:check`. RAT is informational and currently reports violations, so a bare `mvn` ends red; run an explicit goal instead |
| mvn clean install                                               | performs a full build including the unit tests (about 4 minutes with the forked, partitioned surefire run)                          |
| mvn clean install -DskipTests                                   | performs a build, compiling the tests and building the test-jars but not running them. Faster; not for final checks                 |
| mvn clean install -Dmaven.test.skip                             | **do not use**: it also skips building wikantik-main's test-jar, which wikantik-admin-mcp, wikantik-tools, wikantik-knowledge and the IT modules depend on, so a full-reactor build fails |
| mvn clean install -T 1C -DskipITs                               | parallel unit-test build (what phase 1 of `bin/run-tests.sh` runs)                                                                  |
| mvn clean test                                                  | compiles the source and executes the tests                                                                                          |
| mvn test -Dtest=MarkdownRendererTest                             | run just a single test class                                                                                                        |
| mvn test -Dtest=MarkdownRendererTest#testMarkupSimpleMarkdown              | run just a single test within a test class                                                                                          |
| mvn test -pl wikantik-main -Dtest=JDBCUserDatabaseTest          | run one test class in one module (tests that touch a database start a pgvector container through Docker)                            |
| mvn test -Dtest=TestClassName#methodName -Dmaven.surefire.debug | debug a test in Eclipse or IDEA to see why it's failing (see http://www.jroller.com/gmazza/entry/jpa_and_junit#debugging)           |
| mvn compile -pl wikantik-main -q                                | compile-check one module; after a signature change use `test-compile` instead, because `compile` skips the test sources             |
| bin/agent-build.sh start unit -- mvn clean install -DskipITs    | run a build that may exceed ~5 minutes detached from the shell; poll with `bin/agent-build.sh status unit` / `wait unit 540`         |
| bin/deploy-local.sh, bin/redeploy.sh                            | run Wikantik on a local Tomcat 11 as the ROOT context at http://localhost:8080/ (first time / after config changes, and the fast routine redeploy). The Cargo `run` goal is not wired into the war module; Cargo only launches Tomcat for the IT modules |
| bin/run-tests.sh --parallel 4                                   | the canonical pre-commit gate: all unit tests, then the five IT modules in one 4-wide reactor (needs Docker)                         |
| mvn clean install -Pintegration-tests                           | performs a build, enabling the integration tests under wikantik-it-tests. Do not add `-T` to a raw invocation; parallel ITs are supported only through `bin/run-tests.sh --parallel N` |
| mvn test -Dtest=MemoryProfiling                                 | (from wikantik-main module) runs a memory profiling test                                                                             |

(1) `-T 1C` can be added to the unit-test builds above in order to run a parallel build, thus decreasing build time, i.e., `mvn clean install -T 1C -DskipITs`.

1. 3. Reports Specific

| Maven Command                                           | Description                                                                                         |
|---------------------------------------------------------|-----------------------------------------------------------------------------------------------------|
| mvn apache-rat:check                                    | creates an Apache RAT report. Informational only: it is not bound to any lifecycle phase and currently reports violations (docs, scripts and the frontend carry no license headers). See: http://creadur.apache.org/rat/apache-rat-plugin/plugin-info.html |
| mvn clean install -DskipITs -Pcoverage                  | generates the JaCoCo coverage report (aggregated in wikantik-coverage-report) and enforces each module's line-coverage floor |
| mvn pmd:check -Pcomplexity-gate                         | the CI complexity ratchet: fails on any new PMD design-rule violation not in `build-support/pmd-complexity-baseline.properties` (CI runs it with `-fn` and requires `BUILD SUCCESS`) |
| bin/site.sh                                             | builds the full code-health site (coverage, coupling, PMD, CPD, SpotBugs, tests, dependencies) into `target/staging/index.html` |
| mvn javadoc:javadoc                                     | creates javadocs adding some UML class/package level diagrams                                       |
| mvn sonar:sonar                                         | generates a Sonar report. Expects a Sonar server running at http://localhost:9000/                  |
