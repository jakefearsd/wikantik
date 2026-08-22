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
package com.wikantik.knowledge.querylog;

import com.wikantik.api.querylog.AggregatedQuery;
import com.wikantik.api.querylog.ActorType;
import com.wikantik.api.querylog.QueryLogQuery;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@RequiresPostgres
class JdbcQueryLogReaderTest {

    private DataSource ds;

    @BeforeEach
    void setUp() throws Exception {
        this.ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( "retrieval_query_log" );
        insert( "how do I deploy locally", "agent", "api_bundle", 0 );
        insert( "how do I deploy locally", "agent", "api_bundle", 0 );
        insert( "how do I deploy locally", "human", "api_search", 2 );
        insert( "embedding model choice", "human", "api_search", 8 );
    }

    private void insert( String q, String actor, String surface, Integer count ) throws Exception {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                  "INSERT INTO retrieval_query_log (query_text, actor_type, source_surface, result_count) VALUES (?,?,?,?)" ) ) {
            ps.setString( 1, q ); ps.setString( 2, actor ); ps.setString( 3, surface );
            if ( count == null ) ps.setNull( 4, java.sql.Types.INTEGER ); else ps.setInt( 4, count );
            ps.executeUpdate();
        }
    }

    @Test
    void aggregatesGroupsAndOrdersByFrequency() {
        final var rows = new JdbcQueryLogReader( ds ).topQueries(
                new QueryLogQuery( Instant.EPOCH, null, null, null, 1, 50 ) );
        assertEquals( 2, rows.size() );
        final AggregatedQuery top = rows.get( 0 );
        assertEquals( "how do I deploy locally", top.queryText() );
        assertEquals( 3, top.occurrences() );
        assertEquals( 2, top.zeroResultCount() );
        assertEquals( ( 0.0 + 0 + 2 ) / 3, top.avgResultCount(), 1e-9 );
        assertNotNull( top.lastSeen() );
    }

    @Test
    void missFilterKeepsOnlyLowAverageQueries() {
        final var rows = new JdbcQueryLogReader( ds ).topQueries(
                new QueryLogQuery( Instant.EPOCH, null, null, 1, 1, 50 ) );
        assertEquals( 1, rows.size() );
        assertEquals( "how do I deploy locally", rows.get( 0 ).queryText() );
    }

    @Test
    void actorFilterRestrictsRows() {
        final var rows = new JdbcQueryLogReader( ds ).topQueries(
                new QueryLogQuery( Instant.EPOCH, ActorType.HUMAN, null, null, 1, 50 ) );
        assertEquals( 2, rows.size() );      // one human deploy row + one human embedding row
        assertTrue( rows.stream().allMatch( r -> r.occurrences() == 1 ) );
    }
}
