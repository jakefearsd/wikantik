# Containerised Embeddings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give local development and the IT suite a real CPU embedding provider, and add the first end-to-end coverage of the dense/bundle retrieval path.

**Architecture:** One `ollama/ollama` service definition in `docker/docker-compose.embeddings.yml`, run as two independent instances — port 11434 for local dev (started by `bin/deploy-local.sh`), port 11435 for the test suite (started once per run by `bin/run-tests.sh`, around the IT phase). A new `wikantik-it-test-dense` IT module is the only module with embeddings enabled, and carries assertions that a BM25 fallback cannot satisfy.

**Tech Stack:** Docker Compose, Ollama (`qwen3-embedding:0.6b`), Bash, Maven (failsafe + cargo-maven3 + docker-maven-plugin + build-helper), JUnit 5, Gson.

## Global Constraints

- Model tag is `qwen3-embedding:0.6b`; the internal model code is `qwen3-embedding-0.6b`; **1024 dimensions**. The dimension must match the index — do not substitute another model.
- **Do not modify `wikantik-main/src/main/resources/ini/wikantik.properties`.** Its `wikantik.search.embedding.base-url` deliberately still points at `inference.jakefear.com`. That is a recorded non-goal.
- Local dev port **11434**; test-suite port **11435**. Never share one instance between the two.
- Never use `git add -A`. Stage files by name.
- Test commands: `bin/run-tests.sh --module dense` for the new module; `bash bin/tests/<name>.sh` for shell tests.
- The IT suite's Cargo Tomcat runs on the **host**, not in a container, so it reaches the embedder at `http://localhost:<port>` directly — no `host.docker.internal` needed.

---

### Task 1: Compose definition for the embeddings container

**Files:**
- Create: `docker/docker-compose.embeddings.yml`
- Test: `bin/tests/test-embeddings.sh`

**Interfaces:**
- Produces: a compose project exposing Ollama on `${WIKANTIK_EMBEDDING_PORT}` with project name `${COMPOSE_PROJECT_NAME}`. Consumed by Tasks 2 and 3.

- [ ] **Step 1: Write the failing test**

Create `bin/tests/test-embeddings.sh`:

```bash
#!/usr/bin/env bash
# Tests for docker/docker-compose.embeddings.yml. Pure YAML/config assertions —
# does not start a container, so it runs anywhere.
set -euo pipefail
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE="${REPO_DIR}/docker/docker-compose.embeddings.yml"
fails=0
check() { # check <description> <pattern>
  if grep -q -- "$2" "$COMPOSE"; then echo "  ok: $1"; else echo "  FAIL: $1 (no match for: $2)"; fails=$((fails+1)); fi
}
echo "test-embeddings: $COMPOSE"
[ -f "$COMPOSE" ] || { echo "  FAIL: compose file missing"; exit 1; }
check "uses the ollama image"            "image: ollama/ollama"
check "port is parameterised"            'WIKANTIK_EMBEDDING_PORT'
check "model tag is parameterised"       'WIKANTIK_EMBEDDING_MODEL_TAG'
check "defaults to qwen3-embedding:0.6b" 'qwen3-embedding:0.6b'
check "caches models in a named volume"  'ollama-models'
check "has a healthcheck"                'healthcheck:'
# The pull must be idempotent-on-restart, not a one-shot init container.
check "pulls the model on start"         'ollama pull'
if [ "$fails" -ne 0 ]; then echo "test-embeddings: ${fails} failure(s)"; exit 1; fi
echo "test-embeddings: all passed"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bash bin/tests/test-embeddings.sh`
Expected: FAIL with "compose file missing"

- [ ] **Step 3: Write the compose file**

Create `docker/docker-compose.embeddings.yml`:

