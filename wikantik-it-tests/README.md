# Integration Tests

The `integration-tests` Maven profile boots a Tomcat 11 instance (via Cargo)
plus a PostgreSQL 17 + pgvector container (via
[`io.fabric8:docker-maven-plugin`](https://dmp.fabric8.io/)) for every IT
submodule. Schema is applied by the production `migrate.sh` script, and
`src/main/resources/sql/it-test-seed.sql` adds the test fixtures.

There are **five default IT modules**: `wikantik-it-test-rest`,
`wikantik-it-test-sso`, `wikantik-it-test-knowledge-disabled`,
`wikantik-it-test-custom-jdbc`, `wikantik-it-test-dense` (`wikantik-selenide-tests`
is a shared support module, not a runnable IT suite of its own). There is
also an opt-in **Authentik SCIM full-loop** suite
(`wikantik-it-test-scim-fullloop`), built only under the `-Pscim-fullloop`
profile and always run sequentially — never folded into a parallel run.
Only `wikantik-it-test-dense` uses the shared CPU-ollama embedder (port
11435); invoking it directly with a bare `mvn -pl ...` skips that setup and
the suite fails at `@BeforeAll` with the compose command it wanted.

## Prerequisites

- **Docker daemon** running locally
- **`psql`** on the `PATH` (used by the seed step)
- JDK 25+, Maven 3.9+
- A gitignored `it-db.properties` file — copy from the template on first run:
  ```
  cp wikantik-it-tests/it-db.properties.template wikantik-it-tests/it-db.properties
  ```

## Running

The canonical pre-commit gate, from the repo root, is:

```bash
bin/run-tests.sh --parallel 4
```

This runs the unit phase, then all five default IT modules in a single
`-T 4` reactor. Parallel IT execution is supported **only** through this
script: each module reserves its own free `it.db.port`
(build-helper `reserve-network-port` — there is no longer a static
`<it.db.port>` to edit in `wikantik-it-tests/pom.xml`) and a
uniquely-named pgvector container (`wikantik-pg-${project.artifactId}`), so
modules never collide. Do **not** bolt `-T` onto a raw
`mvn clean install -Pintegration-tests` invocation yourself.

A raw, sequential fallback still works (slower, and re-runs all unit tests
inside the IT phase — see the root `CLAUDE.md` for the full comparison):

```bash
# All IT modules under one profile, sequential — no parallelism.
mvn clean install -Pintegration-tests -fae
```

or one module at a time via the wrapper script:

```bash
bin/run-tests.sh --module rest   # rest|sso|knowledge-disabled|custom-jdbc|dense|scim-fullloop
bin/run-tests.sh --fullloop      # ONLY the opt-in Authentik SCIM full-loop
```

## Troubleshooting

- **`psql: command not found`:** install `postgresql-client` (e.g.
  `sudo apt install postgresql-client` on Debian/Ubuntu).
- **Container left running after `Ctrl-C` or a completed run:**
  `autoRemove` is `false` by design (the pgvector container is stopped, not
  removed, at `post-integration-test` so its logs stay inspectable after a
  failure) — an interrupted build never even reaches that phase, so the
  container keeps running. `docker ps -a | grep wikantik-pg` and
  `docker stop <id>` / `docker rm <id>` to clean it up.
