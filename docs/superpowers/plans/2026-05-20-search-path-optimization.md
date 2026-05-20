# Search-Path Optimization Implementation Plan (v1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (recommended — the empirical sweep + JFR analysis at the end depends on data produced earlier in the plan) or superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a JFR profiling toggle and land two surgical search-path optimisations (`fetchContributingChunks` short-circuit + reuse, `fetchRelatedPages` N+1 → batch), then re-sweep at the same Sweep #2 levels with a JFR capture during N=300 to produce data for the next round.

**Architecture:** A small `JfrProfilingService` + admin REST endpoint (matching the existing two-file `AgentGradeAudit*` pattern) so JFR recordings start/stop on demand from prod. Two surgical changes inside `DefaultContextRetrievalService` plus one new batch method on `MentionIndex`, gated by unit tests asserting result-shape preservation. Re-sweep with the existing harness; one JFR capture during the sustained N=300 phase; addendum to the existing characterization report.

**Tech Stack:** JDK 21 (`jdk.jfr.Recording`), Jakarta Servlet 6.1, Mockito + JUnit 5, k6 (already wired), jakemon Prometheus queries (already wired).

---

## Spec

Design spec: `docs/superpowers/specs/2026-05-20-search-path-optimization-design.md`

## File Structure

**New:**
- `wikantik-observability/src/main/java/com/wikantik/observability/JfrProfilingService.java` — JFR start/stop/list/download backend
- `wikantik-observability/src/main/java/com/wikantik/observability/JfrProfilingService.RecordingInfo` (nested record) — DTO
- `wikantik-observability/src/test/java/com/wikantik/observability/JfrProfilingServiceTest.java` — unit tests
- `wikantik-rest/src/main/java/com/wikantik/rest/admin/ProfilingResource.java` — admin REST logic (delegate)
- `wikantik-rest/src/main/java/com/wikantik/rest/AdminProfilingServlet.java` — servlet adapter (one servlet per HTTP method-set, matching `AdminAgentGradeAuditServlet`)
- `wikantik-rest/src/test/java/com/wikantik/rest/admin/ProfilingResourceTest.java` — Mockito unit tests
- `wikantik-it-tests/wikantik-it-test-rest/src/test/java/.../ProfilingResourceIT.java` — Cargo-launched IT (start 2 s recording, poll until FINISHED, download, assert magic bytes)
- `wikantik-main/src/main/java/com/wikantik/search/hybrid/RerankOutcome.java` — record carrying fused page names + chunk evidence
- `wikantik-main/src/test/java/com/wikantik/search/hybrid/HybridSearchServiceRerankOutcomeTest.java` — guard tests for the new path

**Modified:**
- `wikantik-main/src/main/java/com/wikantik/search/hybrid/HybridSearchService.java` — add `rerankWithChunks(...)`; deprecate `rerank(...)` to forward
- `wikantik-main/src/main/java/com/wikantik/search/hybrid/DenseRetriever.java` — return chunks alongside scored pages (new method, current preserved)
- `wikantik-main/src/main/java/com/wikantik/knowledge/MentionIndex.java` — add `findRelatedPagesBatch(List<String>, int) → Map<String, List<RelatedByMention>>`
- `wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java` — consume `RerankOutcome`; rewrite `fetchContributingChunks` for short-circuit + reuse; rewrite `fetchRelatedPages` callsite to batched lookup
- `wikantik-war/src/main/webapp/WEB-INF/web.xml` — `<servlet>` + `<servlet-mapping>` for `/admin/profiling/*`
- `docker-compose.prod.yml` — `wikantik-profiling:/var/wikantik/profiling` named volume mount on the wikantik service
- `docs/ScalingCharacterization.md` — append §9 addendum

**Outputs (gitignored under `loadtest/results/`):**
- `sweep3-{200,300,400}vu-{k6,curl,host}.{log,json}`
- `sweep3-300vu.jfr` (the diagnostic capture)
- `sweep3-300vu-jfr-top20.txt` (extracted by `jfr print`)

---

### Task 1: `JfrProfilingService` — start/stop/list backend

**Files:**
- Create: `wikantik-observability/src/main/java/com/wikantik/observability/JfrProfilingService.java`
- Create: `wikantik-observability/src/test/java/com/wikantik/observability/JfrProfilingServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
// wikantik-observability/src/test/java/com/wikantik/observability/JfrProfilingServiceTest.java
package com.wikantik.observability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JfrProfilingServiceTest {

    @Test
    void startCreatesRunningRecording( @TempDir Path tmp ) {
        final JfrProfilingService svc = new JfrProfilingService( tmp, 5L * 1024 * 1024 * 1024 );
        final JfrProfilingService.RecordingInfo info = svc.start( 2, "test-label" );
        assertNotNull( info.recordingId() );
        assertEquals( "RUNNING", info.status() );
        assertEquals( 2, info.durationSeconds() );
        assertEquals( "test-label", info.label() );
        assertTrue( info.filePath().toString().endsWith( ".jfr" ) );
        svc.stop( info.recordingId() );
    }

    @Test
    void concurrentStartIsRejected( @TempDir Path tmp ) {
        final JfrProfilingService svc = new JfrProfilingService( tmp, 5L * 1024 * 1024 * 1024 );
        svc.start( 5, "first" );
        final IllegalStateException ex = assertThrows( IllegalStateException.class,
            () -> svc.start( 5, "second" ) );
        assertTrue( ex.getMessage().contains( "already running" ) );
    }

    @Test
    void durationOutOfRangeRejected( @TempDir Path tmp ) {
        final JfrProfilingService svc = new JfrProfilingService( tmp, 5L * 1024 * 1024 * 1024 );
        assertThrows( IllegalArgumentException.class, () -> svc.start( 0, "x" ) );
        assertThrows( IllegalArgumentException.class, () -> svc.start( 601, "x" ) );
    }

    @Test
    void stopProducesFinishedFile( @TempDir Path tmp ) throws Exception {
        final JfrProfilingService svc = new JfrProfilingService( tmp, 5L * 1024 * 1024 * 1024 );
        final var info = svc.start( 2, "stop-test" );
        TimeUnit.MILLISECONDS.sleep( 200 );  // let JFR write at least one chunk
        final var stopped = svc.stop( info.recordingId() );
        assertEquals( "FINISHED", stopped.status() );
        assertTrue( stopped.sizeBytes() > 0, "recording should have non-zero bytes" );
        assertTrue( stopped.filePath().toFile().exists() );
    }

    @Test
    void listReturnsKnownRecordings( @TempDir Path tmp ) {
        final JfrProfilingService svc = new JfrProfilingService( tmp, 5L * 1024 * 1024 * 1024 );
        final var a = svc.start( 2, "a" );
        svc.stop( a.recordingId() );
        final var b = svc.start( 2, "b" );
        svc.stop( b.recordingId() );
        final List<JfrProfilingService.RecordingInfo> all = svc.list();
        assertEquals( 2, all.size() );
    }

    @Test
    void unknownRecordingIdRejectedOnStop( @TempDir Path tmp ) {
        final JfrProfilingService svc = new JfrProfilingService( tmp, 5L * 1024 * 1024 * 1024 );
        assertThrows( IllegalArgumentException.class, () -> svc.stop( "no-such-id" ) );
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl wikantik-observability -am test -Dtest=JfrProfilingServiceTest -q`
Expected: COMPILE failure ("cannot find symbol JfrProfilingService").

- [ ] **Step 3: Write the implementation**

