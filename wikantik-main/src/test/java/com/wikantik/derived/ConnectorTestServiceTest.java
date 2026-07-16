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
package com.wikantik.derived;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.api.connectors.CredentialStore;
import com.wikantik.api.connectors.SourceConnector;
import com.wikantik.api.connectors.SourceItem;
import com.wikantik.api.connectors.SyncBatch;
import com.wikantik.api.connectors.SyncCursor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link ConnectorTestService}. Uses a record-style stub {@link SourceConnector}
 *  and the package-visible {@code testUnsaved} overload (assembler injection) so no real
 *  connector/network is ever built. */
class ConnectorTestServiceTest {

    /** Minimal valid webcrawler config — {@link com.wikantik.connectors.config.ConnectorConfigCodec#toConfigForTest}
     *  runs full validation before the stub assembler is ever consulted, so every test needs a
     *  config that passes; the connector TYPE and its fields are otherwise irrelevant since the
     *  injected assembler ignores them and returns the canned stub regardless. */
    private static JsonObject validWebCrawlerConfig() {
        final JsonObject config = new JsonObject();
        final JsonArray seeds = new JsonArray();
        seeds.add( "https://example.com" );
        config.add( "seeds", seeds );
        return config;
    }

    private static SourceItem item( final String uri ) {
        return new SourceItem( uri, new byte[ 0 ], "text/plain", Map.of(), List.of(), "hash-" + uri );
    }

    /** Record-style fake {@link SourceConnector} returning a canned {@link SyncBatch} from every poll. */
    private record StubConnector( String connectorId, SyncBatch batch ) implements SourceConnector {
        @Override public SyncBatch poll( final SyncCursor cursor ) { return batch; }
    }

    private static final CredentialStore NO_STORE = null;

    @Test void unreachableSourceFails() {
        final SyncBatch batch = new SyncBatch( List.of(), List.of(), null, false );
        final StubConnector stub = new StubConnector( "probe", batch );

        final ConnectorTestService.TestResult result = ConnectorTestService.testUnsaved(
            "probe-id", "webcrawler", validWebCrawlerConfig(), Map.of(), NO_STORE,
            ( cfg, store ) -> Optional.of( stub ) );

        assertFalse( result.ok() );
        assertEquals( 0, result.found() );
        assertTrue( result.sample().isEmpty() );
        assertFalse( result.complete() );
        assertEquals( "source unreachable or not authorized — no items returned", result.message() );
    }

    // Pins the end-to-end judgment for the fixed FeedSourceConnector shape: when every configured
    // feed URL fails to fetch, the connector now returns an empty, incomplete batch carrying the
    // (non-null, resumed) input cursor — this must still read as "unreachable", not "reachable,
    // found 0" (the defect this whole fix addresses).
    @Test void feedAllUrlsUnreachableProbeReportsUnreachable() {
        final SyncCursor inputCursor = new SyncCursor( "resume-42" );
        final SyncBatch batch = new SyncBatch( List.of(), List.of(), inputCursor, false );
        final StubConnector stub = new StubConnector( "feed1", batch );

        final ConnectorTestService.TestResult result = ConnectorTestService.testUnsaved(
            "probe-id", "webcrawler", validWebCrawlerConfig(), Map.of(), NO_STORE,
            ( cfg, store ) -> Optional.of( stub ) );

        assertFalse( result.ok() );
        assertEquals( 0, result.found() );
        assertFalse( result.complete() );
        assertEquals( "source unreachable or not authorized — no items returned", result.message() );
    }

    @Test void reachableSourceReportsSample() {
        final List< SourceItem > items = List.of(
            item( "uri1" ), item( "uri2" ), item( "uri3" ), item( "uri4" ), item( "uri5" ) );
        final SyncBatch batch = new SyncBatch( items, List.of(), null, true );
        final StubConnector stub = new StubConnector( "probe", batch );

        final ConnectorTestService.TestResult result = ConnectorTestService.testUnsaved(
            "probe-id", "webcrawler", validWebCrawlerConfig(), Map.of(), NO_STORE,
            ( cfg, store ) -> Optional.of( stub ) );

        assertTrue( result.ok() );
        assertEquals( 5, result.found() );
        assertEquals( List.of( "uri1", "uri2", "uri3" ), result.sample() );
        assertTrue( result.complete() );
        assertEquals( "reachable — found 5 item(s) in a capped probe", result.message() );
    }

