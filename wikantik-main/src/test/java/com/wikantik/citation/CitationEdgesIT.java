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
package com.wikantik.citation;

import static org.junit.jupiter.api.Assertions.*;

import com.wikantik.api.citation.CitationStatus;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.ClusterSummary;
import com.wikantik.api.pagegraph.IndexHealth;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageType;
import com.wikantik.api.pagegraph.Sitemap;
import com.wikantik.api.pagegraph.StructuralConflict;
import com.wikantik.api.pagegraph.StructuralFilter;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.pagegraph.TagSummary;
import com.wikantik.api.pagegraph.Verification;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import javax.sql.DataSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration test for citation edges (Phase 3 — RAG-as-a-Service).
 *
 * <p>Runs against a real PostgreSQL container (no Cargo, no Selenide, no seed-lag)
 * to assert the full save → {@code current} → target-edit → {@code stale} lifecycle
 * of citation rows via {@link CitationSync} + {@link CitationRepository}.</p>
 *
 * <p>The {@link StructuralIndexService} is a simple map-backed stub so the test
 * controls canonical_id resolution without the &gt;20 s structural-index lag documented
 * for the Cargo IT suite.</p>
 */
@Testcontainers( disabledWithoutDocker = true )
class CitationEdgesIT {

    private static final Logger LOG = LogManager.getLogger( CitationEdgesIT.class );

    // --- container -------------------------------------------------------
    @SuppressWarnings( "resource" )
    private static final PostgreSQLContainer PG =
            new PostgreSQLContainer(
                    DockerImageName.parse( "pgvector/pgvector:pg17" )
                                   .asCompatibleSubstituteFor( "postgres" ) )
            .withDatabaseName( "citations_it_test" )
            .withUsername( "test" )
            .withPassword( "test" );

    static {
        PG.start();
    }

    private static DataSource dataSource;

    @BeforeAll
    static void createSchema() throws Exception {
        final PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl( PG.getJdbcUrl() );
        ds.setUser( PG.getUsername() );
        ds.setPassword( PG.getPassword() );
        dataSource = ds;

        try ( Connection c = dataSource.getConnection(); Statement st = c.createStatement() ) {
            st.execute( "CREATE TABLE IF NOT EXISTS citations ("
                    + "    id                    BIGSERIAL   PRIMARY KEY,"
                    + "    source_canonical_id   TEXT        NOT NULL,"
                    + "    target_canonical_id   TEXT        NOT NULL,"
                    + "    target_heading_path   TEXT        NOT NULL DEFAULT '',"
                    + "    span_text             TEXT        NOT NULL DEFAULT '',"
                    + "    span_hash             TEXT        NOT NULL,"
                    + "    claim_text            TEXT,"
                    + "    ordinal               INT         NOT NULL DEFAULT 0,"
                    + "    pinned_target_version INT,"
                    + "    status                TEXT        NOT NULL DEFAULT 'current',"
                    + "    first_seen            TIMESTAMPTZ NOT NULL DEFAULT NOW(),"
                    + "    last_checked          TIMESTAMPTZ,"
                    + "    last_status_change    TIMESTAMPTZ,"
                    + "    CONSTRAINT uq_citation UNIQUE ("
                    + "        source_canonical_id, target_canonical_id,"
                    + "        target_heading_path, span_hash, ordinal)"
                    + ")" );
        }
        LOG.info( "citations table created in IT Postgres container" );
    }

    @BeforeEach
    void truncateCitations() throws Exception {
        try ( Connection c = dataSource.getConnection(); Statement st = c.createStatement() ) {
            st.execute( "TRUNCATE citations RESTART IDENTITY" );
        }
    }

    // --- stub StructuralIndexService backed by mutable maps --------------

    /** Minimal StructuralIndexService stub: slug-to-canonicalId maps only. Everything else is a no-op. */
    private static final class StubIndex implements StructuralIndexService {

        private final Map< String, String > slugToCid = new HashMap<>();
        private final Map< String, String > cidToSlug = new HashMap<>();

        void register( final String slug, final String cid ) {
            slugToCid.put( slug, cid );
            cidToSlug.put( cid, slug );
        }

        @Override public Optional< String > resolveCanonicalIdFromSlug( final String slug ) {
            return Optional.ofNullable( slugToCid.get( slug ) );
        }

        @Override public Optional< String > resolveSlugFromCanonicalId( final String cid ) {
            return Optional.ofNullable( cidToSlug.get( cid ) );
        }

        @Override public Optional< PageDescriptor > getByCanonicalId( final String canonicalId ) { return Optional.empty(); }
        @Override public List< ClusterSummary > listClusters() { return List.of(); }
        @Override public Optional< ClusterDetails > getCluster( final String name ) { return Optional.empty(); }
        @Override public List< TagSummary > listTags( final int minPages ) { return List.of(); }
        @Override public List< PageDescriptor > listPagesByType( final PageType type ) { return List.of(); }
        @Override public List< PageDescriptor > listPagesByFilter( final StructuralFilter filter ) { return List.of(); }
        @Override public Sitemap sitemap() { throw new UnsupportedOperationException( "not needed in CitationEdgesIT" ); }
        @Override public void rebuild() {}
        @Override public IndexHealth health() { throw new UnsupportedOperationException( "not needed in CitationEdgesIT" ); }
        @Override public List< StructuralConflict > conflicts() { return List.of(); }
        @Override public Optional< Verification > verificationOf( final String canonicalId ) { return Optional.empty(); }
        @Override public StructuralProjectionSnapshot snapshot() { throw new UnsupportedOperationException( "not needed in CitationEdgesIT" ); }
    }

