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
package com.wikantik.kgpolicy;

import com.wikantik.PostgresTestContainer;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.knowledge.JdbcKnowledgeRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Smoke test for the {@link KgInclusionFilter} read-path splice.
 *
 * <p>Uses a real PostgreSQL Testcontainer so the filter SQL is validated
 * against the actual database engine. The test is skipped gracefully when
 * Docker is unavailable (e.g. CI without a Docker daemon).</p>
 */
@Testcontainers( disabledWithoutDocker = true )
class KgInclusionFilterIntegrationTest {

    private static DataSource dataSource;
    private JdbcKnowledgeRepository repo;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void setUp() throws Exception {
        try( final Connection conn = dataSource.getConnection() ) {
            conn.createStatement().execute( "DELETE FROM kg_excluded_pages" );
            conn.createStatement().execute( "DELETE FROM kg_edges" );
            conn.createStatement().execute( "DELETE FROM kg_proposals" );
            conn.createStatement().execute( "DELETE FROM kg_rejections" );
            conn.createStatement().execute( "DELETE FROM kg_nodes" );
        }
        repo = new JdbcKnowledgeRepository( dataSource );
    }

    /**
     * Inserts two nodes on different source pages. Before exclusion both should
     * surface; after excluding one page only the non-excluded node should appear.
     */
    @Test
    void queryNodes_excludesPageInKgExcludedPages() throws Exception {
        // Insert two nodes on different pages
        repo.upsertNode( "Alpha", "concept", "Alpha.md", Provenance.HUMAN_AUTHORED, Map.of() );
        repo.upsertNode( "Beta",  "concept", "Beta.md",  Provenance.HUMAN_AUTHORED, Map.of() );

        // Both nodes visible before any exclusion
        final List< com.wikantik.api.knowledge.KgNode > before =
            repo.queryNodes( null, null, 100, 0 );
        assertEquals( 2, before.size(),
            "Both nodes must be visible before any page is excluded" );

        // Exclude Alpha.md
        excludePage( "Alpha.md" );

        // Only Beta survives
        final List< com.wikantik.api.knowledge.KgNode > after =
            repo.queryNodes( null, null, 100, 0 );
        assertEquals( 1, after.size(),
            "Excluded-page node must not appear in query results" );
        assertEquals( "Beta", after.get( 0 ).name(),
            "The surviving node must be Beta (from non-excluded page)" );
    }

    /**
     * Verifies the filter SQL itself is valid PostgreSQL by executing the raw
     * fragment directly. Uses {@link KgInclusionFilter#NODE_FILTER_JOIN} and
     * {@link KgInclusionFilter#NODE_FILTER_WHERE} in a hand-assembled query so
     * even if the service layer changes, this test exercises the constants.
     */
    @Test
    void nodeFilterSql_isValidPostgres_andExcludesCorrectRow() throws Exception {
        // Arrange
        repo.upsertNode( "Gamma", "concept", "Gamma.md", Provenance.HUMAN_AUTHORED, Map.of() );
        repo.upsertNode( "Delta", "concept", "Delta.md", Provenance.HUMAN_AUTHORED, Map.of() );

        // Use the raw filter constants
        final String sql = "SELECT n.name FROM kg_nodes n "
            + KgInclusionFilter.NODE_FILTER_JOIN
            + " WHERE " + KgInclusionFilter.NODE_FILTER_WHERE
            + " ORDER BY n.name";

        // Before exclusion: both rows
        final List< String > before = queryNames( sql );
        assertEquals( List.of( "Delta", "Gamma" ), before,
            "Before exclusion both nodes must appear" );

        // Exclude Gamma.md
        excludePage( "Gamma.md" );

        // After exclusion: only Delta
        final List< String > after = queryNames( sql );
        assertEquals( List.of( "Delta" ), after,
            "After exclusion only the non-excluded node must appear" );
    }

    // ---- helpers ----

    private void excludePage( final String pageName ) throws Exception {
        try( final Connection conn = dataSource.getConnection();
             final PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO kg_excluded_pages (page_name, reason) VALUES (?, 'page_override') "
                 + "ON CONFLICT DO NOTHING" ) ) {
            ps.setString( 1, pageName );
            ps.executeUpdate();
        }
    }

    private List< String > queryNames( final String sql ) throws Exception {
        final List< String > names = new java.util.ArrayList<>();
        try( final Connection conn = dataSource.getConnection();
             final PreparedStatement ps = conn.prepareStatement( sql );
             final java.sql.ResultSet rs = ps.executeQuery() ) {
            while( rs.next() ) {
                names.add( rs.getString( 1 ) );
            }
        }
        return names;
    }
}