```yaml
# CPU-only Ollama used as an embedding provider for local development and the
# IT suite. Mirrors the ollama-embed sidecar in docker-compose.cloud.yml rather
# than introducing a second way to run Ollama.
#
# Two independent instances are expected, distinguished by project name + port:
#   local dev   COMPOSE_PROJECT_NAME=wikantik-embed-dev  PORT=11434
#   test suite  COMPOSE_PROJECT_NAME=wikantik-embed-test PORT=11435
# They must never be shared: a test run borrowing the developer's container
# makes results depend on ambient machine state.
services:
  ollama-embed:
    image: ollama/ollama:latest
    restart: unless-stopped
    ports:
      - "${WIKANTIK_EMBEDDING_PORT:-11434}:11434"
    environment:
      # Keep the 0.6B model resident (~1 GiB) so queries never pay a reload.
      OLLAMA_KEEP_ALIVE: "-1"
    volumes:
      - ollama-models:/root/.ollama
    # `ollama pull` checks the local blob store before downloading, so running it
    # on every start is cheap and idempotent once the model is cached in the
    # named volume. That is what lets this survive restarts without a separate
    # one-shot init container.
    entrypoint: ["/bin/sh", "-c"]
    command:
      - |
        ollama serve &
        SERVE_PID=$$!
        until ollama list >/dev/null 2>&1; do sleep 1; done
        ollama pull "${WIKANTIK_EMBEDDING_MODEL_TAG:-qwen3-embedding:0.6b}"
        wait "$$SERVE_PID"
    healthcheck:
      test: ["CMD-SHELL", "ollama list >/dev/null 2>&1"]
      interval: 10s
      timeout: 10s
      start_period: 30s
      retries: 10

volumes:
  ollama-models:
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bash bin/tests/test-embeddings.sh`
Expected: all checks ok

- [ ] **Step 5: Verify it actually starts and embeds**

```bash
COMPOSE_PROJECT_NAME=wikantik-embed-test WIKANTIK_EMBEDDING_PORT=11435 \
  docker compose -f docker/docker-compose.embeddings.yml up -d
# First run downloads ~600 MB; subsequent runs are cached in the volume.
until curl -sf http://localhost:11435/api/tags >/dev/null; do sleep 2; done
curl -s http://localhost:11435/api/embed \
  -d '{"model":"qwen3-embedding:0.6b","input":"hello"}' | head -c 200
```

Expected: JSON containing an `embeddings` array. Confirm its length is 1024:

```bash
curl -s http://localhost:11435/api/embed -d '{"model":"qwen3-embedding:0.6b","input":"hello"}' \
  | python3 -c 'import sys,json; print(len(json.load(sys.stdin)["embeddings"][0]))'
```

Expected: `1024`. If it prints anything else, stop — the model does not match the index dimension.

- [ ] **Step 6: Commit**

```bash
git add docker/docker-compose.embeddings.yml bin/tests/test-embeddings.sh
git commit -m "feat: CPU ollama compose service for local dev and IT embeddings"
```

---

### Task 2: Local dev wiring in deploy-local.sh

**Files:**
- Modify: `.env.example`
- Modify: `wikantik-war/src/main/config/tomcat/wikantik-custom-postgresql.properties.template`
- Modify: `bin/deploy-local.sh`
- Test: `bin/tests/test-embeddings.sh` (extend)

**Interfaces:**
- Consumes: the compose file from Task 1.
- Produces: `wikantik.search.embedding.base-url` in the deployed `tomcat/tomcat-11/lib/wikantik-custom.properties`.

- [ ] **Step 1: Write the failing test**

Append to `bin/tests/test-embeddings.sh`, before the final `if [ "$fails" -ne 0 ]` block:

```bash
TEMPLATE="${REPO_DIR}/wikantik-war/src/main/config/tomcat/wikantik-custom-postgresql.properties.template"
DEPLOY="${REPO_DIR}/bin/deploy-local.sh"
ENVEX="${REPO_DIR}/.env.example"
checkf() { # checkf <description> <file> <pattern>
  if grep -q -- "$3" "$2"; then echo "  ok: $1"; else echo "  FAIL: $1 (no match for: $3)"; fails=$((fails+1)); fi
}
checkf "template carries the base-url placeholder" "$TEMPLATE" '@@EMBEDDING_BASE_URL@@'
checkf "deploy-local substitutes it"               "$DEPLOY"   '@@EMBEDDING_BASE_URL@@'
checkf "deploy-local can be opted out"             "$DEPLOY"   'WIKANTIK_LOCAL_EMBEDDINGS'
# deploy-local.sh does NOT overwrite an existing properties file, so an existing
# install would otherwise never gain the new setting.
checkf "deploy-local repairs an existing props file" "$DEPLOY" 'embedding.base-url'
checkf ".env.example documents the base url"       "$ENVEX"    'WIKANTIK_EMBEDDING_BASE_URL'
checkf ".env.example documents the opt-out"        "$ENVEX"    'WIKANTIK_LOCAL_EMBEDDINGS'
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bash bin/tests/test-embeddings.sh`
Expected: FAIL on the six new checks.

