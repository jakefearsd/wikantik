# Contributing to Wikantik

Wikantik is in active development and welcomes contributions — bug reports,
documentation improvements, code, design feedback. This file is the short
version; the long-form workflow lives in [`CLAUDE.md`](CLAUDE.md), which
walks every command needed to build, run, and test the project locally.

## Ways to contribute

- **Report a bug.** Open an issue using the bug template. Please include
  the version (commit SHA or release tag), the OS + Java + PostgreSQL
  versions, the failing command, and the full error / stack trace.
- **Suggest a feature.** Open a feature-request issue describing the
  problem you're trying to solve and how you'd want it solved. Concrete
  use cases beat abstract design.
- **Improve docs.** Anything under `docs/` — including the `wikantik-pages/`
  subset, which is also the live wiki — is fair game. PRs that fix
  factual drift between the docs and the running code are especially
  welcome.
- **Submit code.** See the development workflow below.

## Reporting security vulnerabilities

Please **do not** open a public issue. See [`SECURITY.md`](SECURITY.md)
for the disclosure process.

## Development workflow

### Prerequisites

- Java 21+, Maven 3.9+, Node 18+, PostgreSQL 15+ with pgvector 0.5+,
  Docker (optional but recommended).

See [README.md > Prerequisites](README.md#prerequisites) for the
full per-platform install guide, especially for `pgvector`.

### Setting up

```bash
git clone https://github.com/jakefearsd/wikantik.git
cd wikantik
cp .env.example .env                    # set POSTGRES_PASSWORD
sudo -u postgres DB_NAME=wikantik DB_APP_USER=jspwiki \
    DB_APP_PASSWORD='ChangeMe123!' bin/db/install-fresh.sh
mvn clean install -Dmaven.test.skip -T 1C
bin/deploy-local.sh
tomcat/tomcat-11/bin/startup.sh
# http://localhost:8080/  —  admin / admin123
```

The full guide lives at
[`docs/PostgreSQLLocalDeployment.md`](docs/PostgreSQLLocalDeployment.md).
For a container-based dev loop see `bin/container.sh --help`.

### Tests

```bash
# Unit tests (parallel, fast)
mvn clean install -T 1C -DskipITs

# Single test class
mvn test -Dtest=MarkdownRendererTest

# Single test method
mvn test -Dtest=MarkdownRendererTest#testMarkupSimpleMarkdown

# Full integration suite — MUST be sequential (no -T flag), -fae so all
# IT modules run even if one has failures
mvn clean install -Pintegration-tests -fae
```

Integration tests stand up an ephemeral PostgreSQL+pgvector container per
module via `fabric8:docker-maven-plugin`. They run sequentially because
they share fixed ports — running them in parallel causes flaky failures
on port collisions.

### Code conventions

- **Tests first.** Add a failing test that pins the bug or new behaviour
  before the fix/feature. The test must fail without your change and
  pass with it.
- **Never swallow exceptions** with empty catch blocks — always at least
  `LOG.warn()` with context.
- **Migrations are versioned and idempotent.** Every commit that
  changes the schema adds the next `bin/db/migrations/V<NNN>__<desc>.sql`
  with `CREATE TABLE IF NOT EXISTS`, `ADD COLUMN IF NOT EXISTS`, etc.
  Data backfills go in `bin/db/one-shots/` (not in `Vxxx`).
  See [`bin/db/migrations/README.md`](bin/db/migrations/README.md).
- **`bin/` scripts have `--help`.** When adding or modifying a script,
  keep its top-of-file docstring accurate — it's what `--help` prints.
- **Don't commit generated files.** `Main.md` is built from
  `Main.pins.yaml`; `target/` is gitignored; `tomcat/` is local-only.

### Pull requests

1. Branch from `main`.
2. Squash commits into a logical set; each commit message should
   explain *why* the change matters, not just what.
3. Run the full IT reactor before opening: `mvn clean install -Pintegration-tests -fae`.
4. Open the PR. Use the template; link any related issues.
5. Wait for CI to go green. Reviewers will look at the diff and comment.

### Architecture orientation

If you're touching a new area:

- The high-level module map is in
  [README.md > Module Structure](README.md#module-structure).
- The Page Graph and Knowledge Graph are *separate subsystems*. Read
  [`docs/wikantik-pages/PageGraphVsKnowledgeGraph.md`](docs/wikantik-pages/PageGraphVsKnowledgeGraph.md)
  before working in either.
- The architectural reviews under
  [`docs/ArchitectureCritique.md`](docs/ArchitectureCritique.md) and
  [`docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md`](docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md)
  give the long view.

## Code of Conduct

By participating, you agree to abide by the
[Contributor Covenant](CODE_OF_CONDUCT.md).

## License

By contributing, you agree that your contributions will be licensed
under the Apache License 2.0 (see [`LICENSE`](LICENSE)). If the project
license changes, contributors will be notified before merging further
changes.
