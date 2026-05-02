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
package com.wikantik.knowledge;

import com.wikantik.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class MigrationV024Test {

    @Test
    void schema_contains_tier_columns_and_audit_table() throws Exception {
        final DataSource ds = PostgresTestContainer.createDataSource();
        try ( Connection c = ds.getConnection() ) {
            assertColumn( c, "kg_proposals",       "tier",                   "character varying" );
            assertColumn( c, "kg_proposals",       "machine_status",         "character varying" );
            assertColumn( c, "kg_proposals",       "machine_confidence",     "double precision" );
            assertColumn( c, "kg_proposals",       "machine_judged_at",      "timestamp without time zone" );
            assertColumn( c, "kg_proposals",       "machine_model",          "character varying" );
            assertColumn( c, "kg_nodes",           "tier",                   "character varying" );
            assertColumn( c, "kg_nodes",           "provenance_proposal_id", "uuid" );
            assertColumn( c, "kg_edges",           "tier",                   "character varying" );
            assertColumn( c, "kg_edges",           "provenance_proposal_id", "uuid" );

            try ( ResultSet rs = c.createStatement().executeQuery(
                    "SELECT to_regclass('kg_proposal_reviews')" ) ) {
                rs.next();
                assertNotNull( rs.getString( 1 ), "kg_proposal_reviews table must exist" );
            }
        }
    }

    private static void assertColumn( Connection c, String table, String column, String expectedType )
            throws SQLException {
        try ( var ps = c.prepareStatement(
                "SELECT data_type FROM information_schema.columns " +
                "WHERE table_name = ? AND column_name = ?" ) ) {
            ps.setString( 1, table );
            ps.setString( 2, column );
            try ( ResultSet rs = ps.executeQuery() ) {
                assertTrue( rs.next(), table + "." + column + " must exist in test schema" );
                assertEquals( expectedType, rs.getString( 1 ),
                    table + "." + column + " has unexpected type" );
            }
        }
    }
}
