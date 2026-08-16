# Content Intelligence Phase 0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make ten weeks of already-collected multi-engine search data queryable inside Wikantik, so delivered clicks per engine, CTR-curve calibration, and effect measurement all become possible.

**Architecture:** jakemon's `visibility` exporter already writes one JSON snapshot per (engine, site, date) to a Docker volume on docker2 — 1,486 files back to 2026-06-04, each holding `by_query`, `by_page`, `totals`, `coverage`. A standalone shipper reads that tree and POSTs to a new admin-authed Wikantik endpoint, which upserts into one Postgres table. **The exporter itself is never modified.**

**Tech Stack:** Java 25 / Maven (Wikantik), PostgreSQL, React SPA, Python 3 stdlib (jakemon shipper).

**Spec:** [docs/superpowers/specs/2026-08-16-content-intelligence-design.md](../specs/2026-08-16-content-intelligence-design.md)

## Global Constraints

- **Never modify `jakemon/visibility/exporter.py`.** The poll loop serves eight sites; this work must not be able to break it. Part B is additive-only.
- New Maven module MUST declare `mockito-core` in test scope or surefire fails VM init on the inherited javaagent.
- **A new module must ALSO be added to `wikantik-war/pom.xml`.** `wikantik-rest` declares its
  siblings `provided`, so the jar reaches `WEB-INF/lib` only via `wikantik-war`. Miss it and the
  context dies at deploy with `NoClassDefFoundError` — compile and every unit test still pass,
  because the class is on the compile classpath either way. (Hit for real on 2026-08-16.)
- **`/admin/*` authenticates with HTTP Basic** via `AdminAuthFilter`, not bearer tokens. Any
  client shipping to an admin endpoint must send Basic, and must not put credentials in the URL —
  urllib ignores userinfo, so a password with URL-significant characters fails as a DNS error
  rather than an auth error.
- To stop the local Tomcat, resolve the PID with `pgrep -f 'catalina[.]base=<abs path>'` and
  `kill` it. **Never `pkill -f` on a catalina pattern** — it matches containerised Tomcats
  running on this host (it killed the CrafterCMS delivery container on 2026-08-16) and also
  matches your own shell's command line.
- New SPA route requires dual registration: `web.xml` **and** `SpaRoutingFilter.SPA_EXACT`. Either alone 404s.
- Migrations are DDL-only and idempotent; grants via the `:app_user` psql variable; no data backfills in a migration.
- REST errors use `RestServletBase.sendError()`, never raw `response.sendError()`.
- No Prometheus metric may carry a page slug, query string, or session id as a label (spec D2).
- jakemon repo rules: Python **stdlib only**, `bin/validate.sh` is the commit gate, all `bin/` scripts pass `shellcheck -x` and support `--help`.
- Commit gate for Wikantik: `bin/run-tests.sh --parallel 4`.
- Table grain is **snapshot**, not day: each row is a trailing 28-day aggregate stamped with its window end date.

---

# PART A — Wikantik changes

## Task A1: Migration — `search_visibility_snapshot`

**Files:**
- Create: `bin/db/migrations/V050__search_visibility_snapshot.sql`

**Interfaces:**
- Produces: table `search_visibility_snapshot`, PK `(snapshot_date, engine, site_host, page_path, query_text)`, consumed by A3.

- [ ] **Step 1: Write the migration**

```sql
-- V050: queryable search-visibility facts. One row per (snapshot, engine, site, page, query).
-- Each row is a TRAILING WINDOW AGGREGATE (window_days, normally 28) stamped with the window's
-- END date -- NOT a per-day grain. Source is jakemon's visibility snapshot tree, shipped by
-- bin/ship-visibility.py. query_text = '' marks the page-level rollup row, which is authoritative
-- for page totals (engines omit low-volume queries, so summing query rows undercounts).
-- Idempotent / DDL-only.

CREATE TABLE IF NOT EXISTS search_visibility_snapshot (
    snapshot_date DATE        NOT NULL,
    window_days   SMALLINT    NOT NULL,
    engine        TEXT        NOT NULL,
    site_host     TEXT        NOT NULL,
    page_path     TEXT        NOT NULL,
    query_text    TEXT        NOT NULL,
    impressions   INTEGER     NOT NULL,
    clicks        INTEGER     NOT NULL,
    position      NUMERIC(6,2),
    ingested_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (snapshot_date, engine, site_host, page_path, query_text)
);

CREATE INDEX IF NOT EXISTS idx_svs_date   ON search_visibility_snapshot (snapshot_date);
CREATE INDEX IF NOT EXISTS idx_svs_engine ON search_visibility_snapshot (engine, snapshot_date);
CREATE INDEX IF NOT EXISTS idx_svs_page   ON search_visibility_snapshot (site_host, page_path, snapshot_date);
CREATE INDEX IF NOT EXISTS idx_svs_query  ON search_visibility_snapshot (query_text, snapshot_date);

GRANT SELECT, INSERT, UPDATE, DELETE ON search_visibility_snapshot TO :app_user;
```

