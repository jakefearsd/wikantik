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
package com.wikantik.auth.apikeys;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the fail-soft contract when the JDBC DataSource is broken — e.g.
 * the database has been restarted or the connection pool is exhausted while a
 * request is in flight. Every read path must return empty/false rather than
 * propagating an exception up to the MCP / tool-server access filters, because
 * those filters are the only auth boundary and an uncaught exception there
 * would surface as HTTP 500 on every authenticated request, effectively
 * taking both servers offline at the first database blip.
 *
 * <p>The one exception is {@link ApiKeyService#generate} — that's an admin-only
 * write path and there is no safe "empty" fallback, so it must throw
 * {@link IllegalStateException} so the REST layer can render 500 with a
 * meaningful error message.</p>
 */
class ApiKeyServiceFailureTest {

    private static DataSource failingDataSource() throws SQLException {
        final DataSource ds = Mockito.mock( DataSource.class );
        Mockito.when( ds.getConnection() )
                .thenThrow( new SQLException( "Connection refused: database unreachable" ) );
        return ds;
    }

    @Test
    void verifyReturnsEmptyWhenDataSourceThrows() throws Exception {
        final ApiKeyService svc = new ApiKeyService( failingDataSource() );

        // Access filter will call this on every authenticated request — it must
        // degrade to "unknown key" rather than crash the request pipeline.
        final Optional< ApiKeyService.Record > result = svc.verify( "wkk_whatever" );
        assertTrue( result.isEmpty(),
                "verify must swallow SQLException and return empty so the filter "
                        + "falls through to legacy key list (or rejects with 403) "
                        + "instead of 500ing the whole request" );
    }

    @Test
    void listReturnsEmptyListWhenDataSourceThrows() throws Exception {
        final ApiKeyService svc = new ApiKeyService( failingDataSource() );

        // Admin UI polls this; must render an empty table, not explode.
        final List< ApiKeyService.Record > result = svc.list();
        assertEquals( List.of(), result,
                "list must return empty (not throw) so the admin UI can show "
                        + "'no keys / database unreachable' instead of a stack trace" );
    }

    @Test
    void revokeReturnsFalseWhenDataSourceThrows() throws Exception {
        final ApiKeyService svc = new ApiKeyService( failingDataSource() );

        // Admin clicks Revoke while the DB is out — the button must fail
        // gracefully so the operator can retry, not 500.
        assertFalse( svc.revoke( 42, "admin" ),
                "revoke must report no-op on SQLException so the admin UI renders "
                        + "a clean 'revoke failed' rather than an uncaught exception" );
    }

    @Test
    void generateThrowsIllegalStateWhenDataSourceFails() throws Exception {
        final ApiKeyService svc = new ApiKeyService( failingDataSource() );

        // Writes have no fail-soft fallback — the key does not exist, so we
        // must surface the failure. AdminApiKeysResource catches this and
        // returns 500 with a message.
        final IllegalStateException ex = assertThrows( IllegalStateException.class,
                () -> svc.generate( "alice", "laptop", ApiKeyService.Scope.ALL, "admin" ) );
        assertTrue( ex.getMessage().contains( "generation failed" ),
                "Exception message must mention generation for operator clarity: " + ex.getMessage() );
        assertTrue( ex.getCause() instanceof SQLException,
                "Cause must be the underlying SQLException so stack traces include the root" );
    }

    @Test
    void verifyUnknownTokenDoesNotEvenHitDatabaseOnPathologicalInput() throws Exception {
        // Guard rail: a null/empty bearer token must short-circuit before
        // we even open a connection, otherwise attackers can turn failed
        // DB lookups into a connection-pool DOS.
        final DataSource ds = Mockito.mock( DataSource.class );
        final ApiKeyService svc = new ApiKeyService( ds );

        assertTrue( svc.verify( null ).isEmpty() );
        assertTrue( svc.verify( "" ).isEmpty() );

        Mockito.verify( ds, Mockito.never() ).getConnection();
    }
}
