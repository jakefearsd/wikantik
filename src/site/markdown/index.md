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