- [ ] **Step 3: Add the placeholder to the properties template**

Append to `wikantik-war/src/main/config/tomcat/wikantik-custom-postgresql.properties.template`:

```properties
# Dense-retrieval embedding provider. Rendered from .env by bin/deploy-local.sh.
# The ini-bundle default points at a host that no longer exists, so local dev
# must override it; bin/deploy-local.sh runs a CPU ollama container on 11434.
wikantik.search.embedding.base-url = @@EMBEDDING_BASE_URL@@
```

- [ ] **Step 4: Document the variables in .env.example**

Append to `.env.example`:

```bash
# --- Local development embeddings -------------------------------------------
# bin/deploy-local.sh starts a CPU ollama container (docker/docker-compose.
# embeddings.yml) and points the wiki at it. Set to false to skip the container
# entirely and leave the base URL untouched (BM25-only local work).
#WIKANTIK_LOCAL_EMBEDDINGS=true
# Where the local wiki looks for embeddings. Must match the container's port.
#WIKANTIK_EMBEDDING_BASE_URL=http://localhost:11434
# Model pulled on container start. 1024 dimensions — must match the index.
#WIKANTIK_EMBEDDING_MODEL_TAG=qwen3-embedding:0.6b
```

- [ ] **Step 5: Wire deploy-local.sh**

In `bin/deploy-local.sh`, after the `.env` sourcing block (the one ending with `print_status "Sourced ${ENV_FILE}"`, around line 310), insert:

```bash
# --- Local embeddings container ---------------------------------------------
: "${WIKANTIK_LOCAL_EMBEDDINGS:=true}"
: "${WIKANTIK_EMBEDDING_BASE_URL:=http://localhost:11434}"
EMBEDDING_PORT="${WIKANTIK_EMBEDDING_BASE_URL##*:}"
if [[ "${WIKANTIK_LOCAL_EMBEDDINGS}" == "true" ]]; then
    if ! curl -sf --max-time 3 "${WIKANTIK_EMBEDDING_BASE_URL}/api/tags" >/dev/null 2>&1; then
        print_status "Starting local embeddings container on port ${EMBEDDING_PORT}"
        COMPOSE_PROJECT_NAME=wikantik-embed-dev \
        WIKANTIK_EMBEDDING_PORT="${EMBEDDING_PORT}" \
            docker compose -f "${PROJECT_ROOT}/docker/docker-compose.embeddings.yml" up -d
        # First run pulls ~600 MB; that is a download, not a hang.
        print_status "Waiting for the embedder (first run downloads the model)"
        for _ in $(seq 1 180); do
            curl -sf --max-time 3 "${WIKANTIK_EMBEDDING_BASE_URL}/api/tags" >/dev/null 2>&1 && break
            sleep 2
        done
    fi
    if curl -sf --max-time 3 "${WIKANTIK_EMBEDDING_BASE_URL}/api/tags" >/dev/null 2>&1; then
        print_status "Embeddings available at ${WIKANTIK_EMBEDDING_BASE_URL}"
    else
        print_warning "Embedder did not come up; the wiki will fall back to BM25."
    fi
else
    print_status "WIKANTIK_LOCAL_EMBEDDINGS=false — skipping the embeddings container"
fi
```

- [ ] **Step 6: Substitute the placeholder, including on existing installs**

In `bin/deploy-local.sh`, replace the properties-copy block (currently at lines 351–358) with:

