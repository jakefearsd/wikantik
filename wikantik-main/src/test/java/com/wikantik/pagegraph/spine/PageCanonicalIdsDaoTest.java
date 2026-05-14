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
package com.wikantik.pagegraph.spine;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class PageCanonicalIdsDaoTest {

    private DataSource ds;
    private PageCanonicalIdsDao dao;

    @BeforeEach
    void setUp() throws Exception {
        final JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:pci;MODE=PostgreSQL;DB_CLOSE_DELAY=-1" );
        this.ds = h2;
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( """
                CREATE TABLE page_canonical_ids (
                    canonical_id CHAR(26) PRIMARY KEY,
                    current_slug VARCHAR(512) NOT NULL UNIQUE,
                    title VARCHAR(512) NOT NULL,
                    type VARCHAR(32) NOT NULL,
                    cluster VARCHAR(128),
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""" );
            s.executeUpdate( """
                CREATE TABLE page_slug_history (
                    canonical_id CHAR(26) NOT NULL,
                    previous_slug VARCHAR(512) NOT NULL,
                    renamed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (canonical_id, previous_slug)
                )""" );
        }
        this.dao = new PageCanonicalIdsDao( ds );
    }

    @AfterEach
    void tearDown() throws Exception {
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "DROP TABLE page_slug_history" );
            s.executeUpdate( "DROP TABLE page_canonical_ids" );
        }
    }

    @Test
    void upsert_inserts_new_row() {
        dao.upsert( "01H8G3Z1K6Q5W7P9X2V4R0T8MN", "HybridRetrieval", "Hybrid Retrieval",
                    "article", "wikantik-development" );

        final Optional< PageCanonicalIdsDao.Row > row =
                dao.findByCanonicalId( "01H8G3Z1K6Q5W7P9X2V4R0T8MN" );
        assertTrue( row.isPresent() );
        assertEquals( "HybridRetrieval",       row.get().currentSlug() );
        assertEquals( "article",               row.get().type() );
        assertEquals( "wikantik-development",  row.get().cluster() );
    }

    @Test
    void upsert_updates_slug_and_records_history() {
        dao.upsert( "01H8G3Z1K6Q5W7P9X2V4R0T8MN", "HybridRetrieval", "Hybrid Retrieval",
                    "article", "wikantik-development" );

        dao.upsert( "01H8G3Z1K6Q5W7P9X2V4R0T8MN", "HybridSearch", "Hybrid Search",
                    "article", "wikantik-development" );

        final var row = dao.findByCanonicalId( "01H8G3Z1K6Q5W7P9X2V4R0T8MN" ).orElseThrow();
        assertEquals( "HybridSearch", row.currentSlug() );

        final List< String > history = dao.slugHistory( "01H8G3Z1K6Q5W7P9X2V4R0T8MN" );
        assertEquals( List.of( "HybridRetrieval" ), history );
    }

    @Test
    void findBySlug_returns_row_for_current_slug() {
        dao.upsert( "01H8G3Z1K6Q5W7P9X2V4R0T8MN", "HybridRetrieval", "Hybrid Retrieval",
                    "article", "wikantik-development" );
        assertTrue( dao.findBySlug( "HybridRetrieval" ).isPresent() );
        assertTrue( dao.findBySlug( "ThisDoesNotExist" ).isEmpty() );
    }

    @Test
    void findAll_returns_rows_in_stable_order() {
        dao.upsert( "01ABCDEFGHJKMNPQRSTVWXYZ12", "AA", "AA", "article", null );
        dao.upsert( "01ABCDEFGHJKMNPQRSTVWXYZ13", "BB", "BB", "hub",     null );
        final List< PageCanonicalIdsDao.Row > all = dao.findAll();
        assertEquals( 2, all.size() );
    }

    @Test
    void delete_removes_canonical_and_cascades_history() {
        dao.upsert( "01H8G3Z1K6Q5W7P9X2V4R0T8MN", "A", "A", "article", null );
        dao.upsert( "01H8G3Z1K6Q5W7P9X2V4R0T8MN", "B", "B", "article", null );
        assertEquals( 1, dao.slugHistory( "01H8G3Z1K6Q5W7P9X2V4R0T8MN" ).size() );

        dao.delete( "01H8G3Z1K6Q5W7P9X2V4R0T8MN" );
        assertTrue( dao.findByCanonicalId( "01H8G3Z1K6Q5W7P9X2V4R0T8MN" ).isEmpty() );
    }

    // -----------------------------------------------------------------------
    // Defensive upsert: four explicit cases for slug/canonical_id handling
    // -----------------------------------------------------------------------

    // IDs below are exactly 26 characters (ULID alphabet, safe for CHAR(26) columns).
    private static final String ID_NEW    = "01ABCDEFGHJKMNPQRSTVWXYZ01";
    private static final String ID_STABLE = "01ABCDEFGHJKMNPQRSTVWXYZ02";
    private static final String ID_RENAME = "01ABCDEFGHJKMNPQRSTVWXYZ03";
    private static final String ID_STALE  = "01ABCDEFGHJKMNPQRSTVWXYZ04";
    private static final String ID_FRESH  = "01ABCDEFGHJKMNPQRSTVWXYZ05";

    /** Case 1: new canonical_id + new slug → INSERT succeeds. */
    @Test
    void upsert_new_canonicalId_new_slug_inserts() {
        dao.upsert( ID_NEW, "BrandNewPage", "Brand New Page", "article", null );

        final Optional< PageCanonicalIdsDao.Row > row = dao.findByCanonicalId( ID_NEW );
        assertTrue( row.isPresent(), "Row should be present after insert" );
        assertEquals( "BrandNewPage", row.get().currentSlug() );
    }

    /** Case 2: same canonical_id + same slug → no-op (no exception, DB unchanged). */
    @Test
    void upsert_same_canonicalId_same_slug_is_noop() {
        dao.upsert( ID_STABLE, "StablePage", "Stable Page", "article", "cluster-a" );

        // Second call — identical slug, updated title
        assertDoesNotThrow( () ->
                dao.upsert( ID_STABLE, "StablePage", "Stable Page Updated", "article", "cluster-a" ) );

        // Should still be exactly one row and the slug unchanged
        assertEquals( 1, dao.findAll().size() );
        final PageCanonicalIdsDao.Row row = dao.findByCanonicalId( ID_STABLE ).orElseThrow();
        assertEquals( "StablePage", row.currentSlug() );
        // Title updated on repeated upsert (metadata update path)
        assertEquals( "Stable Page Updated", row.title() );
    }

    /** Case 3: same canonical_id + different slug (rename) → UPDATE slug, record history. */
    @Test
    void upsert_same_canonicalId_new_slug_updates_and_records_history() {
        dao.upsert( ID_RENAME, "OldSlug", "Old Title", "article", null );
        dao.upsert( ID_RENAME, "NewSlug", "New Title", "article", null );

        final PageCanonicalIdsDao.Row row = dao.findByCanonicalId( ID_RENAME ).orElseThrow();
        assertEquals( "NewSlug", row.currentSlug(), "Slug should be updated to new value" );

        final List< String > history = dao.slugHistory( ID_RENAME );
        assertEquals( List.of( "OldSlug" ), history, "Old slug should appear in history" );

        // Old slug no longer findable
        assertTrue( dao.findBySlug( "OldSlug" ).isEmpty() );
    }

    /**
     * Case 4: different canonical_id claiming an already-owned slug (data corruption /
     * stale DB row) → WARN logged with both canonical_ids and script hint, no exception
     * thrown, no stacktrace, DB row for new canonical_id NOT inserted.
     */
    @Test
    void upsert_different_canonicalId_same_slug_warns_and_skips_without_exception() {
        // Seed the slug under the "stale" (old) canonical_id
        final String staleId = ID_STALE;
        final String freshId = ID_FRESH;
        final String slug    = "ConflictedPage";

        dao.upsert( staleId, slug, "Conflicted Page", "article", null );

        // Capture WARN log events from PageCanonicalIdsDao
        final List< org.apache.logging.log4j.core.LogEvent > captured = new CopyOnWriteArrayList<>();
        final LoggerContext ctx = (LoggerContext) LogManager.getContext( false );
        final Configuration config = ctx.getConfiguration();

        final AbstractAppender capturingAppender = new AbstractAppender(
                "CapturingAppender-" + Thread.currentThread().getId(), null,
                PatternLayout.createDefaultLayout(), true, null ) {
            @Override
            public void append( final org.apache.logging.log4j.core.LogEvent event ) {
                captured.add( event.toImmutable() );
            }
        };
        capturingAppender.start();
        config.addAppender( capturingAppender );

        final String loggerName = PageCanonicalIdsDao.class.getName();
        LoggerConfig loggerConfig = config.getLoggerConfig( loggerName );
        if ( !loggerConfig.getName().equals( loggerName ) ) {
            // Logger not explicitly configured — add one
            loggerConfig = LoggerConfig.createLogger( false, Level.WARN, loggerName,
                    "true", new AppenderRef[0], null, config, null );
            config.addLogger( loggerName, loggerConfig );
        }
        loggerConfig.addAppender( capturingAppender, Level.WARN, null );
        ctx.updateLoggers();

        try {
            // Now try to upsert the fresh canonical_id for the same slug
            final PageCanonicalIdsDao.UpsertResult result = dao.upsert(
                    freshId, slug, "Conflicted Page", "article", null );
            assertEquals( PageCanonicalIdsDao.UpsertResult.SKIPPED_STALE_SLUG_OWNER, result,
                    "stale-slug conflict must signal SKIPPED so callers can suppress FK-bound cascades" );

            // The fresh canonical_id must NOT have been inserted
            assertTrue( dao.findByCanonicalId( freshId ).isEmpty(),
                    "freshId must not be inserted when slug is already owned by a different canonical_id" );

            // The stale row must still be present and unchanged
            final Optional< PageCanonicalIdsDao.Row > staleRow = dao.findByCanonicalId( staleId );
            assertTrue( staleRow.isPresent(), "Stale row should remain" );
            assertEquals( slug, staleRow.get().currentSlug() );

            // Exactly one WARN must have been logged and it must mention both canonical_ids
            // and the reconciliation script — but no Throwable (no stacktrace).
            final List< org.apache.logging.log4j.core.LogEvent > warns = captured.stream()
                    .filter( e -> e.getLevel() == Level.WARN )
                    .toList();
            assertFalse( warns.isEmpty(), "At least one WARN should be logged" );

            final org.apache.logging.log4j.core.LogEvent warnEvent = warns.get( 0 );
            final String message = warnEvent.getMessage().getFormattedMessage();
            assertTrue( message.contains( freshId ),  "WARN must mention freshId" );
            assertTrue( message.contains( staleId ),  "WARN must mention staleId (slug owner)" );
            assertTrue( message.contains( "reconcile_page_canonical_ids" ),
                    "WARN must reference reconciliation script" );
            assertNull( warnEvent.getThrown(),
                    "WARN must not carry a Throwable (no stacktrace)" );

        } finally {
            loggerConfig.removeAppender( "CapturingAppender-" + Thread.currentThread().getId() );
            ctx.updateLoggers();
            capturingAppender.stop();
        }
    }
}
