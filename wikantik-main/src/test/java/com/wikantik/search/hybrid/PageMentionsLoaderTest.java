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
package com.wikantik.search.hybrid;

import com.wikantik.PostgresTestContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JDBC-backed tests for {@link PageMentionsLoader}. Seeds a small
 * (page, chunk, mention) fixture and verifies that the bulk query rolls up
 * chunk-level mentions to the page grain.
 */
@Testcontainers( disabledWithoutDocker = true )
class PageMentionsLoaderTest {

    private static DataSource dataSource;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void clear() throws Exception {
        try( Connection c = dataSource.getConnection() ) {
            c.createStatement().execute( "DELETE FROM chunk_entity_mentions" );
            c.createStatement().execute( "DELETE FROM content_chunk_embeddings" );
            c.createStatement().execute( "DELETE FROM kg_content_chunks" );
            c.createStatement().execute( "DELETE FROM kg_edges" );
            c.createStatement().execute( "DELETE FROM kg_nodes" );
        }
    }

    @Test
    void rollsUpChunkMentionsToPageSet() {
        final UUID napoleon = insertNode( "Napoleon" );
        final UUID waterloo = insertNode( "Waterloo" );
        final UUID wellington = insertNode( "Wellington" );

        final UUID c1 = insertChunk( "HistoryOfEurope", 0 );
        final UUID c2 = insertChunk( "HistoryOfEurope", 1 );
        final UUID c3 = insertChunk( "FarmingInFrance", 0 );
        insertMention( c1, napoleon );
        insertMention( c1, waterloo );
        insertMention( c2, wellington );
        insertMention( c3, napoleon );

        final PageMentionsLoader loader = new PageMentionsLoader( dataSource );
        final Map< String, Set< UUID > > out = loader.loadFor( List.of( "HistoryOfEurope", "FarmingInFrance" ) );
        assertEquals( Set.of( napoleon, waterloo, wellington ), out.get( "HistoryOfEurope" ) );
        assertEquals( Set.of( napoleon ), out.get( "FarmingInFrance" ) );
    }

    @Test
    void pagesWithoutMentionsAbsentFromResult() {
        insertNode( "Orphan" );
        insertChunk( "Blank", 0 );
        final PageMentionsLoader loader = new PageMentionsLoader( dataSource );
        final Map< String, Set< UUID > > out = loader.loadFor( List.of( "Blank", "DoesNotExist" ) );
        assertTrue( out.isEmpty() );
    }

    @Test
    void emptyInputReturnsEmptyMap() {
        final PageMentionsLoader loader = new PageMentionsLoader( dataSource );
        assertTrue( loader.loadFor( List.of() ).isEmpty() );
        assertTrue( loader.loadFor( null ).isEmpty() );
    }

    // ---- fixture helpers ----

    private UUID insertNode( final String name ) {
        final UUID id = UUID.randomUUID();
        try( Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO kg_nodes (id, name, node_type, provenance) VALUES (?, ?, 'entity', 'human-authored')" ) ) {
            ps.setObject( 1, id );
            ps.setString( 2, name );
            ps.executeUpdate();
        } catch( final Exception e ) {
            throw new RuntimeException( e );
        }
        return id;
    }

    private UUID insertChunk( final String pageName, final int idx ) {
        final UUID id = UUID.randomUUID();
        try( Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO kg_content_chunks "
               + "(id, page_name, chunk_index, text, char_count, token_count_estimate, content_hash) "
               + "VALUES (?, ?, ?, 'body', 4, 1, ?)" ) ) {
            ps.setObject( 1, id );
            ps.setString( 2, pageName );
            ps.setInt( 3, idx );
            ps.setString( 4, Integer.toHexString( pageName.hashCode() ^ idx ) );
            ps.executeUpdate();
        } catch( final Exception e ) {
            throw new RuntimeException( e );
        }
        return id;
    }

    private void insertMention( final UUID chunkId, final UUID nodeId ) {
        try( Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO chunk_entity_mentions (chunk_id, node_id, confidence, extractor) "
               + "VALUES (?, ?, 1.0, 'test')" ) ) {
            ps.setObject( 1, chunkId );
            ps.setObject( 2, nodeId );
            ps.executeUpdate();
        } catch( final Exception e ) {
            throw new RuntimeException( e );
        }
    }
}
