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

import com.wikantik.TestEngine;
import com.wikantik.api.core.Session;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AuthorKeysTest {

    private static Session session( final boolean authenticated, final String login, final String user ) {
        final Session s = Mockito.mock( Session.class );
        Mockito.when( s.isAuthenticated() ).thenReturn( authenticated );
        Mockito.when( s.getLoginPrincipal() ).thenReturn( login == null ? null : new WikiPrincipal( login ) );
        Mockito.when( s.getUserPrincipal() ).thenReturn( user == null ? null : new WikiPrincipal( user ) );
        return s;
    }

    @Test
    void anAuthenticatedSessionYieldsTheLoginNotTheFullName() {
        assertEquals( "jane@example.com", AuthorKeys.authorKeyFor( session( true, "jane@example.com", "Jane Doe" ) ) );
    }

    @Test
    void anAuthenticatedSessionWithoutAUsableLoginFallsBackToTheUserPrincipal() {
        assertEquals( "Jane Doe", AuthorKeys.authorKeyFor( session( true, null, "Jane Doe" ) ) );
        assertEquals( "Jane Doe", AuthorKeys.authorKeyFor( session( true, "  ", "Jane Doe" ) ) );
    }

    @Test
    void anonymousAndAssertedSessionsKeepTheirUserPrincipal() {
        assertEquals( "192.0.2.7", AuthorKeys.authorKeyFor( session( false, "Guest", "192.0.2.7" ) ) );
        assertEquals( "Asserted Alice", AuthorKeys.authorKeyFor( session( false, "Asserted Alice", "Asserted Alice" ) ) );
    }

    @Test
    void aNullSessionIsGuestAndAPrincipalLessSessionIsNull() {
        assertEquals( "Guest", AuthorKeys.authorKeyFor( null ) );
        assertNull( AuthorKeys.authorKeyFor( session( false, null, null ) ) );
    }

    @Test
    void aRealLoggedInSessionYieldsTheLoginWhileItsUserPrincipalIsTheFullName() throws Exception {
        final TestEngine engine = TestEngine.build();
        try {
            final Session admin = engine.adminSession();
            assertEquals( "Administrator", admin.getUserPrincipal().getName(), "the seeded admin's full name" );
            assertEquals( Users.ADMIN, AuthorKeys.authorKeyFor( admin ) );
        } finally {
            engine.stop();
        }
    }
}