```bash
if [[ ! -f "${PROPS_DEST}" ]]; then
    sed -e "s|@@REPO_ROOT@@|${PROJECT_ROOT}|g" \
        -e "s|@@EMBEDDING_BASE_URL@@|${WIKANTIK_EMBEDDING_BASE_URL}|g" \
        "${CONFIG_DIR}/wikantik-custom-postgresql.properties.template" \
        > "${PROPS_DEST}"
    print_status "Created ${PROPS_DEST} (paths substituted for ${SCRIPT_DIR})"
else
    print_status "Properties file already exists (not overwritten)"
    # An existing install predates the embedding setting and is never rewritten,
    # so it would silently keep using the dead ini default. Append it once.
    if ! grep -q "^wikantik.search.embedding.base-url" "${PROPS_DEST}"; then
        printf '\n# Added by deploy-local.sh — local embeddings container.\nwikantik.search.embedding.base-url = %s\n' \
            "${WIKANTIK_EMBEDDING_BASE_URL}" >> "${PROPS_DEST}"
        print_status "Added embedding base-url to the existing ${PROPS_DEST}"
    fi
fi
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `bash bin/tests/test-embeddings.sh`
Expected: all checks ok.

- [ ] **Step 8: Verify against a real deploy**

```bash
bin/deploy-local.sh
grep embedding tomcat/tomcat-11/lib/wikantik-custom.properties
```

Expected: `wikantik.search.embedding.base-url = http://localhost:11434`

- [ ] **Step 9: Commit**

```bash
git add .env.example bin/deploy-local.sh bin/tests/test-embeddings.sh \
        wikantik-war/src/main/config/tomcat/wikantik-custom-postgresql.properties.template
git commit -m "feat: deploy-local.sh runs a local embeddings container and wires the base URL"
```

---

### Task 3: Shared embedder for the test suite

**Files:**
- Modify: `bin/run-tests.sh`
- Test: `bin/tests/test-embeddings.sh` (extend)

**Interfaces:**
- Consumes: the compose file from Task 1.
- Produces: an embedder reachable at `http://localhost:11435` for the whole IT phase. Consumed by Task 4's module config.

- [ ] **Step 1: Write the failing test**

Append to `bin/tests/test-embeddings.sh`, before the final `if [ "$fails" -ne 0 ]` block:

```bash
RUNTESTS="${REPO_DIR}/bin/run-tests.sh"
checkf "run-tests starts the shared embedder"  "$RUNTESTS" 'wikantik-embed-test'
checkf "run-tests uses the test port 11435"    "$RUNTESTS" '11435'
checkf "run-tests tears the embedder down"     "$RUNTESTS" 'embed_stop'
checkf "dense module is in the default gate"   "$RUNTESTS" 'wikantik-it-test-dense'
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bash bin/tests/test-embeddings.sh`
Expected: FAIL on the four new checks.

- [ ] **Step 3: Add start/stop helpers to run-tests.sh**

In `bin/run-tests.sh`, after the `IT_MODULES=( ... )` array (around line 120), insert:

```bash
# Shared CPU embedder for the IT phase. One instance serves every module for the
# whole run, so the model loads once regardless of --parallel N. This manages its
# own container rather than reusing whatever happens to be listening: depending on
# ambient machine state is what made PreviewClickHoldsStillIT hang for 120s.
EMBED_PORT=11435
EMBED_URL="http://localhost:${EMBED_PORT}"
EMBED_PROJECT="wikantik-embed-test"
EMBED_COMPOSE="${REPO_DIR}/docker/docker-compose.embeddings.yml"

embed_start() {
  echo ">>> Starting shared embedder on ${EMBED_URL}"
  COMPOSE_PROJECT_NAME="${EMBED_PROJECT}" WIKANTIK_EMBEDDING_PORT="${EMBED_PORT}" \
    docker compose -f "${EMBED_COMPOSE}" up -d >/dev/null 2>&1 || return 1
  for _ in $(seq 1 180); do
    curl -sf --max-time 3 "${EMBED_URL}/api/tags" >/dev/null 2>&1 && { echo "    embedder ready"; return 0; }
    sleep 2
  done
  echo "    WARNING: embedder not ready after 360s"
  return 1
}

embed_stop() {
  COMPOSE_PROJECT_NAME="${EMBED_PROJECT}" WIKANTIK_EMBEDDING_PORT="${EMBED_PORT}" \
    docker compose -f "${EMBED_COMPOSE}" down >/dev/null 2>&1 || true
}
```

