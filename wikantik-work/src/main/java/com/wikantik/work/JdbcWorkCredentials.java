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

import com.wikantik.jdbc.Jdbc;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Optional;

/** {@link WorkCredentialStore} over {@code work_agent_credential}; composed into the store like the claim half. */
final class JdbcWorkCredentials implements WorkCredentialStore {

    private final Jdbc jdbc;
    private final SecureRandom random = new SecureRandom();

    JdbcWorkCredentials( final Jdbc jdbc ) {
        this.jdbc = jdbc;
    }

    @Override
    public Issued issue( final long agentId, final String createdBy ) throws SQLException {
        final String token = AgentTokens.generate( random );
        final long id = jdbc.inTransaction( conn -> {
            jdbc.update( conn, "UPDATE work_agent_credential SET revoked_at = NOW() "
                + "WHERE agent_id = ? AND revoked_at IS NULL", ps -> ps.setLong( 1, agentId ) );
            return jdbc.queryOne( conn, "INSERT INTO work_agent_credential (agent_id, token_hash, created_by) "
                    + "VALUES (?,?,?) RETURNING id",
                ps -> {
                    ps.setLong( 1, agentId );
                    ps.setString( 2, AgentTokens.hash( token ) );
                    ps.setString( 3, createdBy );
                },
                rs -> rs.getLong( 1 ) )
                .orElseThrow( () -> new SQLException( "INSERT work_agent_credential returned no row" ) );
        } );
        return new Issued( id, token );
    }

    @Override
    public Optional< Live > findLive( final String tokenHash ) throws SQLException {
        return jdbc.queryOne( "SELECT id, agent_id FROM work_agent_credential "
                + "WHERE token_hash = ? AND revoked_at IS NULL",
            ps -> ps.setString( 1, tokenHash ),
            rs -> new Live( rs.getLong( "id" ), rs.getLong( "agent_id" ) ) );
    }

    @Override
    public int revokeAll( final long agentId ) throws SQLException {
        return jdbc.update( "UPDATE work_agent_credential SET revoked_at = NOW() "
            + "WHERE agent_id = ? AND revoked_at IS NULL", ps -> ps.setLong( 1, agentId ) );
    }

    @Override
    public void touch( final long credentialId ) throws SQLException {
        jdbc.update( "UPDATE work_agent_credential SET last_used_at = NOW() WHERE id = ? "
                + "AND ( last_used_at IS NULL OR last_used_at < NOW() - INTERVAL '1 minute' )",
            ps -> ps.setLong( 1, credentialId ) );
    }
}
