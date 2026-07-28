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
package com.wikantik.auth.subsystem.verify;

import com.wikantik.api.core.Engine;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.Authorizer;
import com.wikantik.auth.authorize.Role;
import com.wikantik.auth.authorize.WebContainerAuthorizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ContainerRoleVerifier} — the {@code web.xml} role/URL
 * constraint table rendered on the admin security-verification page.
 */
class ContainerRoleVerifierTest {

    /** The seven fixed rows the verifier always renders. */
    private static final int ACTION_ROWS = 7;

    private AuthorizationManager   authMgr;
    private WebContainerAuthorizer wca;
    private ContainerRoleVerifier  verifier;

    private static final Role ADMIN = new Role( "Admin" );
    private static final Role EDITOR = new Role( "Editor" );

    @BeforeEach
    void setUp() throws Exception {
        authMgr = mock( AuthorizationManager.class );
        wca     = mock( WebContainerAuthorizer.class );
        when( authMgr.getAuthorizer() ).thenReturn( wca );
        verifier = new ContainerRoleVerifier( mock( Engine.class ), authMgr );
    }

    // ------------------------------------------------------------ webContainerRoles

    @Test
    void webContainerRoles_delegatesToWebContainerAuthorizer() throws Exception {
        when( wca.getRoles() ).thenReturn( new Principal[] { ADMIN, EDITOR } );

        assertArrayEqualsByName( new Principal[] { ADMIN, EDITOR }, verifier.webContainerRoles() );
    }

    @Test
    void webContainerRoles_withNonWebAuthorizer_returnsEmptyArray() throws Exception {
        when( authMgr.getAuthorizer() ).thenReturn( mock( Authorizer.class ) );

        assertEquals( 0, verifier.webContainerRoles().length );
    }

    // ------------------------------------------------------------ containerRoleTable

    @Test
    void containerRoleTable_withNonWebAuthorizer_throwsIllegalState() throws Exception {
        when( authMgr.getAuthorizer() ).thenReturn( mock( Authorizer.class ) );

        final IllegalStateException e =
                assertThrows( IllegalStateException.class, () -> verifier.containerRoleTable() );
        assertTrue( e.getMessage().contains( "WebContainerAuthorizer" ) );
    }

    @Test
    void containerRoleTable_rendersOneRowPerActionPlusHeaders() throws Exception {
        when( wca.getRoles() ).thenReturn( new Principal[] { ADMIN } );

        final String html = verifier.containerRoleTable();

        assertTrue( html.startsWith( "<table class=\"wikitable\" border=\"1\">" ), html );
        assertTrue( html.endsWith( "</table>\n" ), html );
        // Two header rows + one row per action.
        assertEquals( ACTION_ROWS + 2, countOccurrences( html, "<tr>" ) );
        assertTrue( html.contains( "<th>Anonymous</th>" ) );
        assertTrue( html.contains( "<th>Admin</th>" ) );
        for ( final String action : new String[] { "View pages", "Edit pages", "Delete pages" } ) {
            assertTrue( html.contains( "<td>" + action + "</td>" ), "missing action row: " + action );
        }
    }

    @Test
    void containerRoleTable_colspanCoversAnonymousColumnPlusEveryRole() throws Exception {
        when( wca.getRoles() ).thenReturn( new Principal[] { ADMIN, EDITOR } );

        final String html = verifier.containerRoleTable();

        // 2 roles + the Anonymous column == 3. A naive string append of
        // roles.length followed by 1 would render the nonsense colspan "21".
        assertTrue( html.contains( "colspan=\"3\">Roles</th>" ),
                "colspan must be roles.length + 1; got: "
                        + html.substring( html.indexOf( "colspan=" ),
                                          html.indexOf( "colspan=" ) + 30 ) );
    }

    @Test
    void unconstrainedPathForAll_marksAnonymousAllowedAndGreen() throws Exception {
        when( wca.getRoles() ).thenReturn( new Principal[] { ADMIN } );
        // Nothing is constrained => anonymous may do everything.
        when( wca.isConstrained( org.mockito.ArgumentMatchers.anyString(),
                                 org.mockito.ArgumentMatchers.any( Role.class ) ) ).thenReturn( false );

        final String html = verifier.containerRoleTable();

        assertTrue( html.contains( "ALLOW: /attach/* Anonymous" ) );
        assertFalse( html.contains( "DENY: /attach/* Anonymous" ) );
        assertTrue( html.contains( "bgcolor=\"#c0ffc0\"" ) );
    }

    @Test
    void constrainedPathForAll_marksAnonymousDeniedAndDefersToPerRoleConstraint() throws Exception {
        when( wca.getRoles() ).thenReturn( new Principal[] { ADMIN } );
        // /admin/* is locked down for everyone-but-Admin.
        when( wca.isConstrained( org.mockito.ArgumentMatchers.anyString(),
                                 org.mockito.ArgumentMatchers.any( Role.class ) ) ).thenReturn( false );
        when( wca.isConstrained( "/admin/*", Role.ALL ) ).thenReturn( true );
        when( wca.isConstrained( "/admin/*", ADMIN ) ).thenReturn( true );

        final String html = verifier.containerRoleTable();

        assertTrue( html.contains( "DENY: /admin/* Anonymous" ) );
        // The Admin column for /admin/* stays an ALLOW because the role is constrained to it.
        assertTrue( html.contains( "ALLOW: /admin/* " + Role.class.getName() + " &quot;Admin&quot;" ), html );
        assertTrue( html.contains( "bgcolor=\"#ffc0c0\"" ) );
    }

    @Test
    void roleDeniedOnConstrainedPath_rendersDenyCell() throws Exception {
        when( wca.getRoles() ).thenReturn( new Principal[] { EDITOR } );
        when( wca.isConstrained( "/admin/*", Role.ALL ) ).thenReturn( true );
        when( wca.isConstrained( "/admin/*", EDITOR ) ).thenReturn( false );

        final String html = verifier.containerRoleTable();

        assertTrue( html.contains( "DENY: /admin/* " + Role.class.getName() + " &quot;Editor&quot;" ), html );
    }

    @Test
    void noContainerRoles_stillRendersEveryActionRow() throws Exception {
        when( wca.getRoles() ).thenReturn( new Principal[0] );

        final String html = verifier.containerRoleTable();

        assertTrue( html.contains( "colspan=\"1\">Roles</th>" ), html );
        assertEquals( ACTION_ROWS + 2, countOccurrences( html, "<tr>" ) );
    }

    // ------------------------------------------------------------ helpers

    private static int countOccurrences( final String haystack, final String needle ) {
        int count = 0;
        int idx = haystack.indexOf( needle );
        while ( idx >= 0 ) {
            count++;
            idx = haystack.indexOf( needle, idx + needle.length() );
        }
        return count;
    }

    private static void assertArrayEqualsByName( final Principal[] expected, final Principal[] actual ) {
        assertEquals( expected.length, actual.length );
        for ( int i = 0; i < expected.length; i++ ) {
            assertEquals( expected[ i ].getName(), actual[ i ].getName() );
        }
    }
}
