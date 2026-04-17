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
