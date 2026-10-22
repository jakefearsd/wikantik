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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V062 adds the profiling-run indexes and is a no-op when re-applied. */
@RequiresPostgres
class IoProfileIndexesMigrationTest {

    private static final List< String > INDEXES = List.of( "ix_users_uid", "ix_drift_snapshot_counts_sweep",
            "ix_retrieval_runs_mode_started", "ix_kg_proposals_judge_pending" );

    @Test
    void indexesExistAndReapplyIsNoOp() throws Exception {
        final DataSource ds = PostgresTestDb.createDataSource();
        final String sql = Files.readString( repoRoot().resolve( "bin/db/migrations/V062__io_profile_indexes.sql" ) );
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.execute( sql );
            s.execute( sql );
            for ( final String idx : INDEXES ) {
                try ( ResultSet rs = s.executeQuery( "SELECT count(*) FROM pg_indexes WHERE indexname = '" + idx + "'" ) ) {
                    assertTrue( rs.next() );
                    assertEquals( 1, rs.getInt( 1 ), idx );
                }
            }
            try ( ResultSet rs = s.executeQuery( "SELECT indexdef FROM pg_indexes WHERE indexname = 'ix_kg_proposals_judge_pending'" ) ) {
                assertTrue( rs.next() );
                assertTrue( rs.getString( 1 ).contains( "machine_status IS NULL" ), "must stay partial" );
            }
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
