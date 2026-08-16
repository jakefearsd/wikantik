#!/bin/bash
#
# SUPERSEDED — prefer `bin/run-tests.sh --parallel 4` (the canonical pre-commit
# gate) or `bin/run-tests.sh --all` (the pre-release gate). This script predates
# both and runs the slower single-reactor form. Kept because it is still in
# muscle memory; it now cleans up after itself properly.
#
# Two bugs fixed here, both of which cost real resources:
#
#  1. `docker rm -f` WITHOUT `-v` leaks an anonymous volume every time. Both
#     postgres and pgvector images declare `VOLUME /var/lib/postgresql/data`, so
#     Docker auto-creates an anonymous volume per container. Those volumes carry
#     only `com.docker.volume.anonymous` and no `org.testcontainers.*` label, so
#     nothing reaps them — an anonymous volume dies only with `docker rm -v`.
#     This leaked ~76-80 volumes per active development day.
#
#  2. `--filter ancestor=pgvector/pgvector:pg17` matched EVERY pgvector container
#     on the machine, including live testcontainers-managed ones belonging to
#     other projects, and force-killed them mid-run. Scoped to this project's own
#     naming (`wikantik-pg-<module>`, set by it.db.container-alias) instead.
set -euo pipefail

# Remove stale IT databases from a previous interrupted run — this project's only.
docker ps -a --filter "name=wikantik-pg-" -q | xargs -r docker rm -f -v

tomcat/tomcat-11/bin/shutdown.sh 2>/dev/null; sleep 3
mvn clean install -Pintegration-tests -fae
