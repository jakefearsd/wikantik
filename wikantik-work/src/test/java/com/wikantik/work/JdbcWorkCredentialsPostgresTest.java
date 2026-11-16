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
package com.wikantik.work;

import com.wikantik.jdbc.testing.FaultInjectingDataSource;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An agent's own credential: issued once, stored as a hash, at most one live per agent. */
@RequiresPostgres
class JdbcWorkCredentialsPostgresTest {

    private DataSource ds;
    private JdbcWorkStore store;
    private long agent;

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        store = new JdbcWorkStore( ds );
        agent = store.registerAgent( "codex", "", "", List.of(), "ana" ).id();
    }

    private Optional< WorkCredentialStore.Live > find( final String token ) throws SQLException {
        return store.credentials().findLive( AgentTokens.hash( token ) );
    }

    @Test
    void issueReturnsATokenThatVerifies() throws SQLException {
        final WorkCredentialStore.Issued issued = store.credentials().issue( agent, "ana" );
        assertTrue( AgentTokens.looksLikeAgentToken( issued.token() ) );
        assertEquals( Optional.of( new WorkCredentialStore.Live( issued.credentialId(), agent ) ), find( issued.token() ) );
        assertEquals( 1, WorkAgentFixtures.scalar( ds, "SELECT count(*) FROM work_agent_credential WHERE agent_id = "
            + agent + " AND created_by = 'ana'" ) );
    }

    @Test
    void issuingAgainRevokesThePreviousOne() throws SQLException {
        final WorkCredentialStore.Issued first = store.credentials().issue( agent, "ana" );
        final WorkCredentialStore.Issued second = store.credentials().issue( agent, "ana" );
        assertNotEquals( first.token(), second.token() );
        assertTrue( find( first.token() ).isEmpty(), "aRevokedTokenIsNotFound" );
        assertTrue( find( second.token() ).isPresent() );
        assertEquals( 1, WorkAgentFixtures.scalar( ds,
            "SELECT count(*) FROM work_agent_credential WHERE revoked_at IS NULL" ) );
    }

    @Test
    void revokeAllLeavesNothingLive() throws SQLException {
        final WorkCredentialStore.Issued issued = store.credentials().issue( agent, "ana" );
        assertEquals( 1, store.credentials().revokeAll( agent ) );
        assertTrue( find( issued.token() ).isEmpty() );
        assertEquals( 0, store.credentials().revokeAll( agent ), "nothing left to revoke" );
    }

    @Test
    void touchWritesAtMostOncePerMinute() throws SQLException {
        final long id = store.credentials().issue( agent, "ana" ).credentialId();
        store.credentials().touch( id );
        final long first = WorkAgentFixtures.scalar( ds,
            "SELECT (extract(epoch FROM last_used_at) * 1000000)::bigint FROM work_agent_credential WHERE id = " + id );
        store.credentials().touch( id );
        assertEquals( first, WorkAgentFixtures.scalar( ds,
            "SELECT (extract(epoch FROM last_used_at) * 1000000)::bigint FROM work_agent_credential WHERE id = " + id ),
            "a second use inside the minute writes nothing" );
        WorkAgentFixtures.exec( ds, "UPDATE work_agent_credential SET last_used_at = NOW() - INTERVAL '2 minutes' "
            + "WHERE id = " + id );
        store.credentials().touch( id );
        assertTrue( WorkAgentFixtures.scalar( ds,
            "SELECT count(*) FROM work_agent_credential WHERE id = " + id
                + " AND last_used_at > NOW() - INTERVAL '10 seconds'" ) == 1 );
    }

    @Test
    void onlyTheHashIsStored() throws SQLException {
        final String token = store.credentials().issue( agent, "ana" ).token();
        assertEquals( 0, WorkAgentFixtures.scalar( ds, "SELECT count(*) FROM work_agent_credential c "
            + "WHERE row_to_json( c )::text LIKE '%" + token + "%'" ) );
        assertEquals( 1, WorkAgentFixtures.scalar( ds, "SELECT count(*) FROM work_agent_credential WHERE token_hash = '"
            + AgentTokens.hash( token ) + "'" ) );
    }

    @Test
    void issueKeepsTheOldCredentialWhenTheInsertFails() throws SQLException {
        final WorkCredentialStore.Issued first = store.credentials().issue( agent, "ana" );
        final FaultInjectingDataSource faulty = new FaultInjectingDataSource( ds );
        faulty.failOn( 2, new RuntimeException( "boom: insert" ) ); // 1 revoke the live one, 2 insert the new one
        assertThrows( RuntimeException.class, () -> new JdbcWorkStore( faulty ).credentials().issue( agent, "ana" ) );
        assertEquals( 1, faulty.rollbacks() );
        assertTrue( find( first.token() ).isPresent(), "the old credential still works" );
    }

    @Test
    void aStoreFailureIsNotAnEmptyResult() {
        final FaultInjectingDataSource faulty = new FaultInjectingDataSource( ds );
        faulty.failOn( 1, new IllegalStateException( "down" ) );
        assertThrows( IllegalStateException.class,
            () -> new JdbcWorkStore( faulty ).credentials().findLive( "anything" ),
            "a failed lookup surfaces; it is never answered as an unknown token" );
    }
}
