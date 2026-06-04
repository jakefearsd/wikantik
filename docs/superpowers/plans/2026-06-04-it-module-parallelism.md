# IT-Module Parallelism (Opt-In) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the four integration-test modules parallel-safe (each reserves its own free TCP ports + uses a uniquely-named Postgres container), then add an **opt-in** `IT_PARALLELISM` mode to `bin/run-tests.sh`. Default behavior (sequential per-module loop) is unchanged.

**Architecture:** Bind `build-helper-maven-plugin:reserve-network-ports` to `process-test-resources` (before the Docker/Cargo `pre-integration-test` startup) to overwrite the shared port properties with dynamically-reserved free ports per module. Give the pgvector container a per-module-unique name (so two parallel modules never collide on container name), and rework the stale-container cleanup to target only that module's own container. Finally, teach `bin/run-tests.sh` to run a single `-T N` IT reactor when `IT_PARALLELISM>1`.

**Tech Stack:** Maven multi-module reactor, `build-helper-maven-plugin` (`reserve-network-ports`), `docker-maven-plugin` (pgvector Postgres), `cargo-maven3-plugin` (Tomcat 11), failsafe, `bin/run-tests.sh`.

**Spec:** `docs/superpowers/specs/2026-06-04-it-module-parallelism-design.md`

---

## Verified context (read before starting)

**Sole developer works directly on `main`** (per CLAUDE.md) — no feature branch, no worktree. Commit to `main`.

**Where the IT plugins live:** `wikantik-it-tests/pom.xml` carries the shared IT build config (docker-maven-plugin, cargo-maven3-plugin, exec-maven-plugin) in its `<build><plugins>` block under the `integration-tests` profile. Each of the four IT modules inherits these executions. The build-helper executions you add go in the **same** `<build><plugins>` block so they are inherited identically.

**Current port wiring (all the values you must make dynamic):**
- `it.db.port` = `55432` — defined in `wikantik-it-tests/pom.xml` `<properties>`. Used by: the docker `<port>${it.db.port}:5432</port>` mapping, the Cargo JDBC datasource URL, `pg-wait-ready`/`pg-apply-migrations`/`pg-seed-test-data` exec steps.
- `it.wikantik.servlet.port` = `18080` — defined in the **root** `pom.xml:82`. Wired to Cargo's HTTP port via `<cargo.servlet.port>${it.wikantik.servlet.port}</cargo.servlet.port>` in the **root** `pom.xml:1014` (pluginManagement). Also used in each module's `it-wikantik.base.url` failsafe system property.
- **Cargo RMI control port** — currently **NOT set**, so Cargo defaults to **8205** (the historical "Port number 8205 (cargo.rmi.port) is in use" collision). There is no `it.wikantik.rmi.port` property yet — this plan introduces one.
- `it-wikantik.mock-oauth.port` = `8088` — defined in `wikantik-it-tests/wikantik-it-test-sso/pom.xml:42`; docker `<port>${it-wikantik.mock-oauth.port}:8080</port>`.
- `it-wikantik.saml-idp.port` = `8089` — defined in `wikantik-it-tests/wikantik-it-test-sso-saml/pom.xml:42`; docker `<port>${it-wikantik.saml-idp.port}:8080</port>`.

**Container naming today:** the pgvector image uses `<alias>${it.db.container-alias}</alias>` where `it.db.container-alias = wikantik-pg`. docker-maven-plugin's *default* container-name pattern is `%n-%i` (image-simple-name + index), which is why running containers show up as `pgvector-N` — **the alias is NOT the container name unless `containerNamePattern` says so.** Two parallel modules both get `pgvector-1` → "container name already in use". `it.db.container-alias` is referenced only at the property definition and the `<alias>` element (no links depend on it), so its value is safe to change.

**`pg-cleanup-stale`** (exec-maven-plugin, `initialize` phase, in `wikantik-it-tests/pom.xml`) currently removes any container matching `ancestor=${pgvector.image}` OR `publish=${it.db.port}`. Under parallelism that `ancestor`-match would delete a concurrently-running sibling's pgvector container. It must be reworked to remove **only this module's own** container by name. (Note: `initialize` runs *before* `process-test-resources`, so the dynamic `it.db.port` is not reserved yet at cleanup time — another reason the cleanup must key on the static container *name*, not the port.)

**build-helper-maven-plugin** is already version-managed (root `pom.xml`, `plugin.build-helper.version = 3.6.1`) — you do **not** add a `<version>`.

