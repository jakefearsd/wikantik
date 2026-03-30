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

import com.wikantik.MockEngineBuilder;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.auth.authorize.Group;
import com.wikantik.auth.authorize.GroupDatabase;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.auth.authorize.Role;
import com.wikantik.auth.authorize.WebContainerAuthorizer;
import com.wikantik.auth.permissions.GroupPermission;
import com.wikantik.auth.permissions.WikiPermission;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.security.Principal;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Additional unit tests for {@link SecurityVerifier} covering branches not
 * exercised by {@link SecurityVerifierTest}: {@code containerRoleTable()},
 * {@code webContainerRoles()} with a WebContainerAuthorizer, {@code policyRoleTable()}
 * with roles present, {@code getFileFromProperty()}, and the {@code verifyGroupDatabase()}
 * null-groupManager branch.
 */
class SecurityVerifierCITest2 {

    private Engine engine;
    private Session session;
    private AuthorizationManager authorizationManager;
    private GroupManager groupManager;
    private UserManager userManager;
    private GroupDatabase groupDatabase;
    private UserDatabase userDatabase;
    private Properties properties;

    @BeforeEach
    void setUp() throws Exception {
        session = mock( Session.class );
        authorizationManager = mock( AuthorizationManager.class );
        groupManager = mock( GroupManager.class );
        userManager = mock( UserManager.class );
        groupDatabase = mock( GroupDatabase.class );
        userDatabase = mock( UserDatabase.class );

        // Plain (non-WebContainer) authorizer by default
        final Authorizer authorizer = mock( Authorizer.class );
        when( authorizationManager.getAuthorizer() ).thenReturn( authorizer );
        when( authorizer.getRoles() ).thenReturn( new Principal[0] );

        // Group DB: success path — add then delete
        when( groupManager.getGroupDatabase() ).thenReturn( groupDatabase );
        final Group defaultGroup = mock( Group.class );
        when( groupManager.parseGroup( anyString(), eq( "" ), eq( true ) ) ).thenReturn( defaultGroup );
        when( groupDatabase.groups() )
                .thenReturn( new Group[0] )
                .thenReturn( new Group[]{ defaultGroup } )
                .thenReturn( new Group[0] );

        // User DB: success path
        when( userManager.getUserDatabase() ).thenReturn( userDatabase );
        final UserProfile defaultProfile = mock( UserProfile.class );
        when( userDatabase.newProfile() ).thenReturn( defaultProfile );
        when( userDatabase.getWikiNames() )
                .thenReturn( new Principal[0] )
                .thenReturn( new Principal[1] )
                .thenReturn( new Principal[0] );

        properties = new Properties();
        properties.setProperty( AuthenticationManager.PROP_LOGIN_MODULE,
                "com.wikantik.auth.login.UserDatabaseLoginModule" );

        final URL policyURL = getClass().getClassLoader().getResource( "wikantik.policy" );
        assertNotNull( policyURL, "Test wikantik.policy must exist on the classpath" );

        engine = MockEngineBuilder.engine()
                .with( AuthorizationManager.class, authorizationManager )
                .with( GroupManager.class, groupManager )
                .with( UserManager.class, userManager )
                .properties( properties )
                .build();
        when( engine.findConfigFile( AuthorizationManager.DEFAULT_POLICY ) ).thenReturn( policyURL );
    }

    private SecurityVerifier buildVerifier() {
        return new SecurityVerifier( engine, session, authorizationManager, groupManager, userManager );
    }

    // ---- webContainerRoles with WebContainerAuthorizer ----

    @Test
    void testWebContainerRoles_webContainerAuthorizer_returnsRoles() throws Exception {
        final WebContainerAuthorizer wca = mock( WebContainerAuthorizer.class );
        final Principal[] expectedRoles = new Principal[]{ new Role( "Admin" ), new Role( "Authenticated" ) };
        when( wca.getRoles() ).thenReturn( expectedRoles );
        when( authorizationManager.getAuthorizer() ).thenReturn( wca );

        final SecurityVerifier verifier = buildVerifier();
        final Principal[] roles = verifier.webContainerRoles();

        assertNotNull( roles );
        assertEquals( 2, roles.length );
    }

