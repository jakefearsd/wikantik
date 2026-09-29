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

import com.wikantik.api.knowledge.Provenance;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.knowledge.KgNodeRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the {@link ContentChunkRepository} surfaces not exercised by
 * {@link ContentChunkRepositoryTest} (which focuses on {@code apply}/outliers/stats):
 * page/id listing, the {@code findByIds} in-process cache, node-mention lookups, and
 * the substring-search fallback path.
 */
@RequiresPostgres
class ContentChunkRepositoryMoreTest {

    private static DataSource dataSource;
    private ContentChunkRepository repo;
    private KgNodeRepository nodes;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestDb.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM chunk_entity_mentions" );
            conn.createStatement().execute( "DELETE FROM kg_content_chunks" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
        repo = new ContentChunkRepository( dataSource );
        nodes = new KgNodeRepository( dataSource );
    }

    private UUID insertChunk( final String page, final int index, final String text ) {
        final Chunk c = new Chunk( page, index, List.of( "H" ), text, text.length(), 1, "hash-" + page + index );
        repo.apply( page, new ChunkDiff.Diff( List.of( c ), List.of(), List.of() ) );
        return repo.findByPage( page ).stream()
                .filter( s -> s.chunkIndex() == index )
                .findFirst().orElseThrow().id();
    }

    // ------------------------------------------------------------------ listing

    @Test
    void listDistinctPageNamesIsAlphabeticalAndDeduplicated() {
        insertChunk( "Zeta", 0, "z" );
        insertChunk( "Alpha", 0, "a" );
        insertChunk( "Alpha", 1, "a2" );

        assertEquals( List.of( "Alpha", "Zeta" ), repo.listDistinctPageNames() );
    }

    @Test
    void listChunkIdsForPageIsOrderedByChunkIndex() {
        final UUID id1 = insertChunk( "Ordered", 1, "second" );
        final UUID id0 = insertChunk( "Ordered", 0, "first" );

        assertEquals( List.of( id0, id1 ), repo.listChunkIdsForPage( "Ordered" ) );
    }

    // ------------------------------------------------------------------ findByIds / cache

    @Test
    void findByIdsReturnsEmptyForNullOrEmptyInput() {
        assertEquals( List.of(), repo.findByIds( null ) );
        assertEquals( List.of(), repo.findByIds( List.of() ) );
    }

    @Test
    void findByIdsFetchesFromDbThenServesFromCache() {
        final UUID id = insertChunk( "Cached", 0, "cached body" );

        final List< ContentChunkRepository.MentionableChunk > first = repo.findByIds( List.of( id ) );
        assertEquals( 1, first.size() );
        assertEquals( "Cached", first.get( 0 ).pageName() );
        assertTrue( repo.cacheStats().missCount() >= 1, "first lookup must be a cache miss" );

        // Delete the row directly so a second DB round-trip would return nothing —
        // proving the second call is served entirely from the in-process cache.
        try {
            fetchIsCachedAfterRowDeleted( id );
        } catch ( final Exception e ) {
            fail( e );
        }
    }