    // --- helpers ---------------------------------------------------------

    private CitationSync buildSync( final StubIndex idx,
                                    final Map< String, String > bodies,
                                    final Map< String, Integer > versions ) {
        final Function< String, Optional< String > > bodyLoader =
                slug -> Optional.ofNullable( bodies.get( slug ) );
        final Function< String, Optional< Integer > > versionLoader =
                slug -> Optional.ofNullable( versions.get( slug ) );
        final CitationRepository repo = new CitationRepository( dataSource );
        final CitationStalenessGrader grader = new CitationStalenessGrader(
                idx, bodyLoader, new MarkdownSectionExtractor() );
        return new CitationSync( repo, new CitationMarkupParser(), grader, idx, bodyLoader, versionLoader );
    }

    // --- tests -----------------------------------------------------------

    /**
     * Full lifecycle: save source page → citation is {@code current}; edit target
     * (span removed) → re-save target → citation becomes {@code stale}.
     */
    @Test
    void citationBecomesStaleWhenTargetSpanIsRemoved() {
        final StubIndex idx = new StubIndex();
        final String targetSlug = "TargetPage";
        final String targetCid  = "tgt-drain-001";
        final String sourceSlug = "SourcePage";
        final String sourceCid  = "src-drain-001";

        idx.register( targetSlug, targetCid );
        idx.register( sourceSlug, sourceCid );

        final String spanText = "drain the queue before rollback";
        final String targetBodyWithSpan =
                "## Drain Step\n" + spanText + "\n\nOther content.\n";
        final String sourceBody =
                "Some intro.\n\n"
                + "[you must drain](cite://" + targetCid + "/Drain%20Step"
                + " \"" + spanText + "\")\n";

        final Map< String, String > bodies    = new HashMap<>();
        final Map< String, Integer > versions = new HashMap<>();

        bodies.put( targetSlug, targetBodyWithSpan );
        bodies.put( sourceSlug, sourceBody );
        versions.put( targetSlug, 1 );

        final CitationSync sync = buildSync( idx, bodies, versions );

        // ── Step 1: save source page; citation should be CURRENT ─────────
        sync.onPageSaved( sourceSlug );

        final CitationRepository repo = new CitationRepository( dataSource );
        final List< CitationRow > afterSave = repo.findBySource( sourceCid );
        assertEquals( 1, afterSave.size(), "one citation must be created" );
        final CitationRow row = afterSave.get( 0 );
        assertEquals( targetCid,              row.targetCanonicalId() );
        assertEquals( "Drain Step",           row.targetHeadingPath() );
        assertEquals( CitationStatus.CURRENT, row.status(),
                "citation must be CURRENT when span is present in target section" );
        LOG.info( "After source save: status={}", row.status() );

        // ── Step 2: edit target (remove the span) and re-save target ─────
        final String targetBodyWithoutSpan =
                "## Drain Step\n\nDifferent content now.\n";
        bodies.put( targetSlug, targetBodyWithoutSpan );
        versions.put( targetSlug, 2 );

        sync.onPageSaved( targetSlug );

        final List< CitationRow > afterTargetEdit = repo.findBySource( sourceCid );
        assertEquals( 1, afterTargetEdit.size(), "citation row must persist after target edit" );
        final CitationRow staleRow = afterTargetEdit.get( 0 );
        assertEquals( CitationStatus.STALE, staleRow.status(),
                "citation must become STALE once the span is absent from the target section" );
        assertNotNull( staleRow.lastStatusChange(),
                "last_status_change must be stamped when status flips to STALE" );
        LOG.info( "After target edit: status={}", staleRow.status() );
    }

    /**
     * A citation targeting a canonical_id absent from the structural index must immediately
     * record {@code target_missing} status when the source page is saved.
     */
    @Test
    void citationIsTargetMissingWhenTargetCanonicalIdUnknown() {
        final StubIndex idx = new StubIndex();
        final String sourceSlug = "AnotherSource";
        final String sourceCid  = "src-missing-001";
        idx.register( sourceSlug, sourceCid );
        // "ghost-cid-999" is NOT registered — no slug resolves for it

        final String sourceBody =
                "[broken link](cite://ghost-cid-999/Section \"some text\")\n";

        final Map< String, String > bodies    = Map.of( sourceSlug, sourceBody );
        final Map< String, Integer > versions = Map.of();

        final CitationSync sync = buildSync( idx, bodies, versions );
        sync.onPageSaved( sourceSlug );

        final CitationRepository repo = new CitationRepository( dataSource );
        final List< CitationRow > rows = repo.findBySource( sourceCid );
        assertEquals( 1, rows.size() );
        assertEquals( CitationStatus.TARGET_MISSING, rows.get( 0 ).status(),
                "citation must be TARGET_MISSING when canonical_id resolves to no slug" );
    }
}
