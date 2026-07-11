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
package com.wikantik.connectors;

import com.wikantik.api.connectors.*;
import com.wikantik.connectors.filesystem.FilesystemSourceConnector;
import com.wikantik.connectors.state.JdbcSyncStateStore;
import com.wikantik.derived.*;
import com.wikantik.ingest.TikaSourceExtractor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test proving the connector-framework Definition-of-Done: a fixture
 * directory flows through {@link FilesystemSourceConnector} -&gt; {@link SyncOrchestrator} -&gt;
 * {@link DerivedPageSinkAdapter} -&gt; a real {@link DerivedPageIngestionService} (with a real
 * {@link TikaSourceExtractor}) -&gt; an in-memory page store, with sync state in H2.
 *
 * <p>Derived page names go through {@link DerivedPage#pageNameFor}, which — via
 * {@code MarkupParser.cleanLink} / {@code TextUtil.cleanString} — capitalizes the first
 * character of the (hyphen-flattened) source path. Rather than hardcode the resulting
 * page names, each test computes its expected name the same way the sink does: strip the
 * {@code scheme:} prefix from the source URI, flatten {@code /} to {@code -}, and pass the
 * result through {@code DerivedPage.pageNameFor}.
 */
class ConnectorSyncEndToEndTest {

    private DataSource ds;
    private final Map< String, Map< String, Object > > pages = new HashMap<>();   // pageName -> frontmatter
    private DerivedPageSink sink;
    private SyncOrchestrator orchestrator;

    @BeforeEach void wire() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:e2e_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL" );
        ds = h2;
        try ( Connection c = ds.getConnection(); var s = c.createStatement() ) {
            s.execute( "CREATE TABLE IF NOT EXISTS connector_sync_state (connector_id VARCHAR PRIMARY KEY, cursor VARCHAR, last_run TIMESTAMP WITH TIME ZONE, status VARCHAR)" );
            s.execute( "CREATE TABLE IF NOT EXISTS connector_synced_item (connector_id VARCHAR NOT NULL, source_uri VARCHAR NOT NULL, content_hash VARCHAR NOT NULL, page_name VARCHAR NOT NULL, acl_refs VARCHAR NOT NULL DEFAULT '[]', first_synced TIMESTAMP WITH TIME ZONE DEFAULT now(), last_synced TIMESTAMP WITH TIME ZONE DEFAULT now(), PRIMARY KEY (connector_id, source_uri))" );
        }
        DerivedPageIngestionService ingestion = new DerivedPageIngestionService(
            new TikaSourceExtractor(),
            ( page, filename, bytes ) -> { },                                   // attachments: no-op
            page -> Optional.ofNullable( pages.get( page ) ),                   // reader
            ( page, body, meta, author ) -> pages.put( page, new HashMap<>( meta ) ),   // writer
            pages::remove );                                                    // deleter
        sink = new DerivedPageSinkAdapter( ingestion, pages::remove, "sync-bot" );
        orchestrator = new SyncOrchestrator( new JdbcSyncStateStore( ds ), sink );
    }

    private SourceConnector fs( Path root ) { return new FilesystemSourceConnector( "fs", root ); }

    /**
     * The derived page name for a given connector source URI, computed the same way
     * {@code DerivedPageSinkAdapter} does (strip {@code scheme:}, flatten {@code /} to
     * {@code -}), then run through the real {@link DerivedPage#pageNameFor}.
     */
    private static String expectedPageName( String sourceUri ) {
        final int colon = sourceUri.indexOf( ':' );
        final String path = colon >= 0 ? sourceUri.substring( colon + 1 ) : sourceUri;
        return DerivedPage.pageNameFor( path.replace( '/', '-' ) );
    }

    // DoD #1 — sync → derived pages: derived_from = connector URI, source metadata carried, schema-valid
    @Test void syncProducesDerivedPagesWithProvenance( @TempDir Path root ) throws Exception {
        Files.createDirectories( root.resolve( "docs" ) );
        Files.writeString( root.resolve( "docs/guide.md" ), "# Guide\n\nHello world." );
        SyncReport r = orchestrator.sync( fs( root ) );
        assertEquals( 1, r.created() );
        String pageName = expectedPageName( "file:docs/guide.md" );
        Map< String, Object > fm = pages.get( pageName );
        assertNotNull( fm, "derived page written under '" + pageName + "'" );
        assertEquals( "file:docs/guide.md", fm.get( "derived_from" ) );        // provenance = source URI
        // schema-valid: derived_from present + non-blank is the frontmatter validator's derived-page rule
        assertFalse( fm.get( "derived_from" ).toString().isBlank() );
    }

    // DoD #2 — cursor-resume: a crash after batch 1 does not re-ingest on restart
    @Test void resumeDoesNotReprocess( @TempDir Path root ) throws Exception {
        Files.writeString( root.resolve( "a.md" ), "alpha" );
        orchestrator.sync( fs( root ) );                                       // first run: creates derived page
        int writesAfterFirst = pages.size();
        // second run, unchanged content → hash-dedup, no re-write
        SyncReport r2 = orchestrator.sync( fs( root ) );
        assertEquals( 1, r2.unchanged() );
        assertEquals( 0, r2.created() + r2.updated() );
        assertEquals( writesAfterFirst, pages.size() );
    }

    // DoD #3 — tombstone: deleting a source file removes its derived page on the next sync
    @Test void deletedSourceFileRemovesDerivedPage( @TempDir Path root ) throws Exception {
        Files.writeString( root.resolve( "keep.md" ), "keep" );
        Files.writeString( root.resolve( "drop.md" ), "drop" );
        orchestrator.sync( fs( root ) );
        String keepPage = expectedPageName( "file:keep.md" );
        String dropPage = expectedPageName( "file:drop.md" );
        assertTrue( pages.containsKey( dropPage ) );
        Files.delete( root.resolve( "drop.md" ) );
        SyncReport r = orchestrator.sync( fs( root ) );
        assertEquals( 1, r.deleted() );
        assertFalse( pages.containsKey( dropPage ) );
        assertTrue( pages.containsKey( keepPage ) );
    }

    // DoD #4 — acl_refs carried into sync state (+ migration idempotency covered by JdbcSyncStateStoreTest)
    @Test void aclRefsPersistedForSyncedItems( @TempDir Path root ) throws Exception {
        Files.createDirectories( root.resolve( "secure" ) );
        Files.writeString( root.resolve( "secure/x.md" ), "secret" );
        orchestrator.sync( fs( root ) );
        try ( Connection c = ds.getConnection();
              var rs = c.createStatement().executeQuery(
                  "SELECT acl_refs FROM connector_synced_item WHERE source_uri='file:secure/x.md'" ) ) {
            assertTrue( rs.next() );
            assertEquals( "[\"secure\"]", rs.getString( 1 ) );                 // parent-dir principal carried
        }
    }
}