- [ ] **Step 4: Call them around the IT phase**

In `bin/run-tests.sh`, immediately before `if [ "$RUN_IT" = 1 ]; then` (around line 265), insert:

```bash
if [ "$RUN_IT" = 1 ] || [ -n "$ONE_MODULE" ]; then
  embed_start || true   # the dense module fails loudly on its own if this did not work
  trap embed_stop EXIT
fi
```

- [ ] **Step 5: Register the new module**

In `bin/run-tests.sh`, add to the `IT_MODULES` array, after the `custom-jdbc` entry:

```bash
  "wikantik-it-tests/wikantik-it-test-dense"
```

Then update the four help/usage strings that enumerate module names — lines containing `rest|sso|knowledge-disabled|custom-jdbc` — to `rest|sso|knowledge-disabled|custom-jdbc|dense`, and add to the `--list` output block:

```bash
  echo "  dense               wikantik-it-tests/wikantik-it-test-dense              (dense retrieval + context bundle)"
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `bash bin/tests/test-embeddings.sh`
Expected: all checks ok.

- [ ] **Step 7: Commit**

```bash
git add bin/run-tests.sh bin/tests/test-embeddings.sh
git commit -m "feat: run-tests.sh manages one shared embedder for the IT phase"
```

---

### Task 4: The `wikantik-it-test-dense` module

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-dense/pom.xml`
- Create: `wikantik-it-tests/wikantik-it-test-dense/src/main/resources/wikantik-custom.properties`
- Create: `wikantik-it-tests/wikantik-it-test-dense/src/test/java/com/wikantik/its/dense/DenseBundleIT.java`
- Modify: `wikantik-it-tests/pom.xml:34-40` (module list)

**Interfaces:**
- Consumes: the embedder at `http://localhost:11435` from Task 3.
- Produces: nothing other tasks depend on. This is the terminal task.

- [ ] **Step 1: Create the module pom**

Copy the structure of `wikantik-it-tests/wikantik-it-test-knowledge-disabled/pom.xml` exactly, changing only the `artifactId`, `name`, and `description`:

```xml
  <artifactId>wikantik-it-test-dense</artifactId>
  <name>Wikantik IT :: dense retrieval</name>
  <description>Integration tests for dense retrieval and the RAG context bundle against a live CPU embedding provider — the only IT module with embeddings enabled</description>
```

Keep the same `<properties>`, the `<resources combine.self="override">` block (this module ships its own `wikantik-custom.properties`), all five plugins (`properties-maven-plugin`, `build-helper-maven-plugin`, `docker-maven-plugin`, `exec-maven-plugin`, `maven-failsafe-plugin`, `cargo-maven3-plugin`), and the `gson` + `junit-jupiter-api` + `junit-jupiter-engine` dependencies. **Omit** the `mcp` and `wikantik-selenide-tests` dependencies — this module has no browser or MCP tests.

- [ ] **Step 2: Register the module**

In `wikantik-it-tests/pom.xml`, add to `<modules>` after line 39:

```xml
    <module>wikantik-it-test-dense</module>
```

- [ ] **Step 3: Write the module's properties overlay**

Create `wikantik-it-tests/wikantik-it-test-dense/src/main/resources/wikantik-custom.properties` by copying `wikantik-it-tests/wikantik-selenide-tests/src/main/resources/wikantik-custom.properties` verbatim, then appending:

```properties
# --- Dense retrieval against the shared IT embedder --------------------------
# bin/run-tests.sh starts one CPU ollama container on 11435 for the whole run.
# The Cargo Tomcat runs on the host, so localhost reaches it directly.
# This is the ONLY IT module with embeddings enabled; the others deliberately
# inherit the ini default and fall back to BM25.
wikantik.search.embedding.base-url = http://localhost:11435
wikantik.search.embedding.model    = qwen3-embedding-0.6b
# lucene-hnsw matches the production default. Do not use `inmemory`: it needs a
# reload after a re-index before the dense-chunk bundle hydrates.
wikantik.search.dense.backend      = lucene-hnsw
wikantik.bundle.dense.enabled      = true
```