    // ---- containerRoleTable ----

    @Test
    void testContainerRoleTable_throwsWhenNotWebContainerAuthorizer() {
        // Default mock authorizer is not a WebContainerAuthorizer
        final SecurityVerifier verifier = buildVerifier();
        assertThrows( IllegalStateException.class, verifier::containerRoleTable );
    }

    @Test
    void testContainerRoleTable_returnsHtmlWhenWebContainerAuthorizer() throws Exception {
        final WebContainerAuthorizer wca = mock( WebContainerAuthorizer.class );
        when( wca.getRoles() ).thenReturn( new Principal[]{ new Role( "Admin" ) } );
        when( wca.isConstrained( anyString(), any() ) ).thenReturn( true );
        when( authorizationManager.getAuthorizer() ).thenReturn( wca );

        final SecurityVerifier verifier = buildVerifier();
        final String html = verifier.containerRoleTable();

        assertNotNull( html );
        assertTrue( html.contains( "<table" ), "Should produce HTML table" );
        assertTrue( html.contains( "Admin" ), "Should contain the role name" );
    }

    @Test
    void testContainerRoleTable_allowsAnonymousWhenPathNotConstrained() throws Exception {
        final WebContainerAuthorizer wca = mock( WebContainerAuthorizer.class );
        when( wca.getRoles() ).thenReturn( new Principal[0] );
        // All paths are not constrained for Role.ALL → anonymous is allowed
        when( wca.isConstrained( anyString(), eq( Role.ALL ) ) ).thenReturn( false );
        when( authorizationManager.getAuthorizer() ).thenReturn( wca );

        final SecurityVerifier verifier = buildVerifier();
        final String html = verifier.containerRoleTable();

        // bgcolor for green (allowed)
        assertTrue( html.contains( "bgcolor=\"#c0ffc0\"" ), "Unconstrained path should show green (ALLOW)" );
    }

    @Test
    void testContainerRoleTable_deniesAnonymousWhenPathConstrained() throws Exception {
        final WebContainerAuthorizer wca = mock( WebContainerAuthorizer.class );
        when( wca.getRoles() ).thenReturn( new Principal[0] );
        // All paths are constrained → anonymous is denied
        when( wca.isConstrained( anyString(), eq( Role.ALL ) ) ).thenReturn( true );
        when( authorizationManager.getAuthorizer() ).thenReturn( wca );

        final SecurityVerifier verifier = buildVerifier();
        final String html = verifier.containerRoleTable();

        assertTrue( html.contains( "bgcolor=\"#ffc0c0\"" ), "Constrained path should show red (DENY) for anonymous" );
    }

    // ---- policyRoleTable with roles ----

    @Test
    void testPolicyRoleTable_withRoles_containsRoleColumns() throws Exception {
        final WebContainerAuthorizer wca = mock( WebContainerAuthorizer.class );
        when( wca.getRoles() ).thenReturn( new Principal[]{ new Role( "Admin" ) } );
        when( authorizationManager.getAuthorizer() ).thenReturn( wca );

        final SecurityVerifier verifier = buildVerifier();
        final String html = verifier.policyRoleTable();

        // Since the policy file adds roles/principals, the table should have column headers
        assertNotNull( html );
        assertTrue( html.startsWith( "<table" ) );
        assertTrue( html.endsWith( "</table>" ) );
    }

    // ---- verifyStaticPermission with GroupPermission ----

    @Test
    void testVerifyStaticPermission_withGroupPermission() {
        when( authorizationManager.allowedByLocalPolicy( any(), any() ) ).thenReturn( true );

        final SecurityVerifier verifier = buildVerifier();
        final boolean result = verifier.verifyStaticPermission(
                new Role( "Admin" ),
                new GroupPermission( "test:Admin", "view" ) );

        assertTrue( result );
    }