**No concurrent Maven builds during verification** — run each verification command one at a time. `bin/run-tests.sh` holds a `flock` so it already refuses to run concurrently with itself. The `--module` and `--unit`/`--it` paths assume a prior `bin/run-tests.sh --unit` (or `mvn install -DskipITs`) has installed artifacts to `~/.m2`; if `~/.m2` is cold, run `bin/run-tests.sh --unit` first.

---

## Task 1: Per-module port + container isolation in the IT parent pom

**Files:**
- Modify: `wikantik-it-tests/pom.xml` (properties, docker image block, cargo properties block, exec `pg-cleanup-stale`, add a build-helper execution)

- [ ] **Step 1: Make `it.db.container-alias` per-module-unique and add the RMI-port fallback property**

In `wikantik-it-tests/pom.xml`, in the `<properties>` block, change the container alias to incorporate the module's artifactId and add an `it.wikantik.rmi.port` fallback default:

```xml
<it.db.container-alias>wikantik-pg-${project.artifactId}</it.db.container-alias>
```

and add (next to the other `it.*` properties):

```xml
<!-- Cargo container-control RMI port. Cargo defaults to 8205 when unset
     (the historical "cargo.rmi.port 8205 is in use" collision). This is the
     fallback; build-helper reserve-network-ports overrides it per module so
     parallel IT modules never share it. -->
<it.wikantik.rmi.port>8205</it.wikantik.rmi.port>
```

`${project.artifactId}` resolves against the *inheriting child* module (e.g. `wikantik-pg-wikantik-it-test-rest`), so each of the four modules gets a distinct alias.

- [ ] **Step 2: Name the pgvector container after its alias**

In the docker-maven-plugin `<image>` block for the pgvector image, inside the `<run>` element (alongside `<ports>`, `<env>`, `<wait>`, `<autoRemove>`), add:

```xml
<containerNamePattern>%a</containerNamePattern>
```

`%a` = the image alias, so the container name becomes `wikantik-pg-${project.artifactId}` — unique per module. (If a `docker ps` check in Step 7 shows the pattern was ignored at the `<run>` level for this docker-maven-plugin version, move the `<containerNamePattern>%a</containerNamePattern>` element up one level to be a direct child of `<image>` and re-verify.)

- [ ] **Step 3: Wire Cargo's RMI port to the (reservable) property**

In the cargo-maven3-plugin `<configuration><configuration><properties>` block in `wikantik-it-tests/pom.xml` (the block that currently sets only `<cargo.jvmargs>`), add a sibling element:

```xml
<cargo.rmi.port>${it.wikantik.rmi.port}</cargo.rmi.port>
```

- [ ] **Step 4: Reserve free ports per module (db, servlet, rmi)**

Add a `build-helper-maven-plugin` execution to the same `<build><plugins>` block in `wikantik-it-tests/pom.xml` (where docker/cargo/exec already live). It overwrites the three port properties with reserved free ports *before* Docker/Cargo start:

```xml
<plugin>
  <groupId>org.codehaus.mojo</groupId>
  <artifactId>build-helper-maven-plugin</artifactId>
  <executions>
    <execution>
      <id>reserve-it-ports</id>
      <phase>process-test-resources</phase>
      <goals><goal>reserve-network-ports</goal></goals>
      <configuration>
        <portNames>
          <portName>it.db.port</portName>
          <portName>it.wikantik.servlet.port</portName>
          <portName>it.wikantik.rmi.port</portName>
        </portNames>
      </configuration>
    </execution>
  </executions>
</plugin>
```

`process-test-resources` runs after `initialize` (the cleanup phase) and before `pre-integration-test` (docker/cargo start), so every downstream `${it.db.port}` / `${it.wikantik.servlet.port}` / `${it.wikantik.rmi.port}` reference resolves to the reserved free port.

- [ ] **Step 5: Rework `pg-cleanup-stale` to target only this module's container**

In the exec-maven-plugin `pg-cleanup-stale` execution in `wikantik-it-tests/pom.xml`, replace the single `<argument>` that does the `ancestor`/`publish` removal with a name-targeted removal:

```xml
<argument>-c</argument>
<argument>docker rm -f "${it.db.container-alias}" >/dev/null 2>&amp;1 &amp;&amp; echo "Removed stale container ${it.db.container-alias}" || true</argument>
```