```java
// wikantik-observability/src/main/java/com/wikantik/observability/JfrProfilingService.java
/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.observability;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Start/stop/list/download of Java Flight Recorder recordings, gated by the
 * admin REST endpoint. Default-off — no recording active at boot; nothing
 * spins up until {@link #start} is hit. One recording at a time.
 */
public final class JfrProfilingService {

    private static final Logger LOG = LogManager.getLogger( JfrProfilingService.class );

    /** Minimum duration accepted by {@link #start}. */
    public static final int MIN_DURATION_SECONDS = 1;
    /** Maximum duration accepted by {@link #start}. Half a 10-minute sweep step is the safety ceiling. */
    public static final int MAX_DURATION_SECONDS = 600;

    private final Path directory;
    private final long maxDirectoryBytes;
    private final ConcurrentHashMap<String, Tracked> recordings = new ConcurrentHashMap<>();
    /** Single-recording guard. */
    private volatile String runningId;

    public JfrProfilingService( final Path directory, final long maxDirectoryBytes ) {
        if ( directory == null ) throw new IllegalArgumentException( "directory must not be null" );
        if ( maxDirectoryBytes <= 0 ) throw new IllegalArgumentException( "maxDirectoryBytes must be positive" );
        this.directory = directory;
        this.maxDirectoryBytes = maxDirectoryBytes;
    }

    public synchronized RecordingInfo start( final int durationSeconds, final String label ) {
        if ( durationSeconds < MIN_DURATION_SECONDS || durationSeconds > MAX_DURATION_SECONDS ) {
            throw new IllegalArgumentException(
                "duration_s must be in [" + MIN_DURATION_SECONDS + ".." + MAX_DURATION_SECONDS + "]" );
        }
        if ( runningId != null ) {
            throw new IllegalStateException(
                "A recording is already running (recording_id=" + runningId + ")" );
        }
        ensureDirectory();
        enforceSizeCap();

        final String safeLabel = label == null ? "" : label.replaceAll( "[^a-zA-Z0-9._-]", "_" );
        final String id = UUID.randomUUID().toString();
        final String file = "wikantik-" + Instant.now().toString().replace( ':', '-' )
            + ( safeLabel.isEmpty() ? "" : "-" + safeLabel ) + ".jfr";
        final Path filePath = directory.resolve( file );

        final Recording rec;
        try {
            rec = new Recording( Configuration.getConfiguration( "default" ) );
        } catch( final Exception e ) {
            throw new IllegalStateException( "JFR default config unavailable: " + e.getMessage(), e );
        }
        rec.setName( "wikantik-" + id );
        rec.setDuration( Duration.ofSeconds( durationSeconds ) );
        rec.setMaxAge( Duration.ofHours( 1 ) );
        try {
            rec.setDestination( filePath );
        } catch( final IOException e ) {
            rec.close();
            throw new IllegalStateException( "Cannot set JFR destination " + filePath + ": " + e.getMessage(), e );
        }
        rec.start();
        runningId = id;
        final RecordingInfo info = new RecordingInfo(
            id, Instant.now(), durationSeconds, safeLabel, filePath, 0L, "RUNNING" );
        recordings.put( id, new Tracked( rec, info ) );
        LOG.info( "JFR start: id={} duration={}s label={} file={}", id, durationSeconds, safeLabel, filePath );
        return info;
    }

    public synchronized RecordingInfo stop( final String recordingId ) {
        if ( recordingId == null ) throw new IllegalArgumentException( "recording_id must not be null" );
        final Tracked t = recordings.get( recordingId );
        if ( t == null ) throw new IllegalArgumentException( "Unknown recording_id: " + recordingId );
        try {
            t.recording.stop();
        } catch( final IllegalStateException ignore ) {
            // already stopped by duration timer — fine
        }
        t.recording.close();
        if ( recordingId.equals( runningId ) ) {
            runningId = null;
        }
        final long size = t.info.filePath().toFile().length();
        final RecordingInfo finished = new RecordingInfo(
            t.info.recordingId(), t.info.startTime(), t.info.durationSeconds(),
            t.info.label(), t.info.filePath(), size, "FINISHED" );
        recordings.put( recordingId, new Tracked( t.recording, finished ) );
        LOG.info( "JFR stop: id={} size={}B", recordingId, size );
        return finished;
    }

    public List<RecordingInfo> list() {
        final List<RecordingInfo> out = new ArrayList<>( recordings.size() );
        for ( final Tracked t : recordings.values() ) out.add( t.info );
        return List.copyOf( out );
    }

    public RecordingInfo get( final String recordingId ) {
        final Tracked t = recordings.get( recordingId );
        return t == null ? null : t.info;
    }

    private void ensureDirectory() {
        try {
            Files.createDirectories( directory );
        } catch( final IOException e ) {
            throw new IllegalStateException( "Cannot create profiling dir " + directory + ": " + e.getMessage(), e );
        }
    }

    private void enforceSizeCap() {
        try ( var stream = Files.list( directory ) ) {
            final long total = stream
                .filter( p -> p.toString().endsWith( ".jfr" ) )
                .mapToLong( p -> p.toFile().length() )
                .sum();
            if ( total > maxDirectoryBytes ) {
                throw new IllegalStateException(
                    "Profiling directory exceeds size cap (" + total + " > " + maxDirectoryBytes
                    + " bytes). Delete old recordings before starting a new one." );
            }
        } catch( final IOException e ) {
            throw new IllegalStateException( "Cannot enforce profiling dir size cap: " + e.getMessage(), e );
        }
    }

    public record RecordingInfo(
        String recordingId,
        Instant startTime,
        int durationSeconds,
        String label,
        Path filePath,
        long sizeBytes,
        String status   // "RUNNING" | "FINISHED" | "FAILED"
    ) {}

    private record Tracked( Recording recording, RecordingInfo info ) {}
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl wikantik-observability -am test -Dtest=JfrProfilingServiceTest -q`
Expected: all 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add wikantik-observability/src/main/java/com/wikantik/observability/JfrProfilingService.java \
        wikantik-observability/src/test/java/com/wikantik/observability/JfrProfilingServiceTest.java
git commit -m "feat(observability): JfrProfilingService — start/stop/list JFR recordings

Backend for the admin profiling endpoint. One concurrent recording at
a time. Duration capped at 600s. Directory size cap (5GB by default)
gates new starts. Uses JFR's default configuration (~1% overhead).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 2: `ProfilingResource` + `AdminProfilingServlet` REST surface

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/admin/ProfilingResource.java`
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/AdminProfilingServlet.java`
- Create: `wikantik-rest/src/test/java/com/wikantik/rest/admin/ProfilingResourceTest.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml` — add servlet + mapping

- [ ] **Step 1: Write the failing test**

