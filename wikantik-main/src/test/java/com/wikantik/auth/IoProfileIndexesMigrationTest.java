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

    // V062 also added ix_kg_proposals_judge_pending; V070 dropped kg_proposals, so V062 can no longer be
    // re-applied to a fully migrated schema (its CREATE INDEX names a missing table). Fresh installs apply V062
    // before V070, which the migration chain under PostgresTestDb exercises; this checks what V062 left behind.
    private static final List< String > INDEXES = List.of( "ix_users_uid", "ix_retrieval_runs_mode_started" );

    @Test
    void indexesExistAfterTheMigrationChain() throws Exception {
        final DataSource ds = PostgresTestDb.createDataSource();
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            for ( final String idx : INDEXES ) {
                try ( ResultSet rs = s.executeQuery( "SELECT count(*) FROM pg_indexes WHERE indexname = '" + idx + "'" ) ) {
                    assertTrue( rs.next() );
                    assertEquals( 1, rs.getInt( 1 ), idx );
                }
            }
        }
    }
}
