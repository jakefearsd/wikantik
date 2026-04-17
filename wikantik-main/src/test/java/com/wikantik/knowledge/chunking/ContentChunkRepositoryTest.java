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
package com.wikantik.knowledge.chunking;

import com.wikantik.PostgresTestContainer;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class ContentChunkRepositoryTest {

    private static DataSource dataSource;
    private ContentChunkRepository repo;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        try( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_content_chunks" );
        }
        repo = new ContentChunkRepository( dataSource );
    }

    @Test
    void insertAndFindRoundTrip() {
        final Chunk c = new Chunk( "P", 0, List.of( "H1" ), "body", 4, 1, "hash0" );
        final ChunkDiff.Diff diff = new ChunkDiff.Diff( List.of( c ), List.of(), List.of() );
        repo.apply( "P", diff );
        final List< ChunkDiff.Stored > stored = repo.findByPage( "P" );
        assertEquals( 1, stored.size() );
        assertEquals( 0, stored.get( 0 ).chunkIndex() );
        assertEquals( "hash0", stored.get( 0 ).contentHash() );
    }

    @Test
    void updateKeepsIdAndUpdatesHash() {
        final Chunk c0 = new Chunk( "P", 0, List.of(), "v1", 2, 1, "h1" );
        repo.apply( "P", new ChunkDiff.Diff( List.of( c0 ), List.of(), List.of() ) );
        final UUID id = repo.findByPage( "P" ).get( 0 ).id();

        final Chunk c0v2 = new Chunk( "P", 0, List.of(), "v2", 2, 1, "h2" );
        repo.apply( "P", new ChunkDiff.Diff( List.of(),
            List.of( new ChunkDiff.Update( id, c0v2 ) ), List.of() ) );

        final List< ChunkDiff.Stored > after = repo.findByPage( "P" );
        assertEquals( 1, after.size() );
        assertEquals( id, after.get( 0 ).id(), "id must be preserved on update" );
        assertEquals( "h2", after.get( 0 ).contentHash() );
    }

    @Test
    void deleteRemovesByIdOnly() {
        final Chunk c0 = new Chunk( "P", 0, List.of(), "v1", 2, 1, "h1" );
        final Chunk c1 = new Chunk( "P", 1, List.of(), "v2", 2, 1, "h2" );
        repo.apply( "P", new ChunkDiff.Diff( List.of( c0, c1 ), List.of(), List.of() ) );
        final UUID id0 = repo.findByPage( "P" ).get( 0 ).id();
        repo.apply( "P", new ChunkDiff.Diff( List.of(), List.of(), List.of( id0 ) ) );
        final List< ChunkDiff.Stored > after = repo.findByPage( "P" );
        assertEquals( 1, after.size() );
        assertEquals( 1, after.get( 0 ).chunkIndex() );
    }

    @Test
    void uniqueIndexConstraintEnforced() {
        final Chunk c0 = new Chunk( "P", 0, List.of(), "a", 1, 1, "ha" );
        final Chunk c0dup = new Chunk( "P", 0, List.of(), "b", 1, 1, "hb" );
        repo.apply( "P", new ChunkDiff.Diff( List.of( c0 ), List.of(), List.of() ) );
        assertThrows( Exception.class, () ->
            repo.apply( "P", new ChunkDiff.Diff( List.of( c0dup ), List.of(), List.of() ) ) );
    }

    @Test
    void deleteAllEmptiesTable() {
        final Chunk c = new Chunk( "P", 0, List.of(), "x", 1, 1, "hx" );
        repo.apply( "P", new ChunkDiff.Diff( List.of( c ), List.of(), List.of() ) );
        repo.deleteAll();
        assertTrue( repo.findByPage( "P" ).isEmpty() );
    }

    @Test
    void findFullByPageReturnsOrderedChunks() {
        // Insert 3 chunks out of order so we know ordering comes from the query,
        // not from insertion order.
        final Chunk c2 = new Chunk( "P", 2, List.of( "Top", "Sub" ), "body-2", 6, 2, "h2" );
        final Chunk c0 = new Chunk( "P", 0, List.of( "Top" ), "body-0", 6, 1, "h0" );
        final Chunk c1 = new Chunk( "P", 1, List.of( "Top" ), "body-1", 6, 3, "h1" );
        repo.apply( "P", new ChunkDiff.Diff( List.of( c2, c0, c1 ), List.of(), List.of() ) );

        final List< ContentChunkRepository.FullChunk > full = repo.findFullByPage( "P" );
        assertEquals( 3, full.size() );
        assertEquals( 0, full.get( 0 ).chunkIndex() );
        assertEquals( 1, full.get( 1 ).chunkIndex() );
        assertEquals( 2, full.get( 2 ).chunkIndex() );

        final ContentChunkRepository.FullChunk first = full.get( 0 );
        assertNotNull( first.id() );
        assertEquals( List.of( "Top" ), first.headingPath() );
        assertEquals( "body-0", first.text() );
        assertEquals( 6, first.charCount() );
        assertEquals( 1, first.tokenCountEstimate() );
        assertEquals( "h0", first.contentHash() );
        assertNotNull( first.created() );
        assertNotNull( first.modified() );

        assertEquals( List.of( "Top", "Sub" ), full.get( 2 ).headingPath() );
    }

    @Test
    void outliersReportMostChunks() {
        // 1 chunk page
        repo.apply( "P1", new ChunkDiff.Diff(
            List.of( new Chunk( "P1", 0, List.of(), "x", 1, 10, "a" ) ),
            List.of(), List.of() ) );
        // 2 chunk page
        repo.apply( "P2", new ChunkDiff.Diff(
            List.of(
                new Chunk( "P2", 0, List.of(), "x", 1, 20, "b0" ),
                new Chunk( "P2", 1, List.of(), "x", 1, 30, "b1" ) ),
            List.of(), List.of() ) );
        // 5 chunk page (the "most chunks" winner)
        repo.apply( "P5", new ChunkDiff.Diff(
            List.of(
                new Chunk( "P5", 0, List.of(), "x", 1, 40, "c0" ),
                new Chunk( "P5", 1, List.of(), "x", 1, 50, "c1" ),
                new Chunk( "P5", 2, List.of(), "x", 1, 60, "c2" ),
                new Chunk( "P5", 3, List.of(), "x", 1, 70, "c3" ),
                new Chunk( "P5", 4, List.of(), "x", 1, 80, "c4" ) ),
            List.of(), List.of() ) );

        final ContentChunkRepository.OutlierReport report = repo.outliers();
        assertFalse( report.mostChunks().isEmpty() );
        assertEquals( "P5", report.mostChunks().get( 0 ).pageName() );
        assertEquals( 5, report.mostChunks().get( 0 ).chunkCount() );
        assertEquals( 80, report.mostChunks().get( 0 ).maxTokens() );
        assertEquals( 40 + 50 + 60 + 70 + 80, report.mostChunks().get( 0 ).totalTokens() );
    }

    @Test
    void outliersReportLargeSingleChunks() {
        // A page with exactly one chunk, 500-char text — should appear in
        // largeSingleChunks because char_count > 400 and COUNT = 1.
        final String longText = "x".repeat( 500 );
        repo.apply( "Giant", new ChunkDiff.Diff(
            List.of( new Chunk( "Giant", 0, List.of(), longText, 500, 130, "gh" ) ),
            List.of(), List.of() ) );
        // A multi-chunk page that must NOT appear (not a single-chunk page).
        repo.apply( "Many", new ChunkDiff.Diff(
            List.of(
                new Chunk( "Many", 0, List.of(), "x".repeat( 500 ), 500, 100, "m0" ),
                new Chunk( "Many", 1, List.of(), "y", 1, 1, "m1" ) ),
            List.of(), List.of() ) );

        final ContentChunkRepository.OutlierReport report = repo.outliers();
        assertTrue( report.largeSingleChunks().stream()
                    .anyMatch( e -> "Giant".equals( e.pageName() ) ),
                    "Giant must appear in largeSingleChunks" );
        assertTrue( report.largeSingleChunks().stream()
                    .noneMatch( e -> "Many".equals( e.pageName() ) ),
                    "Many must NOT appear in largeSingleChunks (>1 chunks)" );
    }

    @Test
    void outliersReportOversizedChunks() {
        // A chunk whose token_count_estimate exceeds the configured max (512).
        repo.apply( "BigTok", new ChunkDiff.Diff(
            List.of( new Chunk( "BigTok", 0, List.of(), "x", 5, 600, "bh" ) ),
            List.of(), List.of() ) );
        // A normally-sized chunk that must not appear.
        repo.apply( "OkTok", new ChunkDiff.Diff(
            List.of( new Chunk( "OkTok", 0, List.of(), "x", 5, 100, "oh" ) ),
            List.of(), List.of() ) );

        final ContentChunkRepository.OutlierReport report = repo.outliers();
        assertTrue( report.oversizedChunks().stream()
                    .anyMatch( e -> "BigTok".equals( e.pageName() ) && e.maxTokens() == 600 ) );
        assertTrue( report.oversizedChunks().stream()
                    .noneMatch( e -> "OkTok".equals( e.pageName() ) ) );
    }

    @Test
    void statsAggregatesMatchInserts() {
        repo.apply( "P1", new ChunkDiff.Diff(
            List.of( new Chunk( "P1", 0, List.of(), "x", 1, 10, "h1" ) ),
            List.of(), List.of() ) );
        repo.apply( "P2", new ChunkDiff.Diff(
            List.of(
                new Chunk( "P2", 0, List.of(), "x", 1, 50, "h2" ),
                new Chunk( "P2", 1, List.of(), "x", 1, 100, "h3" ) ),
            List.of(), List.of() ) );
        final ContentChunkRepository.AggregateStats stats = repo.stats();
        assertEquals( 2, stats.pagesWithChunks() );
        assertEquals( 3, stats.totalChunks() );
        assertEquals( 10, stats.minTokens() );
        assertEquals( 100, stats.maxTokens() );
        // avg 10+50+100 = 160/3 ≈ 53
        assertTrue( stats.avgTokens() >= 52 && stats.avgTokens() <= 54,
                   "avg: " + stats.avgTokens() );
    }
}