```java
// wikantik-rest/src/test/java/com/wikantik/rest/admin/ProfilingResourceTest.java
package com.wikantik.rest.admin;

import com.wikantik.observability.JfrProfilingService;
import com.wikantik.observability.JfrProfilingService.RecordingInfo;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfilingResourceTest {

    @Test
    void startReturnsJsonRecordingInfo() {
        final JfrProfilingService svc = mock( JfrProfilingService.class );
        when( svc.start( 60, "stress" ) ).thenReturn(
            new RecordingInfo( "abc", Instant.parse( "2026-05-20T10:00:00Z" ), 60, "stress",
                               Path.of( "/var/wikantik/profiling/x.jfr" ), 0L, "RUNNING" ) );
        final ProfilingResource r = new ProfilingResource( svc );
        final String json = r.start( 60, "stress" );
        assertTrue( json.contains( "\"recording_id\":\"abc\"" ) );
        assertTrue( json.contains( "\"status\":\"RUNNING\"" ) );
        assertTrue( json.contains( "\"duration_s\":60" ) );
    }

    @Test
    void startInvalidDurationReturns400Body() {
        final JfrProfilingService svc = mock( JfrProfilingService.class );
        when( svc.start( 0, "x" ) ).thenThrow( new IllegalArgumentException( "duration_s must be in [1..600]" ) );
        final ProfilingResource r = new ProfilingResource( svc );
        final ProfilingResource.RestError err = assertThrows( ProfilingResource.RestError.class,
            () -> r.start( 0, "x" ) );
        assertEquals( 400, err.status() );
        assertTrue( err.getMessage().contains( "duration_s" ) );
    }

    @Test
    void startWhileRunningReturns409() {
        final JfrProfilingService svc = mock( JfrProfilingService.class );
        when( svc.start( 60, "x" ) ).thenThrow( new IllegalStateException( "A recording is already running (recording_id=running-id)" ) );
        final ProfilingResource r = new ProfilingResource( svc );
        final ProfilingResource.RestError err = assertThrows( ProfilingResource.RestError.class,
            () -> r.start( 60, "x" ) );
        assertEquals( 409, err.status() );
    }

    @Test
    void stopUnknownReturns404() {
        final JfrProfilingService svc = mock( JfrProfilingService.class );
        when( svc.stop( "no-such" ) ).thenThrow( new IllegalArgumentException( "Unknown recording_id: no-such" ) );
        final ProfilingResource r = new ProfilingResource( svc );
        final ProfilingResource.RestError err = assertThrows( ProfilingResource.RestError.class,
            () -> r.stop( "no-such" ) );
        assertEquals( 404, err.status() );
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl wikantik-rest -am test -Dtest=ProfilingResourceTest -q`
Expected: COMPILE failure ("cannot find symbol ProfilingResource").

- [ ] **Step 3: Write the resource (logic) and the servlet (adapter)**

```java
// wikantik-rest/src/main/java/com/wikantik/rest/admin/ProfilingResource.java
/* ASF header omitted for brevity in plan — copy from AgentGradeAuditResource */
package com.wikantik.rest.admin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.wikantik.observability.JfrProfilingService;
import com.wikantik.observability.JfrProfilingService.RecordingInfo;

import java.nio.file.Path;
import java.util.List;

/**
 * Backing logic for {@code /admin/profiling/jfr/*}. Behind {@code AdminAuthFilter}
 * — no extra auth check here. Maps {@link JfrProfilingService} exceptions to
 * HTTP-status-carrying {@link RestError}s so the servlet adapter can emit the
 * right code.
 */
public final class ProfilingResource {

    private static final Gson GSON = new Gson();

    private final JfrProfilingService svc;

    public ProfilingResource( final JfrProfilingService svc ) {
        if ( svc == null ) throw new IllegalArgumentException( "svc must not be null" );
        this.svc = svc;
    }

    public String start( final int durationSeconds, final String label ) {
        try {
            return toJson( svc.start( durationSeconds, label ) );
        } catch ( final IllegalArgumentException e ) {
            throw new RestError( 400, e.getMessage() );
        } catch ( final IllegalStateException e ) {
            throw new RestError( 409, e.getMessage() );
        }
    }

    public String stop( final String recordingId ) {
        try {
            return toJson( svc.stop( recordingId ) );
        } catch ( final IllegalArgumentException e ) {
            throw new RestError( 404, e.getMessage() );
        }
    }

    public String list() {
        final List<RecordingInfo> all = svc.list();
        final com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for ( final RecordingInfo r : all ) arr.add( GSON.toJsonTree( toJsonObject( r ) ) );
        return arr.toString();
    }

    /** Path to the underlying file for streaming — used by the servlet's download handler. */
    public Path filePathFor( final String recordingId ) {
        final RecordingInfo r = svc.get( recordingId );
        if ( r == null ) throw new RestError( 404, "Unknown recording_id: " + recordingId );
        return r.filePath();
    }

    private String toJson( final RecordingInfo r ) {
        return GSON.toJson( toJsonObject( r ) );
    }

    private JsonObject toJsonObject( final RecordingInfo r ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "recording_id", r.recordingId() );
        o.addProperty( "start_time", r.startTime().toString() );
        o.addProperty( "duration_s", r.durationSeconds() );
        o.addProperty( "label", r.label() );
        o.addProperty( "file_path", r.filePath().toString() );
        o.addProperty( "size_bytes", r.sizeBytes() );
        o.addProperty( "status", r.status() );
        return o;
    }

    /** Carries an HTTP status to the servlet adapter. */
    public static final class RestError extends RuntimeException {
        private final int status;
        public RestError( final int status, final String message ) {
            super( message );
            this.status = status;
        }
        public int status() { return status; }
    }
}
```

```java
// wikantik-rest/src/main/java/com/wikantik/rest/AdminProfilingServlet.java
/* ASF header omitted — copy from AdminAgentGradeAuditServlet */
package com.wikantik.rest;

import com.wikantik.rest.admin.ProfilingResource;
import com.wikantik.observability.JfrProfilingService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Servlet mounting {@link ProfilingResource} at {@code /admin/profiling/jfr/*}.
 * Behind {@code AdminAuthFilter} (AllPermission).
 *
 * <p>Routes by suffix (last URI segment): {@code start}, {@code stop},
 * {@code recordings}, and {@code recordings/{id}}.</p>
 */
public class AdminProfilingServlet extends RestServletBase {

    private static final Logger LOG = LogManager.getLogger( AdminProfilingServlet.class );

    /** Lazily-resolved — engine subsystems not necessarily ready at servlet init. */
    private volatile ProfilingResource delegate;

    @Override
    protected void doPost( final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException, ServletException {
        final ProfilingResource r = resolveDelegate( resp );
        if ( r == null ) return;
        final String path = req.getRequestURI();
        try {
            if ( path.endsWith( "/start" ) ) {
                final var body = com.google.gson.JsonParser.parseReader( req.getReader() ).getAsJsonObject();
                final int dur = body.get( "duration_s" ).getAsInt();
                final String label = body.has( "label" ) ? body.get( "label" ).getAsString() : "";
                writeJson( resp, 200, r.start( dur, label ) );
            } else if ( path.endsWith( "/stop" ) ) {
                final var body = com.google.gson.JsonParser.parseReader( req.getReader() ).getAsJsonObject();
                final String id = body.get( "recording_id" ).getAsString();
                writeJson( resp, 200, r.stop( id ) );
            } else {
                resp.sendError( HttpServletResponse.SC_NOT_FOUND );
            }
        } catch ( final ProfilingResource.RestError e ) {
            resp.sendError( e.status(), e.getMessage() );
        }
    }

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException, ServletException {
        final ProfilingResource r = resolveDelegate( resp );
        if ( r == null ) return;
        final String path = req.getRequestURI();
        try {
            if ( path.endsWith( "/recordings" ) ) {
                writeJson( resp, 200, r.list() );
                return;
            }
            // /recordings/{id}
            final String prefix = "/admin/profiling/jfr/recordings/";
            final int idx = path.indexOf( prefix );
            if ( idx >= 0 ) {
                final String id = path.substring( idx + prefix.length() );
                final Path file = r.filePathFor( id );
                resp.setContentType( "application/octet-stream" );
                resp.setHeader( "Content-Disposition",
                    "attachment; filename=\"" + file.getFileName() + "\"" );
                Files.copy( file, resp.getOutputStream() );
                return;
            }
            resp.sendError( HttpServletResponse.SC_NOT_FOUND );
        } catch ( final ProfilingResource.RestError e ) {
            resp.sendError( e.status(), e.getMessage() );
        }
    }

    private void writeJson( final HttpServletResponse resp, final int status, final String body ) throws IOException {
        resp.setStatus( status );
        resp.setContentType( "application/json" );
        resp.getWriter().write( body );
    }

    private ProfilingResource resolveDelegate( final HttpServletResponse resp ) throws IOException {
        ProfilingResource d = delegate;
        if ( d != null ) return d;
        synchronized ( this ) {
            if ( delegate != null ) return delegate;
            final String dir = System.getProperty( "wikantik.profiling.dir", "/var/wikantik/profiling" );
            final long cap = Long.getLong( "wikantik.profiling.dir.max_bytes", 5L * 1024 * 1024 * 1024 );
            final JfrProfilingService svc = new JfrProfilingService( Paths.get( dir ), cap );
            delegate = new ProfilingResource( svc );
            return delegate;
        }
    }
}
```