- [ ] **Step 2: Apply it twice to prove idempotency**

Run: `DB_NAME=wikantik DB_APP_USER=wikantik bin/db/migrate.sh && DB_NAME=wikantik DB_APP_USER=wikantik bin/db/migrate.sh --status`
Expected: first run applies V050; `--status` shows it applied once; a second `migrate.sh` is a no-op.

- [ ] **Step 3: Commit**

```bash
git add bin/db/migrations/V050__search_visibility_snapshot.sql
git commit -m "feat(insights): search_visibility_snapshot fact table"
```

---

## Task A2: `wikantik-insights` module skeleton and store port

**Files:**
- Create: `wikantik-insights/pom.xml`
- Create: `wikantik-insights/src/main/java/com/wikantik/insights/VisibilityRow.java`
- Create: `wikantik-insights/src/main/java/com/wikantik/insights/InsightsStore.java`
- Create: `wikantik-insights/src/main/java/com/wikantik/insights/JdbcInsightsStore.java`
- Create: `wikantik-insights/src/test/java/com/wikantik/insights/JdbcInsightsStoreTest.java`
- Modify: `pom.xml` (add `<module>wikantik-insights</module>`)
- Modify: `wikantik-war/pom.xml` (add the module as a compile-scope dependency so it is packaged)

**Interfaces:**
- Produces: `VisibilityRow` record; `InsightsStore.upsert(List<VisibilityRow>) -> int`; consumed by A3.

- [ ] **Step 1: Write the failing test**

```java
package com.wikantik.insights;

import org.junit.jupiter.api.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;

class JdbcInsightsStoreTest {

    private DataSource ds;   // H2 in PostgreSQL compatibility mode

    @BeforeEach
    void setUp() throws Exception {
        ds = TestDataSources.h2( "svs" );
        try ( Connection c = ds.getConnection(); Statement st = c.createStatement() ) {
            st.execute( """
                CREATE TABLE IF NOT EXISTS search_visibility_snapshot (
                    snapshot_date DATE NOT NULL, window_days SMALLINT NOT NULL,
                    engine VARCHAR NOT NULL, site_host VARCHAR NOT NULL,
                    page_path VARCHAR NOT NULL, query_text VARCHAR NOT NULL,
                    impressions INTEGER NOT NULL, clicks INTEGER NOT NULL,
                    position NUMERIC(6,2), ingested_at TIMESTAMP DEFAULT NOW(),
                    PRIMARY KEY (snapshot_date, engine, site_host, page_path, query_text))""" );
        }
    }

    private VisibilityRow row( final int impressions, final int clicks ) {
        return new VisibilityRow( LocalDate.of( 2026, 8, 14 ), 28, "bing",
                "wiki.wikantik.com", "/wiki/PhilosophyHub", "", impressions, clicks, 5.2 );
    }

    @Test
    void upsertIsIdempotentAndOverwritesOnConflict() throws Exception {
        final InsightsStore store = new JdbcInsightsStore( ds );

        assertEquals( 1, store.upsert( List.of( row( 100, 4 ) ) ) );
        assertEquals( 1, store.upsert( List.of( row( 120, 7 ) ) ) );

        try ( Connection c = ds.getConnection();
              ResultSet rs = c.createStatement().executeQuery(
                  "SELECT COUNT(*) n, MAX(impressions) i, MAX(clicks) k "
                  + "FROM search_visibility_snapshot" ) ) {
            assertTrue( rs.next() );
            assertEquals( 1, rs.getInt( "n" ), "re-sending a window must converge, not duplicate" );
            assertEquals( 120, rs.getInt( "i" ) );
            assertEquals( 7, rs.getInt( "k" ) );
        }
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `mvn test -pl wikantik-insights -Dtest=JdbcInsightsStoreTest`
Expected: FAIL — `VisibilityRow`/`InsightsStore`/`JdbcInsightsStore` do not exist.

- [ ] **Step 3: Write the module POM**

Copy `wikantik-util/pom.xml` as the shape reference. Dependencies: `wikantik-api`, `com.google.code.gson:gson`, and **test scope**: `junit-jupiter`, `org.mockito:mockito-core`, `com.h2database:h2`. The `mockito-core` entry is mandatory (Global Constraints).

Add to the root `pom.xml` `<modules>` block, after `wikantik-observability`:

```xml
<module>wikantik-insights</module>
```

- [ ] **Step 4: Write the implementation**

```java
package com.wikantik.insights;

import java.time.LocalDate;

/** One trailing-window search-visibility aggregate. queryText "" = page-level rollup. */
public record VisibilityRow( LocalDate snapshotDate, int windowDays, String engine,
                             String siteHost, String pagePath, String queryText,
                             int impressions, int clicks, Double position ) {}