- [ ] **Step 4: Write the failing test**

Create `wikantik-it-tests/wikantik-it-test-dense/src/test/java/com/wikantik/its/dense/DenseBundleIT.java`:

```java
package com.wikantik.its.dense;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end coverage of dense retrieval and the RAG context bundle against a live
 * CPU embedding provider.
 *
 * <p>The load-bearing design point is the NON-VACUITY GUARD in
 * {@link #bundleFindsASectionNoLexicalSearchCouldFind()}. The query deliberately shares
 * no content word with the target page, so a BM25 fallback cannot satisfy it — the match
 * has to come from the embedding. Without that constraint this suite would pass with the
 * embedder switched off, rebuilding exactly the kind of vacuous green removed elsewhere
 * in this codebase.</p>
 */
class DenseBundleIT {

    private static final String BASE = System.getProperty( "it-wikantik.base.url" );
    private static final String EMBEDDER = "http://localhost:11435";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** The embedder is this module's whole reason to exist — fail loudly, do not skip. */
    @BeforeAll
    static void embedderMustBeReachable() throws Exception {
        final HttpResponse<String> r = HTTP.send(
            HttpRequest.newBuilder( URI.create( EMBEDDER + "/api/tags" ) ).GET().build(),
            HttpResponse.BodyHandlers.ofString() );
        assertEquals( 200, r.statusCode(),
            "No embedder at " + EMBEDDER + ". bin/run-tests.sh starts it; if you invoked "
                + "maven directly, run: COMPOSE_PROJECT_NAME=wikantik-embed-test "
                + "WIKANTIK_EMBEDDING_PORT=11435 docker compose -f "
                + "docker/docker-compose.embeddings.yml up -d" );
    }

    private static JsonObject bundle( final String query ) throws Exception {
        final HttpResponse<String> r = HTTP.send(
            HttpRequest.newBuilder( URI.create( BASE + "/api/bundle?q="
                + URLEncoder.encode( query, StandardCharsets.UTF_8 ) ) ).GET().build(),
            HttpResponse.BodyHandlers.ofString() );
        assertEquals( 200, r.statusCode(), "GET /api/bundle: " + r.body() );
        return JsonParser.parseString( r.body() ).getAsJsonObject();
    }

    /** Polls the bundle endpoint until the async embedding pipeline has indexed content. */
    private static JsonObject awaitBundle( final String query ) throws Exception {
        final long deadline = System.currentTimeMillis() + 120_000L;
        JsonObject last = null;
        while ( System.currentTimeMillis() < deadline ) {
            last = bundle( query );
            final var sections = last.getAsJsonArray( "sections" );
            if ( sections != null && sections.size() > 0 ) return last;
            Thread.sleep( 2_000L );
        }
        fail( "No bundle sections for '" + query + "' within 120s; last response: " + last );
        return null; // unreachable
    }

    @Test
    void bundleReturnsCitedSections() throws Exception {
        final JsonObject b = awaitBundle( "hybrid retrieval" );
        final var sections = b.getAsJsonArray( "sections" );
        assertTrue( sections.size() > 0, "expected at least one section" );

        // GET /api/bundle serialises ContextBundle directly with Gson — there is NO
        // "data" envelope. Shape: { query, sections[], coverage }, and each section is
        // BundleSection { canonicalId, slug, headingPath, text, score, citation }.
        final JsonObject first = sections.get( 0 ).getAsJsonObject();
        assertTrue( first.has( "slug" ), "a bundle section must name its source page: " + first );
        assertTrue( first.has( "citation" ), "a bundle section must carry a citation: " + first );
    }

    /**
     * NON-VACUITY GUARD. "machine learning vector similarity" shares no content word with
     * the seeded corpus page this is expected to match, so BM25 cannot rank it. If this
     * passes with the embedder stopped, the guard is broken and the suite is worthless.
     */
    @Test
    void bundleFindsASectionNoLexicalSearchCouldFind() throws Exception {
        final JsonObject b = awaitBundle( "machine learning vector similarity" );
        final var sections = b.getAsJsonArray( "sections" );
        assertTrue( sections.size() > 0,
            "A semantically-related query returned nothing — dense retrieval is not "
                + "contributing, so the bundle is running BM25-only: " + b );
    }
}
```