- [ ] **Step 4: Register the servlet in `web.xml`**

In `wikantik-war/src/main/webapp/WEB-INF/web.xml`, locate the existing admin servlet entries (search for `AdminAgentGradeAuditServlet`) and add a sibling block immediately after it:

```xml
    <servlet>
        <servlet-name>AdminProfilingServlet</servlet-name>
        <servlet-class>com.wikantik.rest.AdminProfilingServlet</servlet-class>
        <load-on-startup>1</load-on-startup>
    </servlet>
    <servlet-mapping>
        <servlet-name>AdminProfilingServlet</servlet-name>
        <url-pattern>/admin/profiling/jfr/*</url-pattern>
    </servlet-mapping>
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -pl wikantik-rest -am test -Dtest=ProfilingResourceTest -q`
Expected: all 4 tests pass.

- [ ] **Step 6: Commit**

```bash
git add wikantik-rest/src/main/java/com/wikantik/rest/admin/ProfilingResource.java \
        wikantik-rest/src/main/java/com/wikantik/rest/AdminProfilingServlet.java \
        wikantik-rest/src/test/java/com/wikantik/rest/admin/ProfilingResourceTest.java \
        wikantik-war/src/main/webapp/WEB-INF/web.xml
git commit -m "feat(rest): /admin/profiling/jfr/* — start/stop/list/download JFR recordings

Two-file admin pattern (ProfilingResource + AdminProfilingServlet) matching
the existing AgentGradeAudit pair. Behind AdminAuthFilter. Routes:
  POST /start            {duration_s,label}      -> RecordingInfo
  POST /stop             {recording_id}          -> RecordingInfo (FINISHED)
  GET  /recordings                                -> [RecordingInfo, ...]
  GET  /recordings/{id}                           -> .jfr file (octet-stream)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 3: Volume mount for `/var/wikantik/profiling`

**Files:**
- Modify: `docker-compose.prod.yml` — wikantik service volumes + a new named volume

- [ ] **Step 1: Add the volume mount**

In `docker-compose.prod.yml`, the `wikantik` service's `volumes:` block currently reads:

```yaml
    volumes:
      # Pages live on the host so they can be moved with rsync independently
      # of the container lifecycle. wikantik-work / wikantik-logs stay as
      # named volumes (regeneratable, no operator interest in their contents).
      - ${WIKANTIK_PAGES_DIR:-/srv/wikantik/pages}:/var/wikantik/pages
      - wikantik-work:/var/wikantik/work
      - wikantik-logs:/var/wikantik/logs
```

Add one more line below the existing three:

```yaml
      - wikantik-profiling:/var/wikantik/profiling
```

And at the bottom of the file (the base `docker-compose.yml` declares the other named volumes; prod doesn't redeclare them — but `wikantik-profiling` is prod-specific). Add a `volumes:` top-level block at the end of `docker-compose.prod.yml`:

```yaml
volumes:
  wikantik-profiling:
```

- [ ] **Step 2: Validate compose renders**

Run: `DB_HOST_BIND=172.17.0.1 docker compose -f docker-compose.yml -f docker-compose.prod.yml config | grep -A1 wikantik-profiling`
Expected: shows the volume mount on the wikantik service + the top-level volume declaration.

- [ ] **Step 3: Commit**

```bash
git add docker-compose.prod.yml
git commit -m "build: mount wikantik-profiling volume for JFR recordings (prod)

Named volume backing the new /admin/profiling/jfr/* endpoint's
recording directory. Wired only in prod since the dev stack has
no need to persist JFR captures.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 4: `MentionIndex.findRelatedPagesBatch`

**Files:**
- Modify: `wikantik-main/src/main/java/com/wikantik/knowledge/MentionIndex.java` — add batch method
- Modify: `wikantik-main/src/test/java/com/wikantik/knowledge/MentionIndexTest.java` (or create if absent) — add test

- [ ] **Step 1: Read the current `MentionIndex.findRelatedPages` to understand the data source**

Run: `sed -n '1,60p' wikantik-main/src/main/java/com/wikantik/knowledge/MentionIndex.java`

If the implementation uses a SQL `WHERE page_name = ?` query, the batch version becomes `WHERE page_name = ANY(?::text[])`. If it uses an in-memory index, the batch version just iterates and collects without a real I/O saving — still worth doing because it changes the consumer interface and makes a future SQL impl drop in cleanly.

- [ ] **Step 2: Write the failing test**

```java
// In MentionIndexTest.java — add this test
@Test
void findRelatedPagesBatchReturnsPerPageMap() {
    // Set up two pages with known mentions in the test fixture.
    final MentionIndex idx = newTestIndex();
    final var single1 = idx.findRelatedPages( "PageA", 5 );
    final var single2 = idx.findRelatedPages( "PageB", 5 );
    final var batch = idx.findRelatedPagesBatch( List.of( "PageA", "PageB" ), 5 );
    assertEquals( single1, batch.get( "PageA" ) );
    assertEquals( single2, batch.get( "PageB" ) );
}
```

(Adapt to the existing test fixture pattern — the executor should preserve whatever `newTestIndex()` or equivalent the existing tests use.)

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn -pl wikantik-main test -Dtest=MentionIndexTest#findRelatedPagesBatchReturnsPerPageMap -q`
Expected: COMPILE failure or test failure ("findRelatedPagesBatch not defined").

- [ ] **Step 4: Implement `findRelatedPagesBatch`**

Add the new method on `MentionIndex` mirroring `findRelatedPages`'s shape but accepting a list:

```java
/**
 * Batch variant of {@link #findRelatedPages(String, int)}. Returns a map
 * keyed by page name; missing keys default to an empty list. Semantics
 * identical to calling {@link #findRelatedPages} for each name; only the
 * I/O is batched.
 */