    @Test void transientCredentialOverlays() {
        final AtomicBoolean realStoreTouched = new AtomicBoolean( false );
        final CredentialStore realStore = new CredentialStore() {
            @Override public boolean enabled() { return true; }
            @Override public void put( final String connectorId, final String name, final String secret ) {}
            @Override public Optional< String > get( final String connectorId, final String name ) {
                realStoreTouched.set( true );
                return Optional.of( "real-value" );
            }
            @Override public List< String > list( final String connectorId ) { return List.of(); }
            @Override public void delete( final String connectorId, final String name ) {}
        };
        final SyncBatch batch = new SyncBatch( List.of(), List.of(), null, true );
        final StubConnector stub = new StubConnector( "probe", batch );
        final AtomicReference< CredentialStore > captured = new AtomicReference<>();

        ConnectorTestService.testUnsaved( "probe-id", "webcrawler", validWebCrawlerConfig(),
            Map.of( "token", "transient-tok" ), realStore,
            ( cfg, store ) -> { captured.set( store ); return Optional.of( stub ); } );

        final CredentialStore overlay = captured.get();
        // Overlay must match on credential NAME regardless of connector id — the same as
        // ConnectorAssembler's per-connector-id supplier lambdas would look up.
        assertEquals( Optional.of( "transient-tok" ), overlay.get( "probe-id", "token" ) );
        assertEquals( Optional.of( "transient-tok" ), overlay.get( "some-other-connector-id", "token" ) );
        assertFalse( realStoreTouched.get() );
    }

    @Test void partialButNonEmptyBatchIsOk() {
        // The one deliberately non-obvious branch: complete=false alone does NOT mean failure —
        // only items.isEmpty() && !complete does. A partial-but-useful poll (e.g. paginated source
        // stopped mid-page for a legitimate reason) still reports ok=true.
        final List< SourceItem > items = List.of( item( "uri1" ), item( "uri2" ) );
        final SyncBatch batch = new SyncBatch( items, List.of(), null, false );
        final StubConnector stub = new StubConnector( "probe", batch );

        final ConnectorTestService.TestResult result = ConnectorTestService.testUnsaved(
            "probe-id", "webcrawler", validWebCrawlerConfig(), Map.of(), NO_STORE,
            ( cfg, store ) -> Optional.of( stub ) );

        assertTrue( result.ok() );
        assertEquals( 2, result.found() );
        assertEquals( List.of( "uri1", "uri2" ), result.sample() );
        assertFalse( result.complete() );
    }

    @Test void emptyButCompleteIsOkZero() {
        final SyncBatch batch = new SyncBatch( List.of(), List.of(), null, true );
        final StubConnector stub = new StubConnector( "probe", batch );

        final ConnectorTestService.TestResult result = ConnectorTestService.testUnsaved(
            "probe-id", "webcrawler", validWebCrawlerConfig(), Map.of(), NO_STORE,
            ( cfg, store ) -> Optional.of( stub ) );

        assertTrue( result.ok() );
        assertEquals( 0, result.found() );
        assertTrue( result.sample().isEmpty() );
        assertTrue( result.complete() );
    }

    @Test void testSavedAppliesSameJudgmentToALiveConnector() {
        final List< SourceItem > items = List.of( item( "a" ), item( "b" ) );
        final StubConnector stub = new StubConnector( "live1", new SyncBatch( items, List.of(), null, true ) );

        final ConnectorTestService.TestResult result = ConnectorTestService.testSaved( stub );

        assertTrue( result.ok() );
        assertEquals( 2, result.found() );
        assertEquals( List.of( "a", "b" ), result.sample() );
    }

    @Test void pollThrowingIsCaughtAndDegradesToFailure() {
        final SourceConnector throwing = new SourceConnector() {
            @Override public String connectorId() { return "throws1"; }
            @Override public SyncBatch poll( final SyncCursor cursor ) { throw new RuntimeException( "boom" ); }
        };

        final ConnectorTestService.TestResult result = ConnectorTestService.testSaved( throwing );

        assertFalse( result.ok() );
        assertEquals( 0, result.found() );
        assertFalse( result.message().contains( "boom" ) );   // no stack trace / exception detail leaked
    }
}
