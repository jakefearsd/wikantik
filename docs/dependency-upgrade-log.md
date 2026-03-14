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