public Map<String, List<RelatedByMention>> findRelatedPagesBatch(
        final List<String> pageNames, final int limit ) {
    if ( pageNames == null || pageNames.isEmpty() ) return Map.of();
    final Map<String, List<RelatedByMention>> out = new LinkedHashMap<>();
    // PHASE 1 (always-correct fallback): per-name loop. If the existing
    // findRelatedPages is SQL-backed, replace this loop body with one
    // PreparedStatement using `page_name = ANY(?::text[])`.
    for ( final String name : pageNames ) {
        if ( name == null ) continue;
        out.put( name, findRelatedPages( name, limit ) );
    }
    return out;
}
```

If the executor's read of MentionIndex shows that `findRelatedPages` is SQL-backed, **replace the per-name loop with a single `WHERE page_name = ANY(?::text[])` query** in the same step, before running the test. The plan task remains valid either way — the consumer interface is what matters for `DefaultContextRetrievalService`.

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -pl wikantik-main test -Dtest=MentionIndexTest#findRelatedPagesBatchReturnsPerPageMap -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/knowledge/MentionIndex.java \
        wikantik-main/src/test/java/com/wikantik/knowledge/MentionIndexTest.java
git commit -m "feat(knowledge): MentionIndex.findRelatedPagesBatch — single-call API for N page lookups

Mirrors findRelatedPages(name, limit) but accepts a list, returning a
per-name map. Eliminates the N+1 pattern in DefaultContextRetrievalService
where the result-building loop called findRelatedPages once per result page.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 5: Replace N+1 `fetchRelatedPages` in `DefaultContextRetrievalService` with batch

**Files:**
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java`
- Modify: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/DefaultContextRetrievalServiceTest.java`

- [ ] **Step 1: Write the failing test**

Add a test asserting that for a 3-page result, `mentionIndex.findRelatedPagesBatch` is called **exactly once** and `mentionIndex.findRelatedPages` is **never** called:

```java
@Test
void retrieveBatchesFetchRelatedPages() {
    // Test fixture: 3 BM25 hits, mentionIndex stubbed to verify batch usage.
    when( mentionIndex.findRelatedPagesBatch( anyList(), anyInt() ) )
        .thenReturn( Map.of(
            "PageA", List.of(),
            "PageB", List.of(),
            "PageC", List.of() ) );
    final RetrievalResult result = service.retrieve(
        new ContextQuery( "test", 5, 3, null ) );
    verify( mentionIndex, times( 1 ) ).findRelatedPagesBatch( anyList(), anyInt() );
    verify( mentionIndex, never() ).findRelatedPages( anyString(), anyInt() );
    assertEquals( 3, result.pages().size() );
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl wikantik-knowledge test -Dtest=DefaultContextRetrievalServiceTest#retrieveBatchesFetchRelatedPages -q`
Expected: failure — current code still calls `findRelatedPages` per-page.

- [ ] **Step 3: Refactor `retrieve()` to batch related-pages lookup**

In `DefaultContextRetrievalService.java`, modify `retrieve()` (currently around lines 149-188). Replace the result-building loop's per-page `fetchRelatedPages` call with a pre-computed map:

```java
// Build the candidate page-name list from `ordered`
final List<String> candidateNames = new ArrayList<>();
for ( final SearchResult sr : ordered ) {
    if ( sr.getPage() != null ) candidateNames.add( sr.getPage().getName() );
}
// One batched lookup instead of N per-page calls
final Map<String, List<RelatedPage>> relatedByPage = fetchRelatedPagesBatch( candidateNames );

// Then in the existing loop, replace fetchRelatedPages(page.getName()) with:
//   relatedByPage.getOrDefault( page.getName(), List.of() )
```

Add a private `fetchRelatedPagesBatch(List<String>)` method in the same file mirroring the existing single-name `fetchRelatedPages` but consuming `mentionIndex.findRelatedPagesBatch(...)` and shaping the result identically. Keep the single-name `fetchRelatedPages` method available for any other callers (and to make the diff small) — but the `retrieve()` path no longer calls it.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl wikantik-knowledge test -Dtest=DefaultContextRetrievalServiceTest -q`
Expected: the new test passes AND all existing tests still pass (search-result shape preserved).

- [ ] **Step 5: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/DefaultContextRetrievalServiceTest.java
git commit -m "perf(search): batch fetchRelatedPages in DefaultContextRetrievalService

Result-building loop previously made one MentionIndex.findRelatedPages
call per result page (N+1 pattern). Pre-compute the per-name map in one
call to findRelatedPagesBatch before the loop, then look up by name
inside. Identical RetrievedPage output; tests assert single batch call
+ zero per-page calls.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 6: `RerankOutcome` + `HybridSearchService.rerankWithChunks`

**Files:**
- Create: `wikantik-main/src/main/java/com/wikantik/search/hybrid/RerankOutcome.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/search/hybrid/HybridSearchService.java`
- Modify: `wikantik-main/src/main/java/com/wikantik/search/hybrid/DenseRetriever.java` — expose chunks
- Create: `wikantik-main/src/test/java/com/wikantik/search/hybrid/HybridSearchServiceRerankOutcomeTest.java`

- [ ] **Step 1: Create the `RerankOutcome` record**

```java
// wikantik-main/src/main/java/com/wikantik/search/hybrid/RerankOutcome.java
/* ASF header */
package com.wikantik.search.hybrid;

import java.util.List;
import java.util.Optional;

/**
 * Carries the output of a hybrid rerank: the fused page-name ordering
 * (always present) plus optional dense-retrieval chunks that downstream
 * callers can reuse to avoid a second full-corpus scan.
 *
 * <p>{@link #denseChunks()} is empty when hybrid retrieval was disabled,
 * the query embedding was unavailable, or the dense retriever returned
 * no chunks. Consumers must treat it as advisory: a present list means
 * "you can short-circuit a downstream scan"; an empty list means
 * "fall through to the normal path".</p>
 */
public record RerankOutcome(
    List<String> fusedPageNames,
    Optional<List<ScoredChunk>> denseChunks
) {
    public static RerankOutcome bm25Only( final List<String> names ) {
        return new RerankOutcome( names, Optional.empty() );
    }
}
```

- [ ] **Step 2: Add `topKChunksRaw` to `DenseRetriever` (preserves the raw chunks before page aggregation)**

In `DenseRetriever.java`, add a sibling method that returns the chunks too:

```java
/**
 * Like {@link #retrieve} but also returns the raw {@link ScoredChunk} list
 * the page aggregation was built from. The chunks live inside this class
 * already; exposing them avoids re-running the brute-force scan in
 * downstream consumers (e.g. {@code DefaultContextRetrievalService.fetchContributingChunks}).
 */
public record Result( List<ScoredPage> pages, List<ScoredChunk> chunks ) {}

public Result retrieveWithChunks( final float[] queryVec ) {
    if( queryVec == null ) throw new IllegalArgumentException( "queryVec must not be null" );
    if( !index.isReady() ) return new Result( List.of(), List.of() );
    if( queryVec.length != index.dimension() ) {
        throw new IllegalArgumentException( "queryVec length " + queryVec.length
            + " does not match index dimension " + index.dimension() );
    }
    final List<ScoredChunk> chunks = index.topKChunks( queryVec, chunkTop );
    final List<ScoredPage> pages = aggregator.aggregate( chunks, strategy );
    final List<ScoredPage> top = pages.size() > pageTop ? pages.subList( 0, pageTop ) : pages;
    return new Result( top, chunks );
}
```

Leave the existing `retrieve` method untouched (still callable; just forwards to the new method internally).

- [ ] **Step 3: Add `rerankWithChunks` to `HybridSearchService`**

```java
/**
 * Like {@link #rerank} but also returns the raw dense chunks the rerank
 * was built from, so callers can avoid re-scanning the corpus. Empty
 * {@code denseChunks} signals fallback-to-BM25 paths.
 */
public RerankOutcome rerankWithChunks( final String query, final List<String> bm25PageNames ) {
    if ( query == null || query.isBlank() ) {
        return RerankOutcome.bm25Only( bm25PageNames == null ? List.of() : bm25PageNames );
    }
    final List<String> bm25 = bm25PageNames == null ? List.of() : bm25PageNames;
    if ( !enabled ) return RerankOutcome.bm25Only( bm25 );
    final Optional<float[]> vec;
    try {
        vec = embedder.embed( query );
    } catch( final RuntimeException e ) {
        LOG.warn( "QueryEmbedder.embed threw despite never-throws contract; falling back to BM25: {}",
            e.getMessage(), e );
        return RerankOutcome.bm25Only( bm25 );
    }
    if ( vec.isEmpty() ) {
        return RerankOutcome.bm25Only( bm25 );
    }
    final DenseRetriever.Result dr;
    try {
        dr = denseRetriever.retrieveWithChunks( vec.get() );
    } catch( final RuntimeException e ) {
        LOG.warn( "DenseRetriever failed; falling back to BM25: {}", e.getMessage(), e );
        return RerankOutcome.bm25Only( bm25 );
    }
    if ( dr.pages().isEmpty() ) {
        return new RerankOutcome( bm25, Optional.of( dr.chunks() ) );
    }
    final List<String> denseNames = new ArrayList<>( dr.pages().size() );
    for ( final ScoredPage sp : dr.pages() ) denseNames.add( sp.pageName() );
    final List<String> fused = fuser.fuse( bm25, denseNames );
    final LinkedHashSet<String> out = new LinkedHashSet<>( fused );
    for ( final String name : bm25 ) out.add( name );
    return new RerankOutcome(
        Collections.unmodifiableList( new ArrayList<>( out ) ),
        Optional.of( dr.chunks() ) );
}
```

Deprecate `rerank(...)` (the existing method) by adding `@Deprecated` and re-implementing it as a thin forwarder:

```java
/** @deprecated since the search-path-optimization spec; use {@link #rerankWithChunks}. */
@Deprecated( since = "2026-05-20" )
public List<String> rerank( final String query, final List<String> bm25PageNames ) {
    return rerankWithChunks( query, bm25PageNames ).fusedPageNames();
}
```

- [ ] **Step 4: Write a guard test for `rerankWithChunks`**

```java
// HybridSearchServiceRerankOutcomeTest.java
@Test
void rerankWithChunksReturnsChunksWhenDenseSucceeds() {
    // Mock DenseRetriever.retrieveWithChunks to return non-empty chunks
    when( dense.retrieveWithChunks( any() ) ).thenReturn(
        new DenseRetriever.Result( List.of( new ScoredPage( "PageA", 1.0 ) ),
                                   List.of( new ScoredChunk( UUID.randomUUID(), "PageA", 0.9 ) ) ) );
    final RerankOutcome out = svc.rerankWithChunks( "q", List.of( "PageA" ) );
    assertTrue( out.denseChunks().isPresent() );
    assertEquals( 1, out.denseChunks().get().size() );
}

@Test
void rerankWithChunksReturnsEmptyChunksOnFallback() {
    when( embedder.embed( "q" ) ).thenReturn( Optional.empty() );
    final RerankOutcome out = svc.rerankWithChunks( "q", List.of( "PageA" ) );
    assertTrue( out.denseChunks().isEmpty() );
    assertEquals( List.of( "PageA" ), out.fusedPageNames() );
}

@Test
void deprecatedRerankStillCompiles() {
    when( embedder.embed( "q" ) ).thenReturn( Optional.empty() );
    @SuppressWarnings( "deprecation" )
    final List<String> names = svc.rerank( "q", List.of( "PageA" ) );
    assertEquals( List.of( "PageA" ), names );
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -pl wikantik-main test -Dtest=HybridSearchServiceRerankOutcomeTest -q` and `mvn -pl wikantik-main test -Dtest=HybridSearchServiceTest -q` (the existing test file — must still pass).
Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add wikantik-main/src/main/java/com/wikantik/search/hybrid/RerankOutcome.java \
        wikantik-main/src/main/java/com/wikantik/search/hybrid/HybridSearchService.java \
        wikantik-main/src/main/java/com/wikantik/search/hybrid/DenseRetriever.java \
        wikantik-main/src/test/java/com/wikantik/search/hybrid/HybridSearchServiceRerankOutcomeTest.java
git commit -m "feat(search): RerankOutcome — surface dense chunks for downstream reuse

HybridSearchService.rerankWithChunks returns both the fused page names
and the raw dense chunks. DenseRetriever gains retrieveWithChunks for
the same purpose. The old rerank(...) is now a deprecated forwarder.
DefaultContextRetrievalService can now skip the second full-corpus
scan when the first scan already produced enough chunks.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 7: `fetchContributingChunks` surgery in `DefaultContextRetrievalService`

**Files:**
- Modify: `wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java`
- Modify: `wikantik-knowledge/src/test/java/com/wikantik/knowledge/DefaultContextRetrievalServiceTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void fetchContributingChunksShortCircuitsWhenChunksPerPageZero() {
    final RetrievalResult result = service.retrieve(
        new ContextQuery( "test", 5, 0, null ) );  // chunksPerPage = 0
    // Verify chunkIndex.topKChunks was never called with the contributing-chunks limit
    verify( chunkIndex, never() ).topKChunks( any(), eq( 200 ) );
    assertEquals( 0, result.pages().get( 0 ).chunks().size() );
}

@Test
void fetchContributingChunksReusesFirstScanWhenSufficient() {
    // With 3 pages × 5 chunks/page = 15 chunks needed; the first hybrid scan
    // returned 50 chunks. The second scan should NOT run.
    when( hybridSearch.rerankWithChunks( any(), anyList() ) )
        .thenReturn( new RerankOutcome( List.of( "P1", "P2", "P3" ),
            Optional.of( fiftyTestChunks( "P1", "P2", "P3" ) ) ) );
    service.retrieve( new ContextQuery( "test", 3, 5, null ) );
    verify( chunkIndex, never() ).topKChunks( any(), eq( 200 ) );
}

@Test
void fetchContributingChunksFallsThroughWhenFirstScanInsufficient() {
    // With 10 pages × 50 chunks/page = 500 needed; first scan only returned 10.
    // Second scan SHOULD run.
    when( hybridSearch.rerankWithChunks( any(), anyList() ) )
        .thenReturn( new RerankOutcome( List.of( "P1" /* ... */ ),
            Optional.of( tenTestChunks() ) ) );
    when( chunkIndex.topKChunks( any(), eq( 200 ) ) ).thenReturn( twoHundredTestChunks() );
    service.retrieve( new ContextQuery( "test", 10, 50, null ) );
    verify( chunkIndex, times( 1 ) ).topKChunks( any(), eq( 200 ) );
}
```

(Helpers `fiftyTestChunks`, `tenTestChunks`, `twoHundredTestChunks` return appropriately-sized `List<ScoredChunk>` for the assertions.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl wikantik-knowledge test -Dtest=DefaultContextRetrievalServiceTest -q`
Expected: the three new tests fail; current code unconditionally calls topKChunks.

- [ ] **Step 3: Modify `applyHybridAndGraphRerank` to consume `RerankOutcome`**

Currently `applyHybridAndGraphRerank` calls `hybridSearch.rerank(...)` and discards the chunks. Change it to call `rerankWithChunks(...)`, propagate the chunks via a new return-pair `(List<SearchResult>, Optional<List<ScoredChunk>>)`. The simplest shape: return a tuple class local to the file, or modify `retrieve()` to capture both directly:

```java
// Inside retrieve(), replace:
//   final List< SearchResult > ordered = applyHybridAndGraphRerank( query.query(), bm25 );
// with:
final RerankOutcome rerankOutcome = applyHybridAndGraphRerankOutcome( query.query(), bm25 );
final List<SearchResult> ordered = rerankOutcome.fusedPageNames();   // see below — overload retained
final Optional<List<ScoredChunk>> reusableChunks = rerankOutcome.denseChunks();
```

For minimal disruption, keep `applyHybridAndGraphRerank` returning `List<SearchResult>` for any other callers and add a sibling method `applyHybridAndGraphRerankWithChunks` returning a pair `(List<SearchResult>, Optional<List<ScoredChunk>>)`. Pass `reusableChunks` into `fetchContributingChunks` as a new parameter.

- [ ] **Step 4: Add the short-circuit + reuse guards to `fetchContributingChunks`**

The method's existing signature is `Map<String,List<RetrievedChunk>> fetchContributingChunks(String query, List<SearchResult> ordered, int chunksPerPage)`. Add a new optional `Optional<List<ScoredChunk>> reusable` parameter (or overload). Inside:

```java
if ( chunksPerPage <= 0 ) return Map.of();        // short-circuit #1
final List<ScoredChunk> topChunks;
final int needed = ordered.size() * chunksPerPage;
if ( reusable.isPresent() && reusable.get().size() >= needed ) {
    topChunks = reusable.get();                   // reuse #2
} else {
    // Fall through to the existing path: embed (cache hit), then topKChunks(emb, 200)
    final Optional<float[]> embedding;
    try { embedding = hybridSearch.prefetchQueryEmbedding( query ).get( 2500, TimeUnit.MILLISECONDS ); }
    catch ( final Exception e ) { return Map.of(); }
    if ( embedding.isEmpty() ) return Map.of();
    topChunks = chunkIndex.topKChunks( embedding.get(), 200 );
}
if ( topChunks.isEmpty() ) return Map.of();
// ...existing groupChunksByInterestingPage + shapeChunkOutput continues unchanged
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -pl wikantik-knowledge test -Dtest=DefaultContextRetrievalServiceTest -q`
Expected: all tests pass (new + existing).

- [ ] **Step 6: Commit**

```bash
git add wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java \
        wikantik-knowledge/src/test/java/com/wikantik/knowledge/DefaultContextRetrievalServiceTest.java
git commit -m "perf(search): fetchContributingChunks short-circuit + reuse first-scan chunks

Two guards inside DefaultContextRetrievalService:
  1. chunksPerPage <= 0  -> return Map.of(), skip second brute-force scan
  2. first hybrid scan already produced >= pages * chunksPerPage chunks
     -> reuse those instead of running topKChunks(emb, 200) a second time

Plumbs RerankOutcome.denseChunks through applyHybridAndGraphRerank to the
contributing-chunk pass. RetrievedPage shape is unchanged (existing tests
still pass).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 8: Integration test for the JFR endpoint

**Files:**
- Create: `wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/it/rest/ProfilingResourceIT.java`

- [ ] **Step 1: Locate the existing IT pattern**

Run: `ls wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/it/rest/`

Pick a representative existing IT (e.g. one that calls an `/admin/*` endpoint) and copy its scaffolding (helper imports, admin-auth helpers, JSON parsing) into the new file.

- [ ] **Step 2: Write the IT**

```java
// Outline; adapt imports to the existing IT scaffolding
@Test
void jfrStartListDownloadHappyPath() throws Exception {
    // 1. Start a 2-second recording
    final String startBody = "{\"duration_s\":2,\"label\":\"it-smoke\"}";
    final HttpResponse<String> start = adminPost( "/admin/profiling/jfr/start", startBody );
    assertEquals( 200, start.statusCode() );
    final JsonObject startJson = JsonParser.parseString( start.body() ).getAsJsonObject();
    final String id = startJson.get( "recording_id" ).getAsString();
    assertEquals( "RUNNING", startJson.get( "status" ).getAsString() );

    // 2. Sleep 3 seconds so the JFR duration-timer auto-stops the recording
    TimeUnit.SECONDS.sleep( 3 );

    // 3. List recordings — ours should appear
    final HttpResponse<String> list = adminGet( "/admin/profiling/jfr/recordings" );
    assertEquals( 200, list.statusCode() );
    assertTrue( list.body().contains( id ) );

    // 4. Explicit stop (idempotent — JFR already auto-stopped)
    adminPost( "/admin/profiling/jfr/stop", "{\"recording_id\":\"" + id + "\"}" );

    // 5. Download the .jfr file
    final HttpResponse<byte[]> dl = adminGetBytes( "/admin/profiling/jfr/recordings/" + id );
    assertEquals( 200, dl.statusCode() );
    final byte[] bytes = dl.body();
    assertTrue( bytes.length > 0 );
    // JFR magic: 'F','L','R','\0'
    assertArrayEquals( new byte[]{ 'F', 'L', 'R', 0 },
                       new byte[]{ bytes[0], bytes[1], bytes[2], bytes[3] } );
}

@Test
void jfrConcurrentStartReturns409() throws Exception {
    final HttpResponse<String> a = adminPost( "/admin/profiling/jfr/start",
        "{\"duration_s\":3,\"label\":\"first\"}" );
    assertEquals( 200, a.statusCode() );
    final HttpResponse<String> b = adminPost( "/admin/profiling/jfr/start",
        "{\"duration_s\":3,\"label\":\"second\"}" );
    assertEquals( 409, b.statusCode() );
    // Cleanup: stop the running one
    final String aId = JsonParser.parseString( a.body() ).getAsJsonObject().get( "recording_id" ).getAsString();
    adminPost( "/admin/profiling/jfr/stop", "{\"recording_id\":\"" + aId + "\"}" );
}
```

(`adminPost`, `adminGet`, `adminGetBytes` exist as helpers in the existing IT scaffolding — reuse, don't reinvent.)

- [ ] **Step 3: Run the IT module**

Run: `mvn -pl wikantik-it-tests/wikantik-it-test-rest -am verify -Pintegration-tests -fae`
Expected: both new ITs pass.

- [ ] **Step 4: Commit**

```bash
git add wikantik-it-tests/wikantik-it-test-rest/src/test/java/com/wikantik/it/rest/ProfilingResourceIT.java
git commit -m "test(it): ProfilingResourceIT — JFR start/list/download against Cargo Tomcat

Happy path: start 2s recording, sleep, list, stop, download, assert
JFR magic bytes 'FLR\0' in the output. Concurrency path: second start
while one is running returns 409.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 9: Full reactor verification + deploy

**Files:** none — verification + deploy only.

- [ ] **Step 1: Unit build**

Run: `mvn clean install -T 1C -DskipITs > /tmp/build-search-opt.log 2>&1; tail -20 /tmp/build-search-opt.log`
Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Full integration-test reactor**

Run: `mvn clean install -Pintegration-tests -fae > /tmp/build-search-opt-it.log 2>&1; tail -25 /tmp/build-search-opt-it.log`
Expected: `BUILD SUCCESS`, every IT module green. Per the project's memorised gate: every prod-code commit ships behind this.

- [ ] **Step 3: Deploy to docker1**

Run: `bin/remote.sh deploy > /tmp/deploy-search-opt.log 2>&1`
This rebuilds the docker image (entrypoint + server.xml unchanged but the WAR is new), ships it, recreates wikantik with the new code + the `wikantik-profiling` volume. ~10 min.

- [ ] **Step 4: Verify the new endpoint and the volume**

```bash
echo "=== profiling volume mounted ===" && ssh jakefear@docker1 'docker inspect repo-wikantik-1 --format "{{range .Mounts}}{{.Destination}} {{end}}"'
echo "=== smoke-test the endpoint ===" && curl -u "$(grep '^admin' test.properties || echo admin:admin):" \
  -X POST http://192.168.0.4:8080/admin/profiling/jfr/start \
  -H 'Content-Type: application/json' -d '{"duration_s":2,"label":"smoke"}'
```
Expected: mounts include `/var/wikantik/profiling`; start returns JSON with `"status":"RUNNING"`.

- [ ] **Step 5: Push**

Run: `git push origin main`

---

### Task 10: Run Sweep #3 with JFR capture during N=300

**Files:**
- Outputs: `loadtest/results/sweep3-{200,300,400}vu-{k6,curl,host}.{log,json}`, `loadtest/results/sweep3-300vu.jfr`, `loadtest/results/sweep3-300vu-jfr-top20.txt`

- [ ] **Step 1: Sweep step N=200**

Same procedure as Sweep #2 Task 3 Step 2. Capture k6, curl, jakemon snapshots.

- [ ] **Step 2: Sweep step N=300 with parallel JFR capture**

Start the curl-probe + k6 step as before. While the k6 ramp is reaching peak (~2 minutes in), kick off a 60-second JFR recording from a separate shell:

```bash
PASS=$(grep test.user.password test.properties | cut -d= -f2)
JFR_START=$(curl -s -u "testbot:${PASS}" -X POST \
  http://192.168.0.4:8080/admin/profiling/jfr/start \
  -H 'Content-Type: application/json' \
  -d '{"duration_s":60,"label":"sweep3-300vu"}')
echo "$JFR_START"
JFR_ID=$(echo "$JFR_START" | python3 -c 'import sys,json;print(json.load(sys.stdin)["recording_id"])')
sleep 65
curl -s -u "testbot:${PASS}" -o loadtest/results/sweep3-300vu.jfr \
  "http://192.168.0.4:8080/admin/profiling/jfr/recordings/${JFR_ID}"
ls -la loadtest/results/sweep3-300vu.jfr
```

- [ ] **Step 3: Sweep step N=400**

Same as N=200/N=300 minus the JFR capture.

- [ ] **Step 4: Extract the JFR top-20 hot methods**

```bash
jfr print --events jdk.ExecutionSample --stack-depth 20 loadtest/results/sweep3-300vu.jfr \
  | awk '/Stack Trace:/{flag=1; next} /^$/{flag=0} flag{print $0}' \
  | sort | uniq -c | sort -rn | head -20 \
  > loadtest/results/sweep3-300vu-jfr-top20.txt
echo "--- top 20 hot methods ---" && cat loadtest/results/sweep3-300vu-jfr-top20.txt
```

(If `jfr print` requires a specific JDK 21 binary path, the executor adapts. Output should be a frequency-ranked list of method names appearing on stack samples.)

---

### Task 11: Write §9 addendum to `docs/ScalingCharacterization.md`

**Files:**
- Modify: `docs/ScalingCharacterization.md` — append a section

- [ ] **Step 1: Append the §9 addendum**

Append to the bottom of the file (before the "Raw data" section if present, otherwise at the end):

```markdown
## 9. Sweep #3 — code-surgery + JFR findings (2026-05-20)

### Sweep #3 vs Sweep #2 at matching VU levels

| N | Sweep #2 RPS / p95 / load1 | Sweep #3 RPS / p95 / load1 | Δ RPS | Δ p95 | Δ load1 |
|---|---|---|---|---|---|
| 200 | 129.3 / 750 ms / 12.44 | <fill> / <fill> / <fill> | <Δ> | <Δ> | <Δ> |
| 300 | 129.2 / 3.51 s / 13.27  | <fill> / <fill> / <fill> | <Δ> | <Δ> | <Δ> |
| 400 | 122.9 / 5.89 s / 10.82  | <fill> / <fill> / <fill> | <Δ> | <Δ> | <Δ> |

### JFR top-20 hot methods at N=300 (60 s capture)

(Paste the contents of `loadtest/results/sweep3-300vu-jfr-top20.txt` here, lightly cleaned: trim leading whitespace, drop addresses, keep `package.Class.method` references.)

### Interpretation

(One paragraph: which hot methods dominate, where they live, what this implies for the v2 spec. If batched `fetchRelatedPages` shows up disappeared and `fetchContributingChunks` no longer second-scans, name those as confirmed wins.)

### Next round: v2 topic

The next optimization spec is `2026-05-DD-<topic>-design.md`. Topic candidates surfaced by the profile, ordered by stack-sample share:

- (item 1)
- (item 2)
- (item 3)
```

Fill in every `<fill>` and `<Δ>` from the Sweep #3 artifacts. The interpretation paragraph names the v2 topic.

- [ ] **Step 2: Commit**

```bash
git add docs/ScalingCharacterization.md
git commit -m "docs: ScalingCharacterization §9 — Sweep #3 + JFR findings

Adds row-for-row comparison against Sweep #2 at N=200/300/400, the
JFR top-20 hot methods captured during the N=300 sustained phase, and
names the v2 optimization topic based on the profile.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 12: Push to origin

The News.md changelog convention is retired (2026-05-20 operator decision):
no per-commit content-log commits. Final task is just to push the
accumulated commits.

- [ ] **Step 1: Push**

```bash
git push origin main
```

---

## Self-Review

- **Spec coverage:**
  - JFR profiling endpoint (spec §1) → Tasks 1, 2, 3, 8, 9 ✓
  - `fetchContributingChunks` surgery (spec §2) → Tasks 6, 7 ✓
  - `fetchRelatedPages` N+1 → batch (spec §3) → Tasks 4, 5 ✓
  - Re-sweep + JFR capture (spec §4) → Tasks 9, 10 ✓
  - §9 addendum (spec §4) → Task 11 ✓
  - Done criteria (spec) — `docs/ScalingCharacterization.md` updated, JFR file preserved, code committed and deployed, full IT green, v2 topic agreed → covered by Tasks 9-12 ✓
  - News.md log: retired by operator decision 2026-05-20; Task 12 is push-only ✓
- **Placeholder scan:** the `<fill>`/`<Δ>` markers in Task 11 Step 1 are *intentional* template slots the executor fills from Sweep #3 artifacts (the plan can't enumerate them ahead of time — empirical). The "(item 1)…(item 3)" markers under v2 candidates likewise depend on the profile. Other task code blocks contain real code, real test bodies, real commands. The "(executor adapts)" note on the `jfr print` invocation in Task 10 Step 4 acknowledges JDK-binary location variability — acceptable for an inline executor that can resolve at run time.
- **Type/name consistency:** `RecordingInfo` carries the same fields across `JfrProfilingService` (Task 1), `ProfilingResource` (Task 2), and the IT (Task 8). `RerankOutcome` is defined in Task 6 Step 1 and consumed in Task 7 Step 3. `findRelatedPagesBatch` shape — `Map<String, List<RelatedByMention>>` — is consistent between Tasks 4 and 5. The `wikantik-profiling` volume name is consistent across `docker-compose.prod.yml` and the verification step. No drift.
