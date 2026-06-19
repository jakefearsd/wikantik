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

import com.wikantik.PostgresTestContainer;
import com.wikantik.api.querylog.ActorType;
import com.wikantik.api.querylog.SourceSurface;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirms the real {@code retrieval_query_log} DDL (BIGSERIAL id + NOW() default + the wire
 * tokens) accepts the service's insert on actual Postgres — the H2 unit test can't validate
 * the Postgres-specific column types.
 */
@Testcontainers( disabledWithoutDocker = true )
class JdbcQueryLogServicePostgresTest {

    private DataSource ds;

    @BeforeEach
    void setUp() {
        ds = PostgresTestContainer.createDataSource();
    }

    @Test
    void log_insertsRow_onRealPostgres() throws Exception {
        final String unique = "pg-rql-" + System.nanoTime();
        new JdbcQueryLogService( ds, true, Runnable::run )
            .log( unique, ActorType.AGENT, SourceSurface.TOOLS_SEARCH_WIKI, 7 );

        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                  "SELECT actor_type, source_surface, result_count, created_at "
                  + "FROM retrieval_query_log WHERE query_text = ?" ) ) {
            ps.setString( 1, unique );
            try ( ResultSet rs = ps.executeQuery() ) {
                assertTrue( rs.next(), "row written to real Postgres" );
                assertEquals( "agent", rs.getString( "actor_type" ) );
                assertEquals( "tools_search_wiki", rs.getString( "source_surface" ) );
                assertEquals( 7, rs.getInt( "result_count" ) );
                assertTrue( rs.getTimestamp( "created_at" ) != null, "created_at defaulted by NOW()" );
            }
        }
    }
}
