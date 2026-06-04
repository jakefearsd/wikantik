# IT-Module Parallelism (Opt-In) Design

**Status:** approved 2026-06-04
**Scope:** make the four integration-test modules parallel-safe (each gets its own dynamically-reserved ports + a unique container), then add an **opt-in** parallel mode to `bin/run-tests.sh`. The default behavior is unchanged (sequential).

## Goal

Recover the (bounded) wall-clock gain from overlapping the small IT modules with the long `custom-jdbc` browser module — *when explicitly requested*. Today the four IT modules (`rest`, `sso`, `sso-saml`, `custom-jdbc`) cannot run in parallel: they share Cargo's default servlet/RMI ports (8080/8205), the Docker Postgres host port `55432`, and the container alias `wikantik-pg`, so `mvn -Pintegration-tests -T …` collides ("container name already in use" / "port 55432 already allocated"). This makes each module self-contained on ports + container name so a single `-T` reactor can run them concurrently.

## Non-goals (out of scope)

- **Changing the default** — `bin/run-tests.sh` with no env stays sequential (the current safe loop). Parallel IT execution is opt-in only.
- Fixed per-module port lanes (dynamic free-port reservation was chosen instead).
- Parallelizing tests *within* a module beyond the already-shipped browser cluster.
- Concurrent separate `mvn` invocations (a single `-T` reactor is used — one Maven process, so no concurrent-`mvn` surefire/fork corruption).

## 1. Port isolation — dynamic, per module

Add a `build-helper-maven-plugin:reserve-network-ports` execution, bound to an early phase that runs **before** the Docker/Cargo `pre-integration-test` startup (e.g. `process-test-resources`), that reserves free TCP ports and overrides the existing port properties per module:

- `it.db.port` — Docker Postgres host port (currently the hardcoded `55432`).
- `it.wikantik.servlet.port` and `it.wikantik.rmi.port` — Cargo Tomcat servlet + RMI control ports.
- `it-wikantik.mock-oauth.port` (in `sso`) and `it-wikantik.saml-idp.port` (in `sso-saml`).

Everything downstream already reads these via `${…}` — the `it-wikantik.base.url` system property, the JDBC URL, `pg_isready`, the Cargo `<properties>` block, and the Docker `<port>` mappings — so reserving free ports flows through with no extra wiring. Because the ports are reserved at build time per module, no two parallel modules can pick the same port.

This is a per-module concern, so the `reserve-network-ports` execution lives where the cargo/docker plugin executions already live (the parent `wikantik-it-tests` build config that each module inherits, plus the per-module `sso`/`sso-saml` mock-port reservations).

## 2. Container isolation

The shared container alias `it.db.container-alias = wikantik-pg` (whose auto-generated name `pgvector-N` collided) becomes **unique per module** (derived from the module — e.g. an override per module pom, or a `containerNamePattern` incorporating `${project.artifactId}`). The `pg-cleanup-stale` step — which today does `docker rm -f` on any container matching the shared `it.db.port` / image — is reworked to target the **module's own container name**, so it can never delete a concurrently-running sibling's container. With unique names + dynamically-reserved ports, cross-module collision is structurally impossible.

## 3. Runner opt-in mode

Add an `IT_PARALLELISM` env to `bin/run-tests.sh` (default `1`, mirroring `UNIT_PARALLELISM`):

- `IT_PARALLELISM=1` (default) — the existing sequential per-module loop, unchanged.
- `IT_PARALLELISM=N` (N>1) — Phase 2 runs a single
  `mvn install -Pintegration-tests -T N -fae -pl <rest,sso,sso-saml,custom-jdbc>`
  reactor (one Maven process; the surefire/failsafe fork-node fix already in place handles the parallel forks). This is one timed `run_step`; the report line reads `IT (parallel ×N)`.

So `IT_PARALLELISM=4 bin/run-tests.sh` gives the full-overlap run; bare `bin/run-tests.sh` is byte-for-byte the same behavior as today.

The `--module X` single-module path is unaffected (always one module, no parallelism).

## 4. Verification

- Run `IT_PARALLELISM=4 bin/run-tests.sh --it` **at least 3 times** and confirm: every module green, no `container name already in use` / `port already allocated` / `pg-cleanup` errors, and no test flakiness under contention.
- Confirm the **default** (`bin/run-tests.sh --it`, `IT_PARALLELISM` unset) still runs sequentially and green.
- Record the parallel vs sequential IT-phase wall-clock (the new per-step `[Xm YYs]` + `TOTAL RUNTIME` lines make this a direct read).
- Also confirm a single-module run (`bin/run-tests.sh --module rest`) still works (it reserves its own ports too — harmless when run alone).

## Honest expected gain

Bounded by `custom-jdbc` (the browser floor). At `-T 4` the IT-phase wall-clock drops toward that floor instead of the sum of all four modules — roughly **1–2 minutes** saved — realized only when `IT_PARALLELISM>1` is set.

## Components / files

- **Modify:** `wikantik-it-tests/pom.xml` — add the `reserve-network-ports` execution for `it.db.port` + the Cargo servlet/RMI ports; make the container alias/name per-module-unique; rework `pg-cleanup-stale` to key on the module's own container.
- **Modify:** `wikantik-it-tests/wikantik-it-test-sso/pom.xml` and `…-sso-saml/pom.xml` — reserve their extra mock ports dynamically.
- **Modify:** `bin/run-tests.sh` — add `IT_PARALLELISM` (default 1 → sequential loop; >1 → single `-T N` IT reactor).
- **No change** to any test code, the app, or the default runner behavior.

## Testing approach

This is build/CI infrastructure; correctness is proven by the verification campaign (§4): ≥3 green parallel runs with no collision/flakiness, the default still sequential-green, and the recorded wall-clock reduction. No new product tests.