- [ ] **Step 5: Run test to verify it fails**

Run: `bin/run-tests.sh --module dense`
Expected: FAIL. The module does not build yet, or the assertions fail because the corpus/queries do not line up.

- [ ] **Step 6: Make it pass**

Adjust only the two query strings and the assertion on section shape to match what the deployed IT corpus actually contains. Inspect a real response first:

```bash
curl -s "http://localhost:<it-port>/wikantik-it-test-dense/api/bundle?q=hybrid+retrieval" | head -c 800
```

Pick a query for the non-vacuity test that is semantically close to a seeded page but shares **no** content word with it. Verify the guard by stopping the embedder and re-running — the test must fail:

```bash
COMPOSE_PROJECT_NAME=wikantik-embed-test docker compose -f docker/docker-compose.embeddings.yml stop
bin/run-tests.sh --module dense   # expected: FAIL
COMPOSE_PROJECT_NAME=wikantik-embed-test WIKANTIK_EMBEDDING_PORT=11435 \
  docker compose -f docker/docker-compose.embeddings.yml start
```

- [ ] **Step 7: Run the module green**

Run: `bin/run-tests.sh --module dense`
Expected: PASS.

- [ ] **Step 8: Run the full gate and record the cost**

Run: `bin/agent-build.sh start gate -- bin/run-tests.sh --parallel 4` then poll `bin/agent-build.sh status gate`.
Expected: ALL PASSED. Note the total runtime and compare against the ~4:30 baseline.

- [ ] **Step 9: Record the measured delta in the spec**

Edit `docs/superpowers/specs/2026-07-31-test-embeddings-container-design.md`, replacing "The actual delta should be measured once the module exists and recorded here." under Risks with the real before/after numbers.

- [ ] **Step 10: Commit**

```bash
git add wikantik-it-tests/pom.xml wikantik-it-tests/wikantik-it-test-dense \
        docs/superpowers/specs/2026-07-31-test-embeddings-container-design.md
git commit -m "test: end-to-end dense retrieval and context-bundle coverage"
```

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
|---|---|
| Container definition, model, volume, healthcheck | 1 |
| Ports 11434 / 11435 | 1 (parameterised), 2 (dev), 3 (test) |
| Local dev via deploy-local.sh | 2 |
| `WIKANTIK_LOCAL_EMBEDDINGS` opt-out | 2 |
| `.env` / `.env.example` wiring | 2 |
| Shared container per test run | 3 |
| New module joins the default gate | 3 (registration), 4 (module) |
| `lucene-hnsw`, embeddings only in this module | 4 |
| Bundle + citation assertions | 4 |
| Non-vacuity guard | 4, verified in Step 6 |
| Fails loudly when embedder absent | 4, `@BeforeAll` |
| Non-goal: ini default untouched | Global Constraints |
| Risk: measure the runtime delta | 4, Steps 8–9 |

**Placeholder scan:** No TBD/TODO. Step 6 of Task 4 is deliberately exploratory (query strings depend on the deployed corpus) but states exactly how to determine the values and how to verify the guard.

**Type consistency:** `embed_start` / `embed_stop` defined in Task 3 Step 3 and called in Step 4. `WIKANTIK_EMBEDDING_PORT`, `WIKANTIK_EMBEDDING_MODEL_TAG`, `COMPOSE_PROJECT_NAME` consistent across Tasks 1–3. `@@EMBEDDING_BASE_URL@@` matches between the template (Task 2 Step 3) and the substitution (Step 6). Port 11435 consistent between Task 3 and the module properties and `DenseBundleIT.EMBEDDER`.