    private void fetchIsCachedAfterRowDeleted( final UUID id ) throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_content_chunks WHERE id = '" + id + "'" );
        }
        final List< ContentChunkRepository.MentionableChunk > second = repo.findByIds( List.of( id ) );
        assertEquals( 1, second.size(), "second call must be served from cache, not the (now-empty) DB" );
        assertEquals( id, second.get( 0 ).id() );
    }

    @Test
    void findByIdsSkipsUnknownIds() {
        final UUID unknown = UUID.randomUUID();
        assertTrue( repo.findByIds( List.of( unknown ) ).isEmpty() );
    }

    @Test
    void fetchByIdsFromDbBypassesCache() {
        final UUID id = insertChunk( "Direct", 0, "direct body" );
        final List< ContentChunkRepository.MentionableChunk > rows = repo.fetchByIdsFromDb( List.of( id ) );
        assertEquals( 1, rows.size() );
        assertEquals( List.of( "H" ), rows.get( 0 ).headingPath() );
    }

    @Test
    void invalidatePageIgnoresNullAndEvictsMatchingEntries() {
        assertDoesNotThrow( () -> repo.invalidatePage( null ) );

        final UUID id = insertChunk( "ToEvict", 0, "text" );
        repo.findByIds( List.of( id ) ); // warm the cache
        assertNotNull( repo.cache().getIfPresent( id ) );

        repo.invalidatePage( "ToEvict" );

        assertNull( repo.cache().getIfPresent( id ) );
    }

    @Test
    void invalidateAllClearsEveryEntry() {
        final UUID id = insertChunk( "ToClearAll", 0, "text" );
        repo.findByIds( List.of( id ) );
        assertNotNull( repo.cache().getIfPresent( id ) );

        repo.invalidateAll();

        assertNull( repo.cache().getIfPresent( id ) );
    }

    // ------------------------------------------------------------------ findMentionsForNode

    @Test
    void findMentionsForNodeReturnsEmptyForNullNode() {
        assertEquals( List.of(), repo.findMentionsForNode( null, 10 ) );
    }

    @Test
    void findMentionsForNodeOrdersByConfidenceThenChunkIndex() throws Exception {
        final UUID nodeId = nodes.upsertNode( "MentionedThing", "concept", "MentionPage",
                Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final UUID chunkLow = insertChunk( "MentionPage", 0, "low confidence chunk" );
        final UUID chunkHigh = insertChunk( "MentionPage", 1, "high confidence chunk" );
        insertMention( chunkLow, nodeId, 0.2 );
        insertMention( chunkHigh, nodeId, 0.9 );

        final List< ContentChunkRepository.NodeMentionRow > rows = repo.findMentionsForNode( nodeId, 10 );

        assertEquals( 2, rows.size() );
        assertEquals( chunkHigh, rows.get( 0 ).chunkId(), "higher confidence must sort first" );
        assertEquals( chunkLow, rows.get( 1 ).chunkId() );
    }

    @Test
    void findMentionsForNodeClampsLimitToValidRange() throws Exception {
        final UUID nodeId = nodes.upsertNode( "ClampedThing", "concept", "ClampPage",
                Provenance.HUMAN_AUTHORED, Map.of() ).id();
        final UUID chunk = insertChunk( "ClampPage", 0, "one mention" );
        insertMention( chunk, nodeId, 0.5 );

        // A limit below 1 must clamp up to 1, not throw / return nothing.
        assertEquals( 1, repo.findMentionsForNode( nodeId, -5 ).size() );
        // A limit above 50 must clamp down, not fail.
        assertEquals( 1, repo.findMentionsForNode( nodeId, 500 ).size() );
    }

    private void insertMention( final UUID chunkId, final UUID nodeId, final double confidence ) throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            final var ps = conn.prepareStatement(
                    "INSERT INTO chunk_entity_mentions (chunk_id, node_id, confidence, extractor) VALUES (?, ?, ?, ?)" );
            ps.setObject( 1, chunkId );
            ps.setObject( 2, nodeId );
            ps.setDouble( 3, confidence );
            ps.setString( 4, "test-extractor" );
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ findChunksOnPageContaining

    @Test
    void findChunksOnPageContainingReturnsEmptyForBlankInputs() {
        assertEquals( List.of(), repo.findChunksOnPageContaining( null, "needle", 10 ) );
        assertEquals( List.of(), repo.findChunksOnPageContaining( "", "needle", 10 ) );
        assertEquals( List.of(), repo.findChunksOnPageContaining( "Page", null, 10 ) );
        assertEquals( List.of(), repo.findChunksOnPageContaining( "Page", "  ", 10 ) );
    }

    @Test
    void findChunksOnPageContainingIsCaseInsensitiveAndOrdered() {
        insertChunk( "SearchPage", 1, "the SECOND chunk mentions needle" );
        insertChunk( "SearchPage", 0, "the first chunk mentions Needle too" );
        insertChunk( "SearchPage", 2, "no match here" );

        final List< ContentChunkRepository.ChunkOnPage > rows =
                repo.findChunksOnPageContaining( "SearchPage", "needle", 10 );

        assertEquals( 2, rows.size() );
        assertEquals( 0, rows.get( 0 ).chunkIndex() );
        assertEquals( 1, rows.get( 1 ).chunkIndex() );
    }
}
