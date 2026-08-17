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
package com.wikantik.rest;

import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.NoRequiredPropertyException;
import com.wikantik.auth.AbstractJDBCDatabase;
import com.wikantik.auth.JndiDataSources;
import com.wikantik.insights.ExpectedCtrCurveParser;
import com.wikantik.insights.ImportedOpportunityParser;
import com.wikantik.insights.InsightsStore;
import com.wikantik.insights.JdbcInsightsStore;
import com.wikantik.insights.SnapshotPayloadParser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Admin ingest endpoint for jakemon's search-visibility snapshots.
 *
 * <p>Mapped to {@code POST /admin/insights/ingest} (gated by {@code AdminAuthFilter} like every
 * other {@code /admin/*} servlet — no auth logic lives here). Accepts one jakemon visibility
 * snapshot JSON document per request, parses it via {@link SnapshotPayloadParser}, and upserts
 * the resulting rows into {@code search_visibility_snapshot} via {@link InsightsStore}.</p>
 *
 * <p>The same document may also carry an optional top-level {@code opportunities} array —
 * jakemon's five imported detector types (content-intelligence design §7.3 "Imported from
 * jakemon", §12.1 item J3), parsed via {@link ImportedOpportunityParser} and upserted into
 * {@code imported_opportunity}. The response gains {@code opportunities_upserted}/
 * {@code opportunities_rejected} only when that key is present, so the response shape stays
 * byte-identical for every request that predates jakemon shipping it.</p>
 *
 * <p>It may also carry an optional top-level {@code expected_ctr} object — jakemon's real,
 * measured {@code EXPECTED_CTR} curve (design §7.3 rule 2, V057), parsed via
 * {@link ExpectedCtrCurveParser} and upserted into {@code expected_ctr_curve}. Same byte-identical-
 * response-until-shipped rule: {@code expected_ctr_upserted}/{@code expected_ctr_rejected} are
 * added only when the key is present.</p>
 *
 * <p>Body is capped at {@link #PROP_MAX_BYTES} (default 4 MiB) so an oversized or runaway
 * shipper request cannot exhaust server memory; the cap is enforced by bounding the read itself
 * rather than trusting a {@code Content-Length} header. The engine/site allowlists come from
 * {@link #PROP_ENGINES} / {@link #PROP_SITES} — the site allowlist defaults to {@code *}
 * (accept every site jakemon polls; see the content-intelligence design doc D10) while the
 * engine allowlist defaults to the three engines jakemon currently ships.</p>
 */
public class InsightsIngestResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( InsightsIngestResource.class );

    static final String PROP_MAX_BYTES = "wikantik.insights.ingest.max_bytes";
    static final int DEFAULT_MAX_BYTES = 4 * 1024 * 1024;

    static final String PROP_ENGINES = "wikantik.insights.ingest.engines";
    static final String DEFAULT_ENGINES = "google,bing,yandex";

    static final String PROP_SITES = "wikantik.insights.ingest.sites";
    static final String DEFAULT_SITES = "*";

    /** Admin-only surface — no cross-origin access. */
    @Override
    protected boolean isCrossOriginAllowed() {
        return false;
    }

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {

        final int maxBytes = configuredInt( PROP_MAX_BYTES, DEFAULT_MAX_BYTES );
        final String body = readBodyBounded( request, maxBytes );
        if ( body == null ) {
            sendError( response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                    "Snapshot body exceeds the " + maxBytes + "-byte limit (configurable via "
                            + PROP_MAX_BYTES + ")." );
            return;
        }

        try {
            final Set< String > engines = configuredSet( PROP_ENGINES, DEFAULT_ENGINES );
            final Set< String > sites   = configuredSet( PROP_SITES, DEFAULT_SITES );

            final SnapshotPayloadParser.ParseResult parsed =
                    SnapshotPayloadParser.parse( body, engines, sites );
            final ImportedOpportunityParser.ParseResult importedParsed =
                    ImportedOpportunityParser.parse( body, engines, sites );
            final ExpectedCtrCurveParser.ParseResult ctrParsed = ExpectedCtrCurveParser.parse( body );

            final InsightsStore store = buildStore();
            if ( store == null ) {
                sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "Search-visibility store is not available (no configured datasource)." );
                return;
            }

            final int upserted = store.upsert( parsed.rows() );

            final Map<String, Object> responseBody = new LinkedHashMap<>();
            responseBody.put( "rows_upserted", upserted );
            responseBody.put( "rows_rejected", parsed.rejected() );

            // Only add these keys when the payload actually carried an `opportunities` array
            // (content-intelligence design §12.1 item J3) -- until jakemon ships it, the response
            // shape must stay byte-identical to what it was before this feature existed; this is
            // a live endpoint fed nightly by a real shipper.
            if ( importedParsed.present() ) {
                final int opportunitiesUpserted = store.upsertImported( importedParsed.rows() );
                responseBody.put( "opportunities_upserted", opportunitiesUpserted );
                responseBody.put( "opportunities_rejected", importedParsed.rejected() );
            }

            // Same byte-identical-until-shipped rule as opportunities above (content-intelligence
            // design §7.3 rule 2, V057) -- jakemon's real EXPECTED_CTR curve.
            if ( ctrParsed.present() ) {
                final int ctrUpserted = store.upsertCtrCurve( ctrParsed.asOf(), ctrParsed.points() );
                responseBody.put( "expected_ctr_upserted", ctrUpserted );
                responseBody.put( "expected_ctr_rejected", ctrParsed.rejected() );
            }

            sendJson( response, responseBody );
        } catch ( final RuntimeException e ) {
            LOG.warn( "visibility snapshot ingest failed: {}", e.getMessage(), e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Failed to ingest visibility snapshot." );
        }
    }

    /**
     * Resolves the configured JNDI DataSource and wraps it in a {@link JdbcInsightsStore}.
     * Package-private (not cached on the instance) so tests can override it to inject a mock
     * store without a live JNDI environment.
     *
     * @return a fresh store, or {@code null} if no datasource is configured/resolvable
     */
    InsightsStore buildStore() {
        final Engine engine = getEngine();
        if ( engine == null ) {
            return null;
        }
        final String datasourceName = engine.getWikiProperties().getProperty(
                AbstractJDBCDatabase.PROP_DATASOURCE, AbstractJDBCDatabase.DEFAULT_DATASOURCE );
        try {
            final DataSource ds = JndiDataSources.lookup( datasourceName,
                    "InsightsIngestResource", AbstractJDBCDatabase.PROP_DATASOURCE );
            return new JdbcInsightsStore( ds );
        } catch ( final NoRequiredPropertyException e ) {
            LOG.warn( "no JNDI datasource resolved for search-visibility ingest ({}): {}",
                    datasourceName, e.getMessage() );
            return null;
        }
    }

    /**
     * Reads the request body up to {@code maxBytes + 1} bytes and decodes it as UTF-8.
     * Bounding the read itself (rather than trusting {@code Content-Length}, which may be
     * absent or understated for a chunked request) means an oversized body is detected
     * without ever buffering more than {@code maxBytes + 1} bytes.
     *
     * @return the body text, or {@code null} if it exceeds {@code maxBytes}
     */
    private String readBodyBounded( final HttpServletRequest request, final int maxBytes ) throws IOException {
        try ( InputStream in = request.getInputStream() ) {
            final byte[] buf = in.readNBytes( maxBytes + 1 );
            if ( buf.length > maxBytes ) {
                return null;
            }
            return new String( buf, StandardCharsets.UTF_8 );
        }
    }

    private int configuredInt( final String key, final int def ) {
        final Engine engine = getEngine();
        if ( engine == null ) {
            return def;
        }
        final String raw = engine.getWikiProperties().getProperty( key );
        if ( raw == null || raw.isBlank() ) {
            return def;
        }
        try {
            final int v = Integer.parseInt( raw.trim() );
            return v > 0 ? v : def;
        } catch ( final NumberFormatException nfe ) {
            return def;
        }
    }

    private Set< String > configuredSet( final String key, final String def ) {
        final Engine engine = getEngine();
        final Properties props = engine == null ? null : engine.getWikiProperties();
        final String raw = props == null ? def : props.getProperty( key, def );
        return Arrays.stream( raw.split( "," ) )
                .map( String::trim )
                .filter( s -> !s.isEmpty() )
                .collect( Collectors.toUnmodifiableSet() );
    }
}
