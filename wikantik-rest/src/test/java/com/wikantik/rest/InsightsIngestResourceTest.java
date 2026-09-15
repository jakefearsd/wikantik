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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.api.core.Engine;
import com.wikantik.insights.InsightsStore;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InsightsIngestResource}: the allowlist-parsing helper (pre-existing) plus
 * the {@code doPost} ingest path, injecting a mock {@link InsightsStore} through the
 * package-private {@link InsightsIngestResource#buildStore()} seam.
 */
class InsightsIngestResourceTest {

    @Test
    void resolveAllowlistTreatsBlankValueAsAbsent() {
        assertEquals( Set.of( "*" ), InsightsIngestResource.resolveAllowlist( "", "*" ) );
    }

    @Test
    void resolveAllowlistTreatsNullValueAsAbsent() {
        assertEquals( Set.of( "*" ), InsightsIngestResource.resolveAllowlist( null, "*" ) );
    }

    @Test
    void resolveAllowlistParsesCommaSeparatedValueTrimmingEntries() {
        assertEquals( Set.of( "google", "bing" ), InsightsIngestResource.resolveAllowlist( "google, bing", "*" ) );
    }

    @Test
    void resolveAllowlistUsesTheNonWildcardDefaultWhenAbsent() {
        assertEquals( Set.of( "google", "bing", "yandex" ),
            InsightsIngestResource.resolveAllowlist( null, "google,bing,yandex" ) );
    }

    // -------------------------------------------------------------------------
    // doPost
    // -------------------------------------------------------------------------

    /** Minimal {@link ServletInputStream} over a fixed body, mirroring OntologySparqlResourceTest. */
    private static final class FixedInputStream extends ServletInputStream {
        private final ByteArrayInputStream delegate;
        FixedInputStream( final byte[] data ) { this.delegate = new ByteArrayInputStream( data ); }
        @Override public boolean isFinished() { return delegate.available() == 0; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener( final ReadListener listener ) { /* not used */ }
        @Override public int read() { return delegate.read(); }
    }

    private static final class TestableInsightsIngestResource extends InsightsIngestResource {
        private final InsightsStore fixedStore;
        TestableInsightsIngestResource( final InsightsStore fixedStore ) { this.fixedStore = fixedStore; }
        @Override InsightsStore buildStore() { return fixedStore; }
    }

    private Engine engine;
    private InsightsStore store;
    private InsightsIngestResource resource;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private StringWriter buf;
    private Properties wikiProperties;

    @BeforeEach
    void setUp() throws Exception {
        wikiProperties = new Properties();
        engine = mock( Engine.class );
        when( engine.getWikiProperties() ).thenReturn( wikiProperties );

        store = mock( InsightsStore.class );
        resource = new TestableInsightsIngestResource( store );
        resource.setEngine( engine );

        req = mock( HttpServletRequest.class );
        resp = mock( HttpServletResponse.class );
        buf = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( buf ) );
    }

    private JsonObject body() {
        return JsonParser.parseString( buf.toString() ).getAsJsonObject();
    }

    private void withBody( final String json ) throws Exception {
        when( req.getInputStream() ).thenReturn( new FixedInputStream( json.getBytes( StandardCharsets.UTF_8 ) ) );
    }

    private static String minimalSnapshot() {
        return "{\"engine\":\"google\",\"site\":\"wiki.wikantik.com\",\"snapshot_date\":\"2026-08-01\"}";
    }

    // -------------------------------------------------------------------------

    @Test
    void doPost_ingestsMinimalSnapshotWithoutOptionalKeys() throws Exception {
        withBody( minimalSnapshot() );
        when( store.upsert( any() ) ).thenReturn( 0 );

        resource.doPost( req, resp );

        final JsonObject b = body();
        assertEquals( 0, b.get( "rows_upserted" ).getAsInt() );
        assertEquals( 0, b.get( "rows_rejected" ).getAsInt() );
        // Byte-identical-until-shipped: neither opportunities nor expected_ctr keys appear.
        assertFalse( b.has( "opportunities_upserted" ) );
        assertFalse( b.has( "expected_ctr_upserted" ) );
    }

    @Test
    void doPost_rejectsMalformedJsonBodyButDoesNotThrow() throws Exception {
        withBody( "{ this is not json" );
        when( store.upsert( any() ) ).thenReturn( 0 );

        resource.doPost( req, resp );

        final JsonObject b = body();
        assertEquals( 0, b.get( "rows_upserted" ).getAsInt() );
        assertEquals( 1, b.get( "rows_rejected" ).getAsInt() );
    }

    @Test
    void doPost_rejectsSnapshotForDisallowedEngine() throws Exception {
        wikiProperties.setProperty( InsightsIngestResource.PROP_ENGINES, "bing" );
        withBody( minimalSnapshot() ); // engine=google, not on the "bing"-only allowlist
        when( store.upsert( any() ) ).thenReturn( 0 );

        resource.doPost( req, resp );

        final JsonObject b = body();
        assertEquals( 0, b.get( "rows_upserted" ).getAsInt() );
        assertEquals( 1, b.get( "rows_rejected" ).getAsInt() );
    }

    @Test
    void doPost_includesOpportunityCountsWhenPayloadCarriesThem() throws Exception {
        final String json = "{\"engine\":\"google\",\"site\":\"wiki.wikantik.com\","
                + "\"snapshot_date\":\"2026-08-01\",\"opportunities\":[]}";
        withBody( json );
        when( store.upsert( any() ) ).thenReturn( 0 );
        when( store.upsertImported( any() ) ).thenReturn( 0 );

        resource.doPost( req, resp );

        final JsonObject b = body();
        assertTrue( b.has( "opportunities_upserted" ) );
        assertTrue( b.has( "opportunities_rejected" ) );
        assertFalse( b.has( "expected_ctr_upserted" ) );
    }

    @Test
    void doPost_includesExpectedCtrCountsWhenPayloadCarriesThem() throws Exception {
        // ExpectedCtrCurveParser stamps asOf from the payload's top-level snapshot_date, not
        // from a field inside "expected_ctr" -- the curve applies to the whole ingest document.
        final String json = "{\"engine\":\"google\",\"site\":\"wiki.wikantik.com\","
                + "\"snapshot_date\":\"2026-08-01\","
                + "\"expected_ctr\":{\"1\":0.3,\"2\":0.15}}";
        withBody( json );
        when( store.upsert( any() ) ).thenReturn( 0 );
        when( store.upsertCtrCurve( any( LocalDate.class ), any() ) ).thenReturn( 2 );

        resource.doPost( req, resp );

        final JsonObject b = body();
        assertEquals( 2, b.get( "expected_ctr_upserted" ).getAsInt() );
        assertTrue( b.has( "expected_ctr_rejected" ) );
        assertFalse( b.has( "opportunities_upserted" ) );
        verify( store ).upsertCtrCurve( LocalDate.of( 2026, 8, 1 ), Map.of( 1, 0.3, 2, 0.15 ) );
    }

    @Test
    void doPost_returns413WhenBodyExceedsConfiguredMaxBytes() throws Exception {
        wikiProperties.setProperty( InsightsIngestResource.PROP_MAX_BYTES, "10" );
        withBody( minimalSnapshot() ); // well over 10 bytes

        resource.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE );
        assertTrue( body().get( "message" ).getAsString().contains( "10-byte limit" ) );
        verify( store, never() ).upsert( any() );
    }

    @Test
    void doPost_returns503WhenStoreUnavailable() throws Exception {
        withBody( minimalSnapshot() );
        resource = new TestableInsightsIngestResource( null );
        resource.setEngine( engine );

        resource.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
        assertTrue( body().get( "message" ).getAsString().toLowerCase().contains( "not available" ) );
    }

    @Test
    void doPost_returns500WhenStoreThrows() throws Exception {
        withBody( minimalSnapshot() );
        when( store.upsert( any() ) ).thenThrow( new RuntimeException( "boom" ) );

        resource.doPost( req, resp );

        verify( resp ).setStatus( HttpServletResponse.SC_INTERNAL_SERVER_ERROR );
        assertFalse( body().get( "message" ).getAsString().isBlank() );
    }

    @Test
    void isCrossOriginAllowed_isFalse() {
        assertFalse( new InsightsIngestResource().isCrossOriginAllowed() );
    }

    // -------------------------------------------------------------------------
    // Config-reading fallbacks + the real (non-overridden) buildStore() seam
    // -------------------------------------------------------------------------

    @Test
    void doPost_usesDefaultsWhenEngineIsNotSet() throws Exception {
        // No engine at all (as in a servlet never through init()) -- configuredInt/configuredSet
        // must fall back to their defaults rather than NPE.
        resource = new TestableInsightsIngestResource( store );
        withBody( minimalSnapshot() );
        when( store.upsert( any() ) ).thenReturn( 1 );

        resource.doPost( req, resp );

        final JsonObject b = body();
        assertEquals( 1, b.get( "rows_upserted" ).getAsInt() );
        assertEquals( 0, b.get( "rows_rejected" ).getAsInt() );
    }

    @Test
    void doPost_fallsBackToDefaultMaxBytesOnNonIntegerProperty() throws Exception {
        wikiProperties.setProperty( InsightsIngestResource.PROP_MAX_BYTES, "not-a-number" );
        withBody( minimalSnapshot() );
        when( store.upsert( any() ) ).thenReturn( 1 );

        resource.doPost( req, resp );

        // Falls back to DEFAULT_MAX_BYTES (4 MiB) rather than rejecting the request as too large.
        final JsonObject b = body();
        assertEquals( 1, b.get( "rows_upserted" ).getAsInt() );
    }

    @Test
    void buildStore_returnsNullWhenEngineNotSet() {
        // The real (non-overridden) production seam -- no test double involved.
        final InsightsIngestResource real = new InsightsIngestResource();
        assertNull( real.buildStore() );
    }

    @Test
    void buildStore_returnsNullWhenJndiLookupFails() {
        // Outside a servlet container there is no java:comp/env InitialContext, so the lookup
        // fails with a NamingException that buildStore() must translate into a null store rather
        // than letting it escape.
        final InsightsIngestResource real = new InsightsIngestResource();
        real.setEngine( engine );
        assertNull( real.buildStore() );
    }
}
