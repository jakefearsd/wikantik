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

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the {@link ContentChunkRepository} surfaces not exercised by
 * {@link ContentChunkRepositoryTest} (which focuses on {@code apply}/outliers/stats):
 * page/id listing and the {@code findByIds} in-process cache.
 */
@RequiresPostgres
class ContentChunkRepositoryMoreTest {

    private static DataSource dataSource;
    private ContentChunkRepository repo;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestDb.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        try ( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_content_chunks" );
        }
        repo = new ContentChunkRepository( dataSource );
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
}