    @Test
    void testVerifyStaticPermission_withWikiPermission_denied() {
        when( authorizationManager.allowedByLocalPolicy( any(), any() ) ).thenReturn( false );

        final SecurityVerifier verifier = buildVerifier();
        final boolean result = verifier.verifyStaticPermission(
                Role.ANONYMOUS,
                new WikiPermission( "test", "createGroups" ) );

        assertFalse( result );
    }

    // ---- getFileFromProperty ----

    @Test
    void testGetFileFromProperty_nullProperty_returnsNullAndAddsErrorMessage() {
        // Make sure the system property is absent
        System.clearProperty( "wikantik.test.nonexistent.property" );
        final SecurityVerifier verifier = buildVerifier();

        final java.io.File file = verifier.getFileFromProperty( "wikantik.test.nonexistent.property" );

        assertNull( file, "Should return null for a null system property" );
        verify( session ).addMessage(
                eq( "Error.wikantik.test.nonexistent.property" ),
                contains( "null" ) );
    }

    @Test
    void testGetFileFromProperty_propertyWithEqualsPrefix_stripsEquals() throws Exception {
        // Set a property value that starts with "="
        final String propKey = "wikantik.test.prop.equals";
        System.setProperty( propKey, "=/nonexistent/path/file.txt" );
        try {
            final SecurityVerifier verifier = buildVerifier();
            final java.io.File file = verifier.getFileFromProperty( propKey );

            assertNull( file, "Non-existent file should return null" );
            // The info message should have been added with the stripped path
            verify( session ).addMessage( eq( "Info." + propKey ), contains( "/nonexistent/path/file.txt" ) );
        } finally {
            System.clearProperty( propKey );
        }
    }

    @Test
    void testGetFileFromProperty_existingFile_returnsFileAndAddsInfoMessages() throws Exception {
        // Use a known file from the test classpath
        final URL knownUrl = getClass().getClassLoader().getResource( "wikantik.policy" );
        assertNotNull( knownUrl );
        final String propKey = "wikantik.test.prop.existing";
        System.setProperty( propKey, knownUrl.getPath() );
        try {
            final SecurityVerifier verifier = buildVerifier();
            final java.io.File file = verifier.getFileFromProperty( propKey );

            assertNotNull( file, "Existing file should be returned" );
            assertTrue( file.exists() );
            verify( session ).addMessage( eq( "Info." + propKey ), contains( "exists in the filesystem" ) );
        } finally {
            System.clearProperty( propKey );
        }
    }

    // ---- verifyGroupDatabase — db save succeeds but subsequent groups() count unchanged ----

    @Test
    void testVerifyGroupDatabase_addAndDeleteSucceedShowsConfigLooksFine() throws Exception {
        // This exercises the "configuration looks fine" message at the end of verifyGroupDatabase
        final Group testGroup = mock( Group.class );
        when( groupManager.parseGroup( anyString(), eq( "" ), eq( true ) ) ).thenReturn( testGroup );
        when( groupDatabase.groups() )
                .thenReturn( new Group[0] )
                .thenReturn( new Group[]{ testGroup } )
                .thenReturn( new Group[0] );

        buildVerifier();

        verify( session ).addMessage( eq( SecurityVerifier.INFO_GROUPS ),
                contains( "configuration looks fine" ) );
    }

    // ---- policyRoleTable — null permission (groupActions contains null) ----

    @Test
    void testPolicyRoleTable_nullPermissionRendersNbsp() {
        // The policyRoleTable() renders null groupAction slots as &nbsp;
        final SecurityVerifier verifier = buildVerifier();
        final String html = verifier.policyRoleTable();

        assertTrue( html.contains( "&nbsp;" ), "Null permissions should render as &nbsp;" );
    }
}
