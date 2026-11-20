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

import com.wikantik.api.core.Session;
import com.wikantik.auth.authorize.Role;
import com.wikantik.auth.authorize.WebAuthorizer;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoleInjectorTest {

    private static final Role A = new Role( "A" );
    private static final Role B = new Role( "B" );

    private final Session session = mock( Session.class );
    private final HttpServletRequest request = mock( HttpServletRequest.class );
    private final List< Principal > added = new ArrayList<>();

    @Test
    void addsRolesTheAuthorizerSaysTheSessionHolds() {
        final Authorizer authorizer = mock( Authorizer.class );
        when( authorizer.getRoles() ).thenReturn( new Principal[] { A, B } );
        when( authorizer.isUserInRole( session, A ) ).thenReturn( false );
        when( authorizer.isUserInRole( session, B ) ).thenReturn( true );

        RoleInjector.inject( authorizer, session, request, added::add );

        assertEquals( List.of( B ), added );
    }

    @Test
    void plainAuthorizerIgnoresTheRoleRequest() {
        final Authorizer authorizer = mock( Authorizer.class );
        when( authorizer.getRoles() ).thenReturn( new Principal[] { A } );

        RoleInjector.inject( authorizer, session, request, added::add );

        assertTrue( added.isEmpty() );
    }

    @Test
    void webAuthorizerConsultsContainerRolesWhenARequestIsGiven() {
        final WebAuthorizer authorizer = mock( WebAuthorizer.class );
        when( authorizer.getRoles() ).thenReturn( new Principal[] { A, B } );
        when( authorizer.isUserInRole( request, A ) ).thenReturn( true );
        when( authorizer.isUserInRole( session, B ) ).thenReturn( true );

        RoleInjector.inject( authorizer, session, request, added::add );

        assertEquals( List.of( A, B ), added );
    }

    @Test
    void webAuthorizerWithNullRequestConsultsAuthorizerOnly() {
        final WebAuthorizer authorizer = mock( WebAuthorizer.class );
        when( authorizer.getRoles() ).thenReturn( new Principal[] { A } );
        when( authorizer.isUserInRole( request, A ) ).thenReturn( true );

        RoleInjector.inject( authorizer, session, null, added::add );

        assertTrue( added.isEmpty() );
    }
}
