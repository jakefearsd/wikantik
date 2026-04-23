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
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JDBC-backed tests for {@link QueryEntityResolver}. The pure-unit window
 * builder is tested inline below; the DB tests cover the ILIKE-via-LOWER
 * match against real {@code kg_nodes} rows.
 */
@Testcontainers( disabledWithoutDocker = true )
class QueryEntityResolverTest {

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
    void resolvesSingleWordExactMatchCaseInsensitive() {
        final UUID napoleon = insertNode( "Napoleon" );
        final QueryEntityResolver resolver = new QueryEntityResolver( dataSource, defaultCfg() );
        assertEquals( Set.of( napoleon ), resolver.resolve( "napoleon" ) );
        assertEquals( Set.of( napoleon ), resolver.resolve( "NAPOLEON" ) );
    }

    @Test
    void resolvesMultipleTokensInQuery() {
        final UUID napoleon = insertNode( "Napoleon" );
        final UUID waterloo = insertNode( "Waterloo" );
        final QueryEntityResolver resolver = new QueryEntityResolver( dataSource, defaultCfg() );
        assertEquals( Set.of( napoleon, waterloo ),
            resolver.resolve( "what happened at Napoleon's Waterloo?" ) );
    }

    @Test
    void resolvesMultiWordEntityViaTwoGram() {
        final UUID ny = insertNode( "New York" );
        final QueryEntityResolver resolver = new QueryEntityResolver( dataSource, defaultCfg() );
        assertEquals( Set.of( ny ), resolver.resolve( "What's the weather in new york?" ) );
    }

    @Test
    void resolveReturnsEmptyWhenNoMatches() {
        insertNode( "SomethingElse" );
        final QueryEntityResolver resolver = new QueryEntityResolver( dataSource, defaultCfg() );
        assertEquals( Set.of(), resolver.resolve( "nothing here" ) );
    }

    @Test
    void cacheHitSkipsSecondLookup() {
        insertNode( "Napoleon" );
        final QueryEntityResolver resolver = new QueryEntityResolver( dataSource, defaultCfg() );
        final Set< UUID > first = resolver.resolve( "Napoleon" );
        // Delete the node; a cache-less call would now miss, but the cached hit survives.
        try( Connection c = dataSource.getConnection() ) {
            c.createStatement().execute( "DELETE FROM kg_nodes WHERE name = 'Napoleon'" );
        } catch( final Exception e ) {
            throw new RuntimeException( e );
        }
        assertEquals( first, resolver.resolve( "Napoleon" ) );
        resolver.invalidateAll();
        assertEquals( Set.of(), resolver.resolve( "Napoleon" ) );
    }

    @Test
    void blankQueryReturnsEmpty() {
        final QueryEntityResolver resolver = new QueryEntityResolver( dataSource, defaultCfg() );
        assertEquals( Set.of(), resolver.resolve( "" ) );
        assertEquals( Set.of(), resolver.resolve( "   " ) );
        assertEquals( Set.of(), resolver.resolve( null ) );
    }

    @Test
    void tokenWindowsProducesSinglesAndNGrams() {
        final List< String > windows = QueryEntityResolver.tokenWindows( "new york city" );
        assertTrue( windows.contains( "new" ) );
        assertTrue( windows.contains( "york" ) );
        assertTrue( windows.contains( "city" ) );
        assertTrue( windows.contains( "new york" ) );
        assertTrue( windows.contains( "york city" ) );
        assertTrue( windows.contains( "new york city" ) );
    }

    @Test
    void tokenWindowsDropsStopWords() {
        final List< String > windows = QueryEntityResolver.tokenWindows( "the cat" );
        assertTrue( windows.contains( "cat" ) );
        // "the" as a standalone candidate is suppressed so it never reaches the SQL in-list.
        assertTrue( !windows.contains( "the" ) );
        // But 2-grams are still allowed — multi-word proper nouns can begin with stop words.
        assertTrue( windows.contains( "the cat" ) );
    }

    private GraphRerankConfig defaultCfg() {
        return GraphRerankConfig.fromProperties( new Properties() );
    }

    private UUID insertNode( final String name ) {
        final UUID id = UUID.randomUUID();
        try( Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO kg_nodes (id, name, node_type, provenance) VALUES (?, ?, 'entity', 'human-authored')" ) ) {
            ps.setObject( 1, id );
            ps.setString( 2, name );
            ps.executeUpdate();
        } catch( final Exception e ) {
            throw new RuntimeException( "insertNode failed for " + name, e );
        }
        return id;
    }
}
