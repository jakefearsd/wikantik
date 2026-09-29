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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives every {@code catch (SQLException e) { LOG.warn(...); throw ... }} block in
 * {@link ContentChunkRepository} with a {@link DataSource} that always fails
 * {@code getConnection()} — the generic-failure paths a live Postgres constraint can't
 * reach, since none of these statements are otherwise malformed.
 */
class ContentChunkRepositoryErrorPathTest {

    private ContentChunkRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        final DataSource broken = mock( DataSource.class );
        when( broken.getConnection() ).thenThrow( new SQLException( "simulated connection failure" ) );
        repo = new ContentChunkRepository( broken );
    }

    @Test
    void listDistinctPageNamesWrapsFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class, repo::listDistinctPageNames );
        assertTrue( ex.getMessage().contains( "listDistinctPageNames failed" ) );
    }

    @Test
    void listChunkIdsForPageWrapsFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> repo.listChunkIdsForPage( "Page" ) );
        assertTrue( ex.getMessage().contains( "listChunkIdsForPage failed for Page" ) );
    }

    @Test
    void findByPageWrapsFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class, () -> repo.findByPage( "Page" ) );
        assertTrue( ex.getMessage().contains( "findByPage failed for Page" ) );
    }

    @Test
    void findByIdsWrapsFailureOnCacheMiss() {
        // A non-empty, never-before-seen id is guaranteed to miss the cache and reach the DB.
        assertThrows( RuntimeException.class, () -> repo.findByIds( List.of( UUID.randomUUID() ) ) );
    }

    @Test
    void findFullByPageWrapsFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class, () -> repo.findFullByPage( "Page" ) );
        assertTrue( ex.getMessage().contains( "findFullByPage failed for Page" ) );
    }

    @Test
    void findMentionsForNodeWrapsFailure() {
        assertThrows( RuntimeException.class, () -> repo.findMentionsForNode( UUID.randomUUID(), 10 ) );
    }

    @Test
    void findChunksOnPageContainingWrapsFailure() {
        assertThrows( RuntimeException.class,
                () -> repo.findChunksOnPageContaining( "Page", "needle", 10 ) );
    }

    @Test
    void outliersWrapsFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class, repo::outliers );
        assertTrue( ex.getMessage().contains( "outliers failed" ) );
    }

    @Test
    void applyWrapsFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> repo.apply( "Page", new ChunkDiff.Diff( List.of(), List.of(), List.of() ) ) );
        assertTrue( ex.getMessage().contains( "apply failed for Page" ) );
    }

    @Test
    void deleteAllWrapsFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class, repo::deleteAll );
        assertTrue( ex.getMessage().contains( "deleteAll failed" ) );
    }

    @Test
    void statsWrapsFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class, repo::stats );
        assertTrue( ex.getMessage().contains( "stats failed" ) );
    }
}
