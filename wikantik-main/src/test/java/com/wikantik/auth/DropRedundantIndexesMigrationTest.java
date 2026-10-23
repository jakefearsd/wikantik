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
package com.wikantik.auth;

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V063 drops redundant/unusable indexes, keeps the covering ones, and is a no-op when re-applied. */
@RequiresPostgres
class DropRedundantIndexesMigrationTest {

    private static final List< String > DROPPED = List.of(
            "idx_kg_content_chunks_page_name", "kg_nodes_tier_idx", "idx_svs_date",
            "idx_chunk_entity_mentions_chunk", "idx_kg_edges_source", "idx_hub_proposals_hub",
            "ix_retrieval_runs_mode", "idx_citations_source", "idx_audit_log_seq",
            "idx_comment_mentions_comment_id", "idx_connector_synced_item_connector",
            "idx_kg_nodes_properties", "idx_kg_content_chunks_content_hash",
            "ix_canonical_ids_type", "ix_canonical_ids_cluster", "kg_judge_timeouts_content_sha_idx" );

    /** The indexes that make the dropped ones redundant — must survive. */
    private static final List< String > KEPT = List.of(
            "kg_content_chunks_page_index_uniq", "kg_nodes_tier_name_idx", "search_visibility_snapshot_pkey",
            "chunk_entity_mentions_pkey", "kg_edges_source_id_target_id_relationship_type_key",
            "hub_proposals_hub_name_page_name_key", "ix_retrieval_runs_mode_started", "uq_citation",
            "audit_log_pkey", "uq_comment_mentions", "connector_synced_item_pkey" );

    @Test
    void droppedIndexesAreGoneCoveringOnesRemainAndReapplyIsNoOp() throws Exception {
        final DataSource ds = PostgresTestDb.createDataSource();
        final String sql = Files.readString( repoRoot().resolve( "bin/db/migrations/V063__drop_redundant_indexes.sql" ) );
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.execute( sql );
            s.execute( sql );
            for ( final String idx : DROPPED ) {
                assertEquals( 0, count( s, idx ), idx + " should be dropped" );
            }
            for ( final String idx : KEPT ) {
                assertTrue( count( s, idx ) >= 1, idx + " must remain" );
            }
        }
    }

    @Test
    void auditChainHeadIsServedByThePrimaryKey() throws Exception {
        final DataSource ds = PostgresTestDb.createDataSource();
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.execute( "SET enable_seqscan = off" );
            final StringBuilder plan = new StringBuilder();
            try ( ResultSet rs = s.executeQuery(
                    "EXPLAIN SELECT seq, row_hash FROM audit_log ORDER BY seq DESC LIMIT 1" ) ) {
                while ( rs.next() ) {
                    plan.append( rs.getString( 1 ) ).append( '\n' );
                }
            }
            assertFalse( plan.toString().contains( "Seq Scan" ), plan.toString() );
            assertTrue( plan.toString().contains( "Index" ), plan.toString() );
        }
    }

    private static int count( final Statement s, final String index ) throws Exception {
        try ( ResultSet rs = s.executeQuery( "SELECT count(*) FROM pg_indexes WHERE indexname = '" + index + "'" ) ) {
            assertTrue( rs.next() );
            return rs.getInt( 1 );
        }
    }

    private static Path repoRoot() {
        Path dir = Paths.get( System.getProperty( "user.dir" ) ).toAbsolutePath();
        while ( dir != null && !Files.isDirectory( dir.resolve( "bin/db/migrations" ) ) ) {
            dir = dir.getParent();
        }
        if ( dir == null ) {
            throw new IllegalStateException( "Could not locate repo root (bin/db/migrations)" );
        }
        return dir;
    }
}