```

```java
package com.wikantik.insights;

import java.util.List;

public interface InsightsStore {
    /** Upsert rows keyed by (snapshotDate, engine, siteHost, pagePath, queryText).
     *  Returns the number of rows written. Re-sending a window converges. */
    int upsert( List< VisibilityRow > rows );
}
```

```java
package com.wikantik.insights;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

public class JdbcInsightsStore implements InsightsStore {

    private static final Logger LOG = LogManager.getLogger( JdbcInsightsStore.class );

    private static final String UPSERT_SQL = """
        INSERT INTO search_visibility_snapshot
            (snapshot_date, window_days, engine, site_host, page_path, query_text,
             impressions, clicks, position)
        VALUES (?,?,?,?,?,?,?,?,?)
        ON CONFLICT (snapshot_date, engine, site_host, page_path, query_text)
        DO UPDATE SET impressions = EXCLUDED.impressions,
                      clicks      = EXCLUDED.clicks,
                      position    = EXCLUDED.position,
                      window_days = EXCLUDED.window_days,
                      ingested_at = NOW()""";

    private final DataSource dataSource;

    public JdbcInsightsStore( final DataSource dataSource ) {
        this.dataSource = dataSource;
    }

    @Override
    public int upsert( final List< VisibilityRow > rows ) {
        if ( rows.isEmpty() ) {
            return 0;
        }
        try ( Connection c = dataSource.getConnection();
              PreparedStatement ps = c.prepareStatement( UPSERT_SQL ) ) {
            for ( final VisibilityRow r : rows ) {
                ps.setObject( 1, r.snapshotDate() );
                ps.setInt( 2, r.windowDays() );
                ps.setString( 3, r.engine() );
                ps.setString( 4, r.siteHost() );
                ps.setString( 5, r.pagePath() );
                ps.setString( 6, r.queryText() );
                ps.setInt( 7, r.impressions() );
                ps.setInt( 8, r.clicks() );
                if ( r.position() == null ) {
                    ps.setNull( 9, Types.NUMERIC );
                } else {
                    ps.setDouble( 9, r.position() );
                }
                ps.addBatch();
            }
            int written = 0;
            for ( final int n : ps.executeBatch() ) {
                written += Math.max( n, 0 );
            }
            return written;
        } catch ( final SQLException e ) {
            LOG.warn( "search-visibility upsert failed for {} rows: {}", rows.size(), e.getMessage(), e );
            return 0;
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn test -pl wikantik-insights -Dtest=JdbcInsightsStoreTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add pom.xml wikantik-insights
git commit -m "feat(insights): wikantik-insights module with JDBC visibility store"
```

---

## Task A3: Ingest endpoint `POST /admin/insights/ingest`

**Files:**
- Create: `wikantik-insights/src/main/java/com/wikantik/insights/SnapshotPayloadParser.java`
- Create: `wikantik-insights/src/test/java/com/wikantik/insights/SnapshotPayloadParserTest.java`
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/InsightsIngestResource.java`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml`

**Interfaces:**
- Consumes: `InsightsStore` (A2).
- Produces: `SnapshotPayloadParser.parse(String json, Set<String> engines, Set<String> sites) -> ParseResult{rows, rejected}`.

- [ ] **Step 1: Write the failing parser test**

```java
package com.wikantik.insights;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotPayloadParserTest {

    private static final Set< String > ENGINES = Set.of( "google", "bing", "yandex" );
    private static final Set< String > SITES   = Set.of( "wiki.wikantik.com" );

    private static final String PAYLOAD = """
        { "engine": "bing", "site": "wiki.wikantik.com",
          "snapshot_date": "2026-08-14", "window_days": 28,
          "by_page":  [ {"key":"https://wiki.wikantik.com/wiki/PhilosophyHub",
                         "clicks":0,"impressions":1,"position":9.0} ],
          "by_query": [ {"key":"philosophy hub","clicks":1,"impressions":3,"position":4.5} ] }""";

    @Test
    void parsesPageAndQueryRowsAndNormalisesPagePath() {
        final SnapshotPayloadParser.ParseResult r =
                SnapshotPayloadParser.parse( PAYLOAD, ENGINES, SITES );

        assertEquals( 2, r.rows().size() );
        assertEquals( 0, r.rejected() );

        final VisibilityRow page = r.rows().stream()
                .filter( x -> x.queryText().isEmpty() ).findFirst().orElseThrow();
        assertEquals( "/wiki/PhilosophyHub", page.pagePath(),
                      "scheme+host must be stripped so page_path is a stable key" );

        final VisibilityRow query = r.rows().stream()
                .filter( x -> !x.queryText().isEmpty() ).findFirst().orElseThrow();
        assertEquals( "philosophy hub", query.queryText() );
        assertEquals( "", query.pagePath(),
                      "query rows carry no page dimension in this payload shape" );
    }

    @Test
    void rejectsUnknownEngineAndUnknownSiteWithoutThrowing() {
        final String bad = PAYLOAD.replace( "\\"bing\\"", "\\"altavista\\"" );
        final SnapshotPayloadParser.ParseResult r =
                SnapshotPayloadParser.parse( bad, ENGINES, SITES );
        assertTrue( r.rows().isEmpty() );
        assertEquals( 1, r.rejected() );
    }

    @Test
    void malformedJsonYieldsRejectionNotException() {
        final SnapshotPayloadParser.ParseResult r =
                SnapshotPayloadParser.parse( "{ not json", ENGINES, SITES );
        assertTrue( r.rows().isEmpty() );
        assertEquals( 1, r.rejected() );
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `mvn test -pl wikantik-insights -Dtest=SnapshotPayloadParserTest`
Expected: FAIL — `SnapshotPayloadParser` does not exist.

- [ ] **Step 3: Write the parser**

```java
package com.wikantik.insights;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Parses one jakemon visibility snapshot into VisibilityRows. Never throws: a malformed or
 *  disallowed payload is counted as rejected so a bad shipper cannot 500 the endpoint. */
public final class SnapshotPayloadParser {

    private static final Logger LOG = LogManager.getLogger( SnapshotPayloadParser.class );

    public record ParseResult( List< VisibilityRow > rows, int rejected ) {}

    private SnapshotPayloadParser() {}

    /** Strip scheme+host+fragment and any trailing slash so page_path is a stable key. */
    static String normalisePath( final String raw ) {
        if ( raw == null || raw.isBlank() ) {
            return "";
        }
        String path = raw;
        try {
            final URI uri = URI.create( raw.trim() );
            if ( uri.getPath() != null && !uri.getPath().isBlank() ) {
                path = uri.getPath();
            }
        } catch ( final IllegalArgumentException e ) {
            LOG.warn( "unparseable page key '{}', using it verbatim: {}", raw, e.getMessage() );
        }
        final int hash = path.indexOf( '#' );
        if ( hash >= 0 ) {
            path = path.substring( 0, hash );
        }
        return path.length() > 1 && path.endsWith( "/" )
                ? path.substring( 0, path.length() - 1 ) : path;
    }

    public static ParseResult parse( final String json, final Set< String > allowedEngines,
                                     final Set< String > allowedSites ) {
        try {
            final JsonObject o = JsonParser.parseString( json ).getAsJsonObject();
            final String engine = o.get( "engine" ).getAsString();
            final String site   = o.get( "site" ).getAsString();
            if ( !allowedEngines.contains( engine ) || !allowedSites.contains( site ) ) {
                LOG.warn( "rejecting snapshot for engine={} site={} (not allowlisted)", engine, site );
                return new ParseResult( List.of(), 1 );
            }
            final LocalDate date = LocalDate.parse( o.get( "snapshot_date" ).getAsString() );
            final int window = o.has( "window_days" ) ? o.get( "window_days" ).getAsInt() : 28;

            final List< VisibilityRow > rows = new ArrayList<>();
            collect( rows, o.getAsJsonArray( "by_page" ),  date, window, engine, site, true );
            collect( rows, o.getAsJsonArray( "by_query" ), date, window, engine, site, false );
            return new ParseResult( rows, 0 );
        } catch ( final RuntimeException e ) {
            LOG.warn( "rejecting malformed visibility snapshot: {}", e.getMessage() );
            return new ParseResult( List.of(), 1 );
        }
    }

    private static void collect( final List< VisibilityRow > out, final JsonArray arr,
                                 final LocalDate date, final int window, final String engine,
                                 final String site, final boolean isPage ) {
        if ( arr == null ) {
            return;
        }
        for ( final JsonElement el : arr ) {
            final JsonObject r = el.getAsJsonObject();
            final String key = r.get( "key" ).getAsString();
            out.add( new VisibilityRow( date, window, engine, site,
                    isPage ? normalisePath( key ) : "",
                    isPage ? "" : key,
                    r.get( "impressions" ).getAsInt(),
                    r.get( "clicks" ).getAsInt(),
                    r.has( "position" ) ? r.get( "position" ).getAsDouble() : null ) );
        }
    }
}
```

- [ ] **Step 4: Run the parser test to verify it passes**

Run: `mvn test -pl wikantik-insights -Dtest=SnapshotPayloadParserTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Write the servlet**

`InsightsIngestResource extends RestServletBase`, `doPost`:
1. Reject bodies over `wikantik.insights.ingest.max_bytes` (default 4 MiB) with `413` via `sendError()`.
2. Read the body, call `SnapshotPayloadParser.parse` with the configured engine/site allowlists.
3. `store.upsert(rows)`.
4. Respond `200` with `{"rows_upserted":N,"rows_rejected":M}`.
5. On any failure, `sendError()` — never a raw `response.sendError()` (Global Constraints).

`/admin/*` is already covered by `AdminAuthFilter`, so no new auth code is required.

- [ ] **Step 6: Register the servlet**

In `wikantik-war/src/main/webapp/WEB-INF/web.xml`, beside the other `/admin/*` servlets:

```xml
<servlet>
    <servlet-name>InsightsIngestResource</servlet-name>
    <servlet-class>com.wikantik.rest.InsightsIngestResource</servlet-class>
</servlet>
<servlet-mapping>
    <servlet-name>InsightsIngestResource</servlet-name>
    <url-pattern>/admin/insights/ingest</url-pattern>
</servlet-mapping>
```

- [ ] **Step 7: Verify the module compiles including tests**

Run: `mvn test-compile -pl wikantik-insights,wikantik-rest -q`
Expected: no errors. (`mvn compile` alone skips test sources — see Global Constraints.)

- [ ] **Step 8: Commit**

```bash
git add wikantik-insights wikantik-rest/src/main/java/com/wikantik/rest/InsightsIngestResource.java wikantik-war/src/main/webapp/WEB-INF/web.xml
git commit -m "feat(insights): admin ingest endpoint for visibility snapshots"
```

---

## Task A4: Ingest metrics with the no-page-label invariant

**Files:**
- Modify: `wikantik-insights/src/main/java/com/wikantik/insights/JdbcInsightsStore.java`
- Create: `wikantik-insights/src/test/java/com/wikantik/insights/InsightsMetricsCardinalityTest.java`

- [ ] **Step 1: Write the failing invariant test**

```java
package com.wikantik.insights;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class InsightsMetricsCardinalityTest {

    private static final Set< String > FORBIDDEN = Set.of( "page", "page_path", "query",
                                                           "query_text", "session", "slug" );

    @Test
    void noInsightsMetricCarriesAnUnboundedLabel() {
        final SimpleMeterRegistry reg = new SimpleMeterRegistry();
        final InsightsMetrics metrics = new InsightsMetrics( reg );

        metrics.recordIngest( "bing", "ok", 42 );
        metrics.recordIngest( "google", "error", 0 );

        for ( final Meter m : reg.getMeters() ) {
            for ( final var tag : m.getId().getTags() ) {
                assertFalse( FORBIDDEN.contains( tag.getKey() ),
                        "metric " + m.getId().getName() + " carries unbounded label " + tag.getKey() );
            }
        }
        assertFalse( reg.getMeters().isEmpty(), "metrics must actually be registered" );
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `mvn test -pl wikantik-insights -Dtest=InsightsMetricsCardinalityTest`
Expected: FAIL — `InsightsMetrics` does not exist.

- [ ] **Step 3: Write `InsightsMetrics`**

Register exactly two families, both bounded by engine and outcome:

```java
package com.wikantik.insights;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** Bounded-cardinality ingest health. Never labels by page, query, or session (spec D2). */
public class InsightsMetrics {

    private final MeterRegistry registry;

    public InsightsMetrics( final MeterRegistry registry ) {
        this.registry = registry;
    }

    public void recordIngest( final String engine, final String outcome, final int rows ) {
        Counter.builder( "wikantik.insights.ingest.rows" )
               .tag( "engine", engine ).tag( "outcome", outcome )
               .register( registry ).increment( rows );
        if ( "ok".equals( outcome ) ) {
            registry.gauge( "wikantik.insights.ingest.last_success_timestamp",
                            java.util.List.of( io.micrometer.core.instrument.Tag.of( "engine", engine ) ),
                            System.currentTimeMillis() / 1000.0 );
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -pl wikantik-insights -Dtest=InsightsMetricsCardinalityTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wikantik-insights
git commit -m "feat(insights): bounded-cardinality ingest metrics"
```

---

## Task A5: Admin acquisition panel with engine split

**Files:**
- Create: `wikantik-rest/src/main/java/com/wikantik/rest/InsightsResource.java` (`GET /admin/insights/acquisition?days=28`)
- Create: `wikantik-frontend/src/components/admin/InsightsPanel.jsx`
- Create: `wikantik-frontend/src/components/admin/InsightsPanel.test.jsx`
- Modify: `wikantik-war/src/main/webapp/WEB-INF/web.xml`
- Modify: `wikantik-http/src/main/java/.../SpaRoutingFilter.java` (add `/admin/insights` to `SPA_EXACT`)

- [ ] **Step 1: Write the failing frontend test**

```jsx
import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import InsightsPanel from './InsightsPanel'

const PAYLOAD = {
  engines: [
    { engine: 'google', clicks: 0, impressions: 993, ctr: 0, position: 61.41 },
    { engine: 'bing',   clicks: 4, impressions: 220, ctr: 0.018, position: 5.2 },
  ],
}

describe('InsightsPanel', () => {
  it('renders one row per engine with delivered clicks', async () => {
    vi.stubGlobal('fetch', vi.fn(() =>
      Promise.resolve({ ok: true, json: () => Promise.resolve(PAYLOAD) })))

    render(<InsightsPanel />)

    expect(await screen.findByText('google')).toBeInTheDocument()
    expect(screen.getByText('bing')).toBeInTheDocument()
    expect(screen.getByTestId('clicks-google')).toHaveTextContent('0')
    expect(screen.getByTestId('clicks-bing')).toHaveTextContent('4')
    expect(screen.getByTestId('position-google')).toHaveTextContent('61.4')
  })
})
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `cd wikantik-frontend && npx vitest run src/components/admin/InsightsPanel.test.jsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Write the endpoint and the panel**

`InsightsResource` aggregates the most recent `snapshot_date` per engine:

```sql
SELECT engine, SUM(clicks) clicks, SUM(impressions) impressions,
       AVG(position) position
  FROM search_visibility_snapshot
 WHERE query_text = ''
   AND site_host = ?
   AND snapshot_date = (SELECT MAX(snapshot_date)
                          FROM search_visibility_snapshot
                         WHERE engine = search_visibility_snapshot.engine)
 GROUP BY engine
```

`InsightsPanel.jsx` fetches `/admin/insights/acquisition`, renders one row per engine with
`data-testid={`clicks-${engine}`}` and `data-testid={`position-${engine}`}`. Reuse the shared table
primitives in `src/components/ui/` rather than rolling new ones.

- [ ] **Step 4: Register the SPA route in BOTH places**

`web.xml` servlet-mapping for `/admin/insights`, **and** add `"/admin/insights"` to the `SPA_EXACT`
array in `SpaRoutingFilter.java`. Either alone yields a 404 (Global Constraints).

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd wikantik-frontend && npx vitest run src/components/admin/InsightsPanel.test.jsx`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add wikantik-rest wikantik-frontend wikantik-war wikantik-http
git commit -m "feat(insights): admin acquisition panel with per-engine delivered clicks"
```

---

## Task A6: Full gate

- [ ] **Step 1: Run the canonical suite**

Run: `bin/agent-build.sh start gate -- bin/run-tests.sh --parallel 4`, then poll
`bin/agent-build.sh status gate` until it terminates. Do **not** end the turn waiting — detached
builds are not harness-tracked.
Expected: `SUCCESS exit=0`.

- [ ] **Step 2: Commit any fixes and tag the phase**

```bash
git commit -m "test(insights): phase 0 green on the canonical gate"
```

---

# PART B — jakemon changes

> **Scope note.** Part B adds **one new file and one cron entry**. It does not modify
> `exporter.py`, `opportunities.py`, the providers, the gauges, or the Grafana dashboard. The
> exporter already writes everything needed; the shipper only reads its output tree. If Part B is
> reverted entirely, jakemon behaves exactly as it does today.

## Task B1: `bin/ship-visibility.py` — snapshot shipper

**Files:**
- Create: `jakemon/bin/ship-visibility.py`
- Create: `jakemon/visibility/test_ship.py`
- Modify: `jakemon/bin/validate.sh` (include the new test module in the Python test run)

**Interfaces:**
- Consumes: the snapshot tree at `VIS_DATA_DIR` (`/data/<engine>/<site>/snapshot-YYYY-MM-DD.json`).
- Produces: `POST {WIKANTIK_INSIGHTS_URL}` with `{engine, site, snapshot_date, window_days, by_page, by_query}`.
- Auth: `Authorization: Basic <base64(user:password)>` for `/admin/*`. A `--token` containing a
  colon is treated as Basic; a plain token stays Bearer for the MCP/tools surfaces.

- [ ] **Step 1: Write the failing test**

```python
"""Tests for the snapshot shipper. Stdlib only, matching the repo rule."""
import json, os, tempfile, unittest
import ship_visibility as ship


class ShipTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        p = os.path.join(self.dir, "bing", "wiki.wikantik.com")
        os.makedirs(p)
        with open(os.path.join(p, "snapshot-2026-08-14.json"), "w") as fh:
            json.dump({"engine": "bing", "site": "wiki.wikantik.com",
                       "by_page": [{"key": "https://wiki.wikantik.com/wiki/A",
                                    "clicks": 1, "impressions": 3, "position": 4.0}],
                       "by_query": []}, fh)

    def test_discovers_snapshots_and_stamps_date_from_filename(self):
        found = ship.discover(self.dir, sites={"wiki.wikantik.com"})
        self.assertEqual(1, len(found))
        engine, site, date, payload = found[0]
        self.assertEqual(("bing", "wiki.wikantik.com", "2026-08-14"), (engine, site, date))
        self.assertEqual(1, len(payload["by_page"]))

    def test_skips_sites_not_in_the_allowlist(self):
        self.assertEqual([], ship.discover(self.dir, sites={"other.example"}))

    def test_post_failure_is_reported_not_raised(self):
        def boom(*a, **k):
            raise RuntimeError("HTTP 503: down")
        sent, failed = ship.ship_all(self.dir, {"wiki.wikantik.com"},
                                     "http://wikantik/admin/insights/ingest",
                                     "tok", http=boom)
        self.assertEqual(0, sent)
        self.assertEqual(1, failed)   # must not propagate


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `cd jakemon/visibility && python3 -m unittest test_ship -v`
Expected: FAIL — `ModuleNotFoundError: ship_visibility`.

- [ ] **Step 3: Write the shipper**

```python
#!/usr/bin/env python3
"""Ship jakemon visibility snapshots into Wikantik's insights store.

Reads the snapshot tree the exporter ALREADY writes (VIS_DATA_DIR/<engine>/<site>/
snapshot-YYYY-MM-DD.json) and POSTs each one to Wikantik. Does not touch the exporter,
the gauges, or any exporter state -- if this script never runs, jakemon is unaffected.

Idempotent: Wikantik upserts on (snapshot_date, engine, site, page, query), so
re-shipping a file converges. That makes backfill and the nightly run the same code path.

Stdlib only (repo rule). Usage:
    ship-visibility.py --url URL --token TOK [--since YYYY-MM-DD] [--dry-run]
"""
import argparse
import glob
import json
import os
import sys
import urllib.request

DEFAULT_SITES = ("wiki.wikantik.com", "wikantik.com")


def log(msg):
    print(f"[ship-visibility] {msg}", file=sys.stderr, flush=True)


def _post(url, token, payload):
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url, data=data, method="POST",
        headers={"Content-Type": "application/json",
                 "Authorization": f"Bearer {token}"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8") or "{}")


def discover(data_dir, sites, since=None):
    """Yield (engine, site, date_str, payload) for each snapshot of an allowlisted site."""
    out = []
    for path in sorted(glob.glob(os.path.join(data_dir, "*", "*", "snapshot-*.json"))):
        site = os.path.basename(os.path.dirname(path))
        engine = os.path.basename(os.path.dirname(os.path.dirname(path)))
        if site not in sites:
            continue
        date = os.path.basename(path)[len("snapshot-"):-len(".json")]
        if since and date < since:
            continue
        try:
            with open(path, encoding="utf-8") as fh:
                out.append((engine, site, date, json.load(fh)))
        except (OSError, ValueError) as exc:
            log(f"skipping unreadable {path}: {exc}")
    return out


def ship_all(data_dir, sites, url, token, since=None, window_days=28, http=_post):
    """Ship every discovered snapshot. Returns (sent, failed). Never raises."""
    sent = failed = 0
    for engine, site, date, payload in discover(data_dir, sites, since):
        body = {"engine": engine, "site": site, "snapshot_date": date,
                "window_days": window_days,
                "by_page": payload.get("by_page", []),
                "by_query": payload.get("by_query", [])}
        try:
            http(url, token, body)
            sent += 1
        except Exception as exc:                       # noqa: BLE001
            failed += 1
            log(f"{engine}/{site}/{date}: ship failed: {exc}")
    return sent, failed


def main(argv=None):
    ap = argparse.ArgumentParser(prog="ship-visibility",
                                 description=__doc__.splitlines()[0])
    ap.add_argument("--url", default=os.environ.get("WIKANTIK_INSIGHTS_URL"),
                    help="Wikantik ingest endpoint")
    ap.add_argument("--token", default=os.environ.get("WIKANTIK_INSIGHTS_TOKEN"),
                    help="admin bearer token")
    ap.add_argument("--data-dir", default=os.environ.get("VIS_DATA_DIR", "/data"))
    ap.add_argument("--since", help="only ship snapshots on/after this YYYY-MM-DD")
    ap.add_argument("--sites", default=",".join(DEFAULT_SITES))
    ap.add_argument("--dry-run", action="store_true", help="list what would be shipped")
    args = ap.parse_args(argv)

    sites = {s.strip() for s in args.sites.split(",") if s.strip()}
    if args.dry_run:
        found = discover(args.data_dir, sites, args.since)
        for engine, site, date, _ in found:
            log(f"would ship {engine}/{site}/{date}")
        log(f"{len(found)} snapshot(s)")
        return 0
    if not args.url or not args.token:
        log("--url and --token (or WIKANTIK_INSIGHTS_URL/_TOKEN) are required")
        return 2

    sent, failed = ship_all(args.data_dir, sites, args.url, args.token, args.since)
    log(f"shipped {sent}, failed {failed}")
    return 1 if failed and not sent else 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd jakemon/visibility && python3 -m unittest test_ship -v`
Expected: PASS (3 tests).

- [ ] **Step 5: Run the repo gate**

Run: `cd jakemon && bin/validate.sh`
Expected: clean. The new file is Python, not bash, so `shellcheck` does not apply to it; confirm
`validate.sh` picks up `test_ship.py` in its Python test discovery and add it if not.

- [ ] **Step 6: Commit**

```bash
cd jakemon
git add bin/ship-visibility.py visibility/test_ship.py bin/validate.sh
git commit -m "feat(visibility): ship snapshots to Wikantik insights store"
```

---

## Task B2: Backfill and schedule

**Backfill runs against the local deployment first, then docker1.** This is the first exercise of
the whole write path end to end, and the ingest endpoint has never seen a real payload. Local is
disposable; production is not. The upsert is idempotent, so running local first costs nothing and
the same command is later re-pointed at docker1.

- [ ] **Step 1: Dry-run against the live snapshot tree (read-only, safe anywhere)**

```bash
ssh docker2 'docker exec $(docker ps -qf name=visib) \
  python3 /opt/jakemon/bin/ship-visibility.py --dry-run'
```
Expected: **430** snapshots for the two wikantik sites — bing 144, google 144, yandex 142 —
oldest `2026-06-04`, newest `2026-08-14`. (Verified 2026-08-16.)

- [ ] **Step 2: Bring the local deployment up with the new endpoint**

```bash
mvn clean install -DskipTests -T 1C     # WAR must include the new web.xml + servlet
bin/deploy-local.sh                     # applies V050 and re-renders config
tomcat/tomcat-11/bin/startup.sh
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/   # expect 200
```

- [ ] **Step 3: Backfill LOCAL and verify before touching production**

Ship from docker2 to the local instance. The snapshots live in the container on docker2, so pull
them to this machine first (read-only), then ship from here:

```bash
ssh docker2 'docker exec $(docker ps -qf name=visib) tar cf - /data' | tar xf - -C /tmp/vis-snapshots
source <(grep -v '^#' test.properties | sed 's/^test.user.//' | sed 's/=/="/' | sed 's/$/"/')
python3 /home/jakefear/source/jakemon/bin/ship-visibility.py \
    --data-dir /tmp/vis-snapshots/data \
    --url http://localhost:8080/admin/insights/ingest \
    --token "$password"
```
Expected: `shipped 430, failed 0`.

- [ ] **Step 4: Verify the local rows, then re-run to prove idempotency**

```bash
PGPASSWORD=… psql -h localhost -U wikantik -d wikantik -c \
  "SELECT engine, COUNT(*) rows, COUNT(DISTINCT snapshot_date) days,
          MIN(snapshot_date), MAX(snapshot_date)
     FROM search_visibility_snapshot GROUP BY engine ORDER BY engine"
```
Record the row count, re-run Step 3 unchanged, and re-run this query. **The counts must be
identical.** A second run that grows the table means the upsert key is wrong and the backfill must
not go near production.

- [ ] **Step 5: Only once Step 4 is clean — backfill docker1**

```bash
ssh docker2 'docker exec -e WIKANTIK_INSIGHTS_TOKEN=… $(docker ps -qf name=visib) \
  python3 /opt/jakemon/bin/ship-visibility.py \
    --url http://docker1:8080/admin/insights/ingest'
```
Expected: `shipped 430, failed 0`.

- [ ] **Step 6: Verify ten weeks of history landed on production**

```bash
PGPASSWORD=… psql -h docker1 -U wikantik -d wikantik -c \
  "SELECT engine, COUNT(DISTINCT snapshot_date) days, MIN(snapshot_date), MAX(snapshot_date),
          SUM(clicks) FILTER (WHERE query_text='') clicks
     FROM search_visibility_snapshot GROUP BY engine ORDER BY engine"
```
Expected: three engines, ~72 distinct dates each, `MIN` = 2026-06-04. Bing shows non-zero clicks;
Google shows zero. **This query is the acceptance criterion for the whole phase** — it is the
question §1.2 says is currently unanswerable.

- [ ] **Step 7: Schedule the nightly run**

Add a cron entry on docker2 running the shipper daily at 05:30 with `--since` set to 7 days back
(bounded work, and the upsert makes overlap free). Follow the existing BusyBox crond convention:
stage the crontab root-owned `0600` in the entrypoint rather than bind-mounting a group-writable
file, or crond silently refuses it.

- [ ] **Step 8: Commit**

```bash
cd jakemon
git add <cron/compose files touched>
git commit -m "chore(visibility): nightly snapshot ship to Wikantik"
```

---

## Self-review checklist

- [ ] `exporter.py`, `opportunities.py`, `providers/`, and the Grafana dashboard are untouched.
- [ ] Every task ends with a passing test and a commit.
- [ ] `VisibilityRow` field names match between A2, A3, and B1's payload keys.
- [ ] The endpoint path `/admin/insights/ingest` is identical in A3 (web.xml), A3 (servlet), and B2.
- [ ] No Prometheus label carries a page or query (A4 enforces it).
- [ ] `/admin/insights` is registered in `web.xml` **and** `SPA_EXACT` (A5).
