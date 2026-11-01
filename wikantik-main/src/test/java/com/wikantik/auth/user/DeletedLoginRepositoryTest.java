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
package com.wikantik.auth.user;

import com.wikantik.jdbc.Jdbc;
import com.wikantik.jdbc.testing.FaultInjectingDataSource;
import com.wikantik.jdbc.testing.MigrationApplier;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresPostgres
class DeletedLoginRepositoryTest {

    private DeletedLoginRepository repo;

    @BeforeEach
    void setUp() {
        PostgresTestDb.truncate( "deleted_logins" );
        repo = new DeletedLoginRepository( new Jdbc( PostgresTestDb.createDataSource() ) );
    }

    @Test
    void recordedLoginIsDeletedAndOthersAreNot() throws Exception {
        assertFalse( repo.isDeleted( "alice" ) );
        repo.record( "alice" );
        assertTrue( repo.isDeleted( "alice" ) );
        assertFalse( repo.isDeleted( "bob" ) );
        assertTrue( repo.isDeleted( "Alice" ), "compared case-insensitively, like live login uniqueness" );
        assertFalse( repo.isDeleted( null ) );
    }

    @Test
    void recordingTwiceIsANoOpAndAllListsEveryLogin() throws Exception {
        repo.record( "alice" );
        assertDoesNotThrow( () -> repo.record( "alice" ) );
        repo.record( "bob@example.com" );
        assertEquals( Set.of( "alice", "bob@example.com" ), repo.all() );
    }

    @Test
    void aFailedWriteIsNotSwallowed() {
        final FaultInjectingDataSource faulty = new FaultInjectingDataSource( PostgresTestDb.createDataSource() );
        faulty.failOn( 1, new RuntimeException( "boom" ) );
        final DeletedLoginRepository failing = new DeletedLoginRepository( new Jdbc( faulty ) );

        assertThrows( RuntimeException.class, () -> failing.record( "alice" ) );
    }

    @Test
    void migrationV065IsANoOpWhenReapplied() throws Exception {
        repo.record( "alice" );
        final Path file = MigrationApplier.listMigrations( MigrationApplier.findRepoRoot().resolve( "bin/db/migrations" ) )
                .stream().filter( p -> MigrationApplier.versionNumber( p ) == 65 ).findFirst().orElseThrow();
        final String sql = MigrationApplier.substituteAppUser( Files.readString( file ), PostgresTestDb.getUsername() );
        try ( Connection conn = PostgresTestDb.createDataSource().getConnection(); Statement st = conn.createStatement() ) {
            st.execute( sql );
            st.execute( sql );
        }
        assertTrue( repo.isDeleted( "alice" ), "re-applying must not drop or change rows" );
    }
}