This removes only `wikantik-pg-${project.artifactId}` (this module's own container) if it lingered from a prior run, and can never delete a concurrently-running sibling module's container.

- [ ] **Step 6: Compile-check the reactor model (poms parse, no run yet)**

Run: `mvn -q -pl wikantik-it-tests -Pintegration-tests help:effective-pom -Doutput=/dev/null`
Expected: BUILD SUCCESS (the effective POM assembles — proves the XML edits are well-formed and the profile/plugin config is valid). If it fails, read the parse/validation error and fix the offending element before proceeding.

- [ ] **Step 7: Verify a single module still runs green (sequential, but now on reserved ports + a uniquely-named container)**

Run: `bin/run-tests.sh --module rest`
Expected: `PASS  IT: wikantik-it-tests/wikantik-it-test-rest  [Xm YYs]   Tests run: …, Failures: 0, Errors: 0` and `RESULT: ALL PASSED`.
While it runs (or from the log), confirm the container came up uniquely-named:
`docker ps --format '{{.Names}}' | grep wikantik-pg` → expect `wikantik-pg-wikantik-it-test-rest` (NOT `pgvector-1`). If it shows `pgvector-N`, the `containerNamePattern` placement was wrong — apply the Step 2 fallback (move it to be a direct child of `<image>`) and re-run.
(Long run: Cargo + Postgres. If `clean` fails on a lock: `pkill -9 -f "surefire.*booter|plexus.classworlds|org.codehaus.cargo"` then retry — never kill `java -jar app.jar` or a `tomcat/tomcat-11` process.)

- [ ] **Step 8: Commit**

```bash
git add wikantik-it-tests/pom.xml
git commit -m "test(it): per-module dynamic ports + unique pgvector container name

Reserve it.db.port / it.wikantik.servlet.port / it.wikantik.rmi.port via
build-helper before docker/cargo start; name the pgvector container after a
per-artifactId alias; rework pg-cleanup-stale to remove only this module's
container. Makes the IT modules parallel-safe. No behavior change when run
sequentially."
```

---

## Task 2: Reserve the extra mock-server ports in the sso / sso-saml modules

**Files:**
- Modify: `wikantik-it-tests/wikantik-it-test-sso/pom.xml` (add a build-helper execution for `it-wikantik.mock-oauth.port`)
- Modify: `wikantik-it-tests/wikantik-it-test-sso-saml/pom.xml` (add a build-helper execution for `it-wikantik.saml-idp.port`)

These two modules inherit Task 1's `reserve-it-ports` (db/servlet/rmi) and the unique pgvector container from the parent. They each additionally run one extra Docker container (mock-oauth2-server / simplesamlphp) on a fixed port that must also become dynamic.

- [ ] **Step 1: Reserve the mock-oauth port in the sso module**

In `wikantik-it-tests/wikantik-it-test-sso/pom.xml`, add a build-helper execution to its `integration-tests` profile `<build><plugins>` block (use a **distinct execution id** so it runs alongside the inherited `reserve-it-ports`):

```xml
<plugin>
  <groupId>org.codehaus.mojo</groupId>
  <artifactId>build-helper-maven-plugin</artifactId>
  <executions>
    <execution>
      <id>reserve-mock-oauth-port</id>
      <phase>process-test-resources</phase>
      <goals><goal>reserve-network-ports</goal></goals>
      <configuration>
        <portNames>
          <portName>it-wikantik.mock-oauth.port</portName>
        </portNames>
      </configuration>
    </execution>
  </executions>
</plugin>
```

- [ ] **Step 2: Reserve the SAML-IdP port in the sso-saml module**

In `wikantik-it-tests/wikantik-it-test-sso-saml/pom.xml`, add the analogous execution to its `integration-tests` profile `<build><plugins>` block:

```xml
<plugin>
  <groupId>org.codehaus.mojo</groupId>
  <artifactId>build-helper-maven-plugin</artifactId>
  <executions>
    <execution>
      <id>reserve-saml-idp-port</id>
      <phase>process-test-resources</phase>
      <goals><goal>reserve-network-ports</goal></goals>
      <configuration>
        <portNames>
          <portName>it-wikantik.saml-idp.port</portName>
        </portNames>
      </configuration>
    </execution>
  </executions>
</plugin>
```

- [ ] **Step 3: Verify each module runs green individually**

Run (one at a time):
```bash
bin/run-tests.sh --module sso
bin/run-tests.sh --module sso-saml
```
Expected: both `PASS … Failures: 0, Errors: 0` / `RESULT: ALL PASSED`. The mock-oauth / saml-idp containers now bind reserved free ports; the OIDC discovery / SAML metadata wait-URLs (which already read `${it-wikantik.mock-oauth.port}` / `${it-wikantik.saml-idp.port}`) follow automatically.

- [ ] **Step 4: Commit**

```bash
git add wikantik-it-tests/wikantik-it-test-sso/pom.xml wikantik-it-tests/wikantik-it-test-sso-saml/pom.xml
git commit -m "test(it): reserve mock-oauth / saml-idp ports dynamically per module"
```

---

## Task 3: Runner opt-in `IT_PARALLELISM` mode

**Files:**
- Modify: `bin/run-tests.sh` (add `IT_PARALLELISM`; default 1 = unchanged sequential loop; >1 = single `-T N` IT reactor)

- [ ] **Step 1: Add the `IT_PARALLELISM` env (default 1) next to `UNIT_PARALLELISM`**

In `bin/run-tests.sh`, immediately after the `UNIT_PARALLELISM="${UNIT_PARALLELISM:-1C}"` block (around line 72), add:

```bash
# IT-phase parallelism (opt-in). Default 1 = the safe sequential per-module
# loop (one module at a time). IT_PARALLELISM=N (N>1) runs a SINGLE
# `mvn install -Pintegration-tests -T N` reactor over all IT modules at once;
# each module now reserves its own free ports + uniquely-named pgvector
# container (build-helper reserve-network-ports), so they no longer collide.
# One Maven process, so the surefire/failsafe fork-node fix handles the forks.
IT_PARALLELISM="${IT_PARALLELISM:-1}"
```

- [ ] **Step 2: Branch the IT phase on `IT_PARALLELISM`**

In `bin/run-tests.sh`, replace the IT-phase block (the `if [ "$RUN_IT" = 1 ]; then for mod in … done` loop, currently lines ~130-136) with a branch that keeps the sequential loop for the default and adds a single parallel reactor for `IT_PARALLELISM>1`:

```bash
if [ "$RUN_IT" = 1 ]; then
  if [ "$IT_PARALLELISM" -gt 1 ] 2>/dev/null; then
    # Single parallel reactor over all IT modules. Each module reserves its
    # own ports + container name, so -T N cannot collide. -fae so every module
    # runs even if one fails. -pl <all four>, NO -am (deps already installed by
    # Phase 1), so the ~6000 unit tests are not re-run.
    it_pl="$(IFS=,; echo "${IT_MODULES[*]}")"
    run_step "IT (parallel x${IT_PARALLELISM})" "${LOG_DIR}/it-parallel.log" \
      install -Pintegration-tests -fae -T "${IT_PARALLELISM}" -pl "$it_pl"
  else
    for mod in "${IT_MODULES[@]}"; do
      # -pl <module> WITHOUT -am: deps resolve from the Phase-1 install, so unit
      # tests are not re-run. Sequential (default). -fae within the module.
      run_step "IT: ${mod}" "${LOG_DIR}/it-$(basename "$mod").log" \
        install -Pintegration-tests -fae -pl "$mod"
    done
  fi
elif [ -n "$ONE_MODULE" ]; then
  mod="wikantik-it-tests/wikantik-it-test-${ONE_MODULE}"
  [ -d "$mod" ] || { echo "no such IT module: $mod" >&2; exit 2; }
  run_step "IT: ${mod}" "${LOG_DIR}/it-${ONE_MODULE}.log" \
    install -Pintegration-tests -fae -pl "$mod"
fi
```

(The `--module` single-module path is unchanged — always one module, no parallelism.)

- [ ] **Step 3: Update the usage/header comment to document `IT_PARALLELISM`**

In the header comment block of `bin/run-tests.sh` (the `# Usage:` section, lines ~19-24), add a line documenting the env so `--help` surfaces it:

```bash
#   IT_PARALLELISM=4 bin/run-tests.sh --it   # opt-in: run all IT modules in one -T 4 reactor
```

- [ ] **Step 4: Syntax-check the script**

Run: `bash -n bin/run-tests.sh`
Expected: no output, exit 0 (valid bash).

- [ ] **Step 5: Verify the DEFAULT (unset `IT_PARALLELISM`) still runs the sequential loop, green**

Run: `bin/run-tests.sh --it`
Expected: four sequential `PASS  IT: wikantik-it-tests/wikantik-it-test-*  [Xm YYs]` lines (rest, sso, sso-saml, custom-jdbc), `RESULT: ALL PASSED`, and a final `TOTAL RUNTIME: …` line. This proves the opt-in branch left the default path byte-for-byte equivalent.

- [ ] **Step 6: Commit**

```bash
git add bin/run-tests.sh
git commit -m "test(it): add opt-in IT_PARALLELISM mode to run-tests.sh (default 1 = unchanged)"
```

---

## Task 4: Parallel verification campaign + record the gain

**Files:** none (verification only; a possible follow-up commit if a collision is found).

- [ ] **Step 1: Run the full parallel IT phase three times**

Run, one at a time (the runner's `flock` enforces no overlap):
```bash
IT_PARALLELISM=4 bin/run-tests.sh --it
IT_PARALLELISM=4 bin/run-tests.sh --it
IT_PARALLELISM=4 bin/run-tests.sh --it
```
For each run, confirm from the output / `.test-suite-logs/report.txt`:
- `PASS  IT (parallel x4)  [Xm YYs]   Tests run: …, Failures: 0, Errors: 0`
- `RESULT: ALL PASSED`
- the `it-parallel.log` contains **no** `container name already in use`, `port is already allocated`, `Address already in use`, `cargo.rmi.port … is in use`, or `pg-cleanup` errors.
All three runs MUST be green.

- [ ] **Step 2: If any run hit a port/container collision — reserve the offending port and re-verify**

If `it-parallel.log` shows a collision on a port not yet reserved (e.g. an AJP `8009` or another Cargo connector), add that port to the appropriate `reserve-network-ports` `<portNames>` list and wire the matching property (for an AJP collision: add `<cargo.tomcat.ajp.port>${it.wikantik.ajp.port}</cargo.tomcat.ajp.port>` to the cargo `<properties>` block, an `it.wikantik.ajp.port` fallback property, and `it.wikantik.ajp.port` to the `reserve-it-ports` portNames). Re-run Step 1 until three consecutive clean greens. Commit the fix:
```bash
git add wikantik-it-tests/pom.xml
git commit -m "test(it): reserve <offending> port dynamically to close a parallel-run collision"
```
(If Step 1 was clean on the first three runs, skip this step.)

- [ ] **Step 3: Record the parallel-vs-sequential IT-phase wall-clock**

Compare the `[Xm YYs]` on the `IT (parallel x4)` line against the sum of the four sequential `IT:` lines from Task 3 Step 5. Note the realized saving (expected: the IT phase drops toward the `custom-jdbc` browser floor — roughly 1–2 minutes saved). Capture it in the CHANGELOG note (Step 4).

- [ ] **Step 4: Add a CHANGELOG (Unreleased) note and commit**

Add one line under the Unreleased section of `CHANGELOG.md`:

```
- test: opt-in parallel IT execution (`IT_PARALLELISM=N bin/run-tests.sh`) — IT modules now reserve per-module free ports + a uniquely-named pgvector container, so a single `-T N` reactor runs them concurrently (default behavior unchanged, sequential).
```

```bash
git add CHANGELOG.md
git commit -m "docs(changelog): note opt-in parallel IT execution"
```

---

## Self-review

- **Spec coverage:**
  - §1 Port isolation (dynamic, per module) → Task 1 Step 4 (db/servlet/rmi via `reserve-network-ports` at `process-test-resources`) + Task 2 (mock-oauth/saml-idp). The spec also named the Cargo RMI port; Task 1 Steps 1+3 introduce `it.wikantik.rmi.port` and wire `cargo.rmi.port` (the spec assumed it existed — the plan correctly creates it, since Cargo otherwise defaults to the colliding 8205).
  - §2 Container isolation (unique name + reworked cleanup) → Task 1 Steps 1, 2, 5.
  - §3 Runner opt-in `IT_PARALLELISM` (default 1 sequential; >1 single `-T N` reactor; `--module` unaffected) → Task 3.
  - §4 Verification (≥3 parallel runs, default still sequential-green, record wall-clock) → Task 4 Steps 1+3, and Task 3 Step 5 for the default-still-green check.
  - Non-goals (no default change, no fixed lanes, no within-module parallelism beyond the shipped browser cluster, single reactor not concurrent `mvn`) are respected — default path is byte-for-byte unchanged and only one Maven process ever runs.
- **Placeholder scan:** every code/XML step contains the literal element/snippet to add; the one conditional (AJP/other-port) in Task 4 Step 2 is a contingency with the exact remedy spelled out, not a TODO. No "TBD"/"handle edge cases".
- **Consistency:** `it.db.container-alias = wikantik-pg-${project.artifactId}` is used identically by the docker `<alias>`, `containerNamePattern>%a`, and the reworked `pg-cleanup-stale` (`docker rm -f "${it.db.container-alias}"`). `it.wikantik.rmi.port` is defined (Step 1), wired to `cargo.rmi.port` (Step 3), and reserved (Step 4) with the same name throughout. `IT_PARALLELISM` default `1`, `IT_MODULES` joined with `,` into `-pl`, and the `run_step` label `IT (parallel xN)` are consistent between Task 3 and Task 4's grep targets.
- **Verification reality:** each task ends with a runnable command and a concrete green criterion; `bin/run-tests.sh --module …` / `--it` are the existing, correct entry points; the `docker ps` name check catches a wrong `containerNamePattern` placement early.
