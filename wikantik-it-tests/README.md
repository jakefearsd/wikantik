# Integration Tests

The `integration-tests` Maven profile boots a Tomcat 11 instance (via Cargo)
plus a PostgreSQL 17 + pgvector container (via
[`io.fabric8:docker-maven-plugin`](https://dmp.fabric8.io/)) for every IT
submodule. Schema is applied by the production `migrate.sh` script, and
`src/main/resources/sql/it-test-seed.sql` adds the test fixtures.

## Prerequisites

- **Docker daemon** running locally
- **`psql`** on the `PATH` (used by the seed step)
- JDK 21+, Maven 3.9+
- A gitignored `it-db.properties` file — copy from the template on first run:
  ```
  cp wikantik-it-tests/it-db.properties.template wikantik-it-tests/it-db.properties
  ```

## Running

```bash
# All IT modules, including SSO and Hub pgvector tests, run under one profile.
mvn clean install -Pintegration-tests -fae
```

IT modules must run sequentially — **do not** pass `-T` with the
`integration-tests` profile. Port 55432 is reused across modules.

## Troubleshooting

- **Port 55432 bound:** stop any local PG listening on that port, or change
  `<it.db.port>` in `wikantik-it-tests/pom.xml`.
- **`psql: command not found`:** install `postgresql-client` (e.g.
  `sudo apt install postgresql-client` on Debian/Ubuntu).
- **Container left running after `Ctrl-C`:** `autoRemove=true` should clean
  it up, but if something leaks, `docker ps | grep wikantik-pg` and
  `docker stop <id>`.
