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
import com.wikantik.auth.user.DummyUserDatabase;
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
 * Unit tests for {@link SecurityVerifier}. Uses the package-private constructor
 * to inject mock dependencies, verifying the security audit logic in isolation.
 */
class SecurityVerifierTest {

    private Engine engine;
    private Session session;
    private AuthorizationManager authorizationManager;
    private GroupManager groupManager;
    private UserManager userManager;
    private Authorizer authorizer;
    private GroupDatabase groupDatabase;
    private UserDatabase userDatabase;
    private Properties properties;

    @BeforeEach
    void setUp() throws Exception {
        session = mock( Session.class );
        authorizationManager = mock( AuthorizationManager.class );
        groupManager = mock( GroupManager.class );
        userManager = mock( UserManager.class );
        authorizer = mock( Authorizer.class );
        groupDatabase = mock( GroupDatabase.class );
        userDatabase = mock( UserDatabase.class );

        // Default authorizer setup
        when( authorizationManager.getAuthorizer() ).thenReturn( authorizer );
        when( authorizer.getRoles() ).thenReturn( new Principal[0] );

        // Default group manager setup: group add/save/delete all succeed
        when( groupManager.getGroupDatabase() ).thenReturn( groupDatabase );
        final Group defaultGroup = mock( Group.class );
        when( groupManager.parseGroup( anyString(), eq( "" ), eq( true ) ) ).thenReturn( defaultGroup );
        when( groupDatabase.groups() )
                .thenReturn( new Group[0] )                    // initial count
                .thenReturn( new Group[]{ defaultGroup } )     // after save
                .thenReturn( new Group[0] );                   // after delete

        // Default user manager setup: user add/save/delete all succeed
        when( userManager.getUserDatabase() ).thenReturn( userDatabase );
        final UserProfile defaultProfile = mock( UserProfile.class );
        when( userDatabase.newProfile() ).thenReturn( defaultProfile );
        when( userDatabase.getWikiNames() )
                .thenReturn( new Principal[0] )        // initial count
                .thenReturn( new Principal[1] )        // after save
                .thenReturn( new Principal[0] );       // after delete

        // Default properties with a valid login module
        properties = new Properties();
        properties.setProperty( AuthenticationManager.PROP_LOGIN_MODULE,
                "com.wikantik.auth.login.UserDatabaseLoginModule" );

        // Policy file from test resources
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

    /**
     * Helper that builds the SecurityVerifier using the injectable constructor.
     * The constructor runs all verify* methods, so the verifier is fully initialized after this call.
     */
    private SecurityVerifier buildVerifier() {
        return new SecurityVerifier( engine, session, authorizationManager, groupManager, userManager );
    }

    // ---- JAAS verification tests ----

    @Test
    void testVerifyJaas_validLoginModule_addsInfoMessages() {
        final SecurityVerifier verifier = buildVerifier();

        verify( session ).addMessage( eq( SecurityVerifier.INFO_JAAS ),
                contains( AuthenticationManager.PROP_LOGIN_MODULE ) );
        verify( session ).addMessage( eq( SecurityVerifier.INFO_JAAS ),
                contains( "LoginModule implementation" ) );
        verify( session, never() ).addMessage( eq( SecurityVerifier.ERROR_JAAS ), anyString() );
    }

    @Test
    void testVerifyJaas_nullLoginModule_addsErrorMessage() {
        properties.remove( AuthenticationManager.PROP_LOGIN_MODULE );

        buildVerifier();

        verify( session ).addMessage( eq( SecurityVerifier.ERROR_JAAS ),
                contains( "null or blank" ) );
    }

    @Test
    void testVerifyJaas_emptyLoginModule_addsErrorMessage() {
        properties.setProperty( AuthenticationManager.PROP_LOGIN_MODULE, "" );

        buildVerifier();

        verify( session ).addMessage( eq( SecurityVerifier.ERROR_JAAS ),
                contains( "null or blank" ) );
    }

    @Test
    void testVerifyJaas_nonExistentClass_addsErrorMessage() {
        properties.setProperty( AuthenticationManager.PROP_LOGIN_MODULE,
                "com.nonexistent.FakeLoginModule" );

        // Note: the existing SecurityVerifier code has a latent NPE bug where
        // 'c' remains null after ClassNotFoundException and falls through to
        // LoginModule.class.isAssignableFrom(c). This test documents that behavior.
        assertThrows( NullPointerException.class, this::buildVerifier );

        // The error message about not finding the class was added before the NPE
        verify( session ).addMessage( eq( SecurityVerifier.ERROR_JAAS ),
                contains( "could not find" ) );
    }

    @Test
    void testVerifyJaas_classNotLoginModule_addsErrorMessage() {
        // String is not a LoginModule
        properties.setProperty( AuthenticationManager.PROP_LOGIN_MODULE,
                "java.lang.String" );

        buildVerifier();

        verify( session ).addMessage( eq( SecurityVerifier.ERROR_JAAS ),
                contains( "does not seem to be LoginModule" ) );
    }

    // ---- Policy verification tests ----

    @Test
    void testVerifyPolicy_validPolicy_setsConfiguredTrue() {
        final SecurityVerifier verifier = buildVerifier();

        assertTrue( verifier.isSecurityPolicyConfigured(),
                "Policy should be marked as configured when the policy file is valid" );
    }

    @Test
    void testVerifyPolicy_validPolicy_addsPolicyInfoMessages() {
        buildVerifier();

        verify( session ).addMessage( eq( SecurityVerifier.INFO_POLICY ),
                contains( "exists" ) );
    }

    @Test
    void testVerifyPolicy_extractsPrincipalsFromPolicyFile() {
        final SecurityVerifier verifier = buildVerifier();

        final Principal[] principals = verifier.policyPrincipals();
        assertNotNull( principals );
        // The test policy file defines grants for Role "All", "Anonymous", "Asserted", "Authenticated",
        // plus GroupPrincipal "Admin" and Role "Admin". The verifier also adds the four built-in roles.
        assertTrue( principals.length >= 4,
                "Should have at least the 4 built-in roles, got " + principals.length );

        // Check that built-in roles are present
        boolean hasAll = false;
        boolean hasAnonymous = false;
        boolean hasAsserted = false;
        boolean hasAuthenticated = false;
        for ( final Principal p : principals ) {
            if ( Role.ALL.equals( p ) ) hasAll = true;
            if ( Role.ANONYMOUS.equals( p ) ) hasAnonymous = true;
            if ( Role.ASSERTED.equals( p ) ) hasAsserted = true;
            if ( Role.AUTHENTICATED.equals( p ) ) hasAuthenticated = true;
        }
        assertTrue( hasAll, "Should contain Role.ALL" );
        assertTrue( hasAnonymous, "Should contain Role.ANONYMOUS" );
        assertTrue( hasAsserted, "Should contain Role.ASSERTED" );
        assertTrue( hasAuthenticated, "Should contain Role.AUTHENTICATED" );
    }

    // ---- Policy and container roles verification tests ----

    @Test
    void testVerifyPolicyAndContainerRoles_nonStandardRoleMissing_addsErrorMessage() {
        // The test wikantik.policy file contains Role "Admin" which is non-standard.
        // Since the mock authorizer returns no container roles, "Admin" will be flagged as missing.
        buildVerifier();

        verify( session ).addMessage( eq( SecurityVerifier.ERROR_ROLES ),
                contains( "is defined in security policy but not in web.xml" ) );
    }

    @Test
    void testVerifyPolicyAndContainerRoles_allRolesPresent_addsInfoMessage() throws Exception {
        // Set up authorizer to have the Admin role, matching what the policy file defines
        when( authorizer.getRoles() ).thenReturn( new Principal[]{ new Role( "Admin" ) } );

        buildVerifier();

        verify( session, atLeastOnce() ).addMessage( eq( SecurityVerifier.INFO_ROLES ),
                contains( "Every non-standard role" ) );
    }

    @Test
    void testVerifyPolicyAndContainerRoles_exceptionFromAuthorizer_addsErrorMessage() throws Exception {
        when( authorizationManager.getAuthorizer() )
                .thenThrow( new WikiSecurityException( "Authorizer broken" ) );

        buildVerifier();

        verify( session ).addMessage( eq( SecurityVerifier.ERROR_ROLES ),
                contains( "Authorizer broken" ) );
    }

    // ---- User database verification tests ----

    @Test
    void testVerifyUserDatabase_nullDatabase_addsErrorMessage() {
        when( userManager.getUserDatabase() ).thenReturn( null );

        buildVerifier();

        verify( session ).addMessage( eq( SecurityVerifier.ERROR_DB ),
                contains( "UserDatabase is null" ) );
    }

    @Test
    void testVerifyUserDatabase_dummyDatabase_addsErrorMessage() {
        final DummyUserDatabase dummyDb = new DummyUserDatabase();
        when( userManager.getUserDatabase() ).thenReturn( dummyDb );

        buildVerifier();

        verify( session ).addMessage( eq( SecurityVerifier.ERROR_DB ),
                contains( "DummyUserDatabase" ) );
    }

    @Test
    void testVerifyUserDatabase_normalDatabase_reportsType() throws Exception {
        final UserProfile profile = mock( UserProfile.class );
        when( userDatabase.newProfile() ).thenReturn( profile );
        // After save, return 1 more user, then after delete, back to 0
        when( userDatabase.getWikiNames() )
                .thenReturn( new Principal[0] )       // initial count
                .thenReturn( new Principal[1] )       // after save
                .thenReturn( new Principal[0] );      // after delete

        buildVerifier();

        verify( session ).addMessage( eq( SecurityVerifier.INFO_DB ),
                contains( "appears to be initialized properly" ) );
    }

    @Test
    void testVerifyUserDatabase_successfulAddAndDelete_addsInfoMessages() throws Exception {
        final UserProfile profile = mock( UserProfile.class );
        when( userDatabase.newProfile() ).thenReturn( profile );
        when( userDatabase.getWikiNames() )
                .thenReturn( new Principal[0] )
                .thenReturn( new Principal[1] )
                .thenReturn( new Principal[0] );

        buildVerifier();

        verify( session ).addMessage( eq( SecurityVerifier.INFO_DB ),
                contains( "allows new users to be created" ) );
        verify( session ).addMessage( eq( SecurityVerifier.INFO_DB ),
                contains( "allows users to be deleted" ) );
        verify( session ).addMessage( eq( SecurityVerifier.INFO_DB ),
                contains( "configuration looks fine" ) );
    }

    @Test
    void testVerifyUserDatabase_getWikiNamesThrows_addsErrorMessage() throws Exception {
        when( userDatabase.getWikiNames() )
                .thenThrow( new WikiSecurityException( "DB error" ) );

        buildVerifier();

        verify( session ).addMessage( eq( SecurityVerifier.ERROR_DB ),
                contains( "Could not obtain a list of current users" ) );
    }

    @Test
    void testVerifyUserDatabase_saveThrows_addsErrorMessage() throws Exception {
        final UserProfile profile = mock( UserProfile.class );
        when( userDatabase.newProfile() ).thenReturn( profile );
        when( userDatabase.getWikiNames() ).thenReturn( new Principal[0] );
        doThrow( new WikiSecurityException( "save failed" ) ).when( userDatabase ).save( any( UserProfile.class ) );

        buildVerifier();

        verify( session ).addMessage( eq( SecurityVerifier.ERROR_DB ),
                contains( "Could not add a test user" ) );
    }

    @Test
    void testVerifyUserDatabase_addDidNotIncreaseCount_addsErrorMessage() throws Exception {
        final UserProfile profile = mock( UserProfile.class );
        when( userDatabase.newProfile() ).thenReturn( profile );
        // Count stays at 0 after save -- add failed silently
        when( userDatabase.getWikiNames() )
                .thenReturn( new Principal[0] )
                .thenReturn( new Principal[0] );

        buildVerifier();

        verify( session ).addMessage( eq( SecurityVerifier.ERROR_DB ),
                contains( "Could not add a test user" ) );
    }

    @Test
    void testVerifyUserDatabase_deleteDidNotDecreaseCount_addsErrorMessage() throws Exception {
        final UserProfile profile = mock( UserProfile.class );
        when( userDatabase.newProfile() ).thenReturn( profile );
        // After save, count goes up; after delete, stays up (delete failed silently)
        when( userDatabase.getWikiNames() )
                .thenReturn( new Principal[0] )
                .thenReturn( new Principal[1] )
                .thenReturn( new Principal[1] );

        buildVerifier();

        verify( session ).addMessage( eq( SecurityVerifier.ERROR_DB ),
                contains( "Could not delete a test user" ) );
    }

    @Test
    void testVerifyUserDatabase_deleteThrows_addsErrorMessage() throws Exception {
        final UserProfile profile = mock( UserProfile.class );
        when( userDatabase.newProfile() ).thenReturn( profile );
        when( userDatabase.getWikiNames() )
                .thenReturn( new Principal[0] )
                .thenReturn( new Principal[1] );
        doThrow( new WikiSecurityException( "delete failed" ) )
                .when( userDatabase ).deleteByLoginName( anyString() );

        buildVerifier();

        verify( session ).addMessage( eq( SecurityVerifier.ERROR_DB ),
                contains( "Could not delete a test user" ) );
    }

    // ---- Group database verification tests ----

    @Test
    void testVerifyGroupDatabase_getGroupDatabaseThrows_addsErrorMessage() throws Exception {
        when( groupManager.getGroupDatabase() )
                .thenThrow( new WikiSecurityException( "no group db" ) );

        buildVerifier();

        verify( session ).addMessage( eq( SecurityVerifier.ERROR_GROUPS ),
                contains( "Could not retrieve GroupManager" ) );
        // Also should report the DB is null
        verify( session ).addMessage( eq( SecurityVerifier.ERROR_GROUPS ),
                contains( "GroupDatabase is null" ) );
    }

    @Test
    void testVerifyGroupDatabase_normalDatabase_reportsType() throws Exception {
        when( groupDatabase.groups() ).thenReturn( new Group[0] );
        final Group testGroup = mock( Group.class );
        when( groupManager.parseGroup( anyString(), eq( "" ), eq( true ) ) ).thenReturn( testGroup );
        // After save: 1 group; after delete: back to 0
        when( groupDatabase.groups() )
                .thenReturn( new Group[0] )           // initial count
                .thenReturn( new Group[]{ testGroup } ) // after save
                .thenReturn( new Group[0] );          // after delete

        buildVerifier();

        verify( session ).addMessage( eq( SecurityVerifier.INFO_GROUPS ),
                contains( "appears to be initialized properly" ) );
    }

    @Test
    void testVerifyGroupDatabase_successfulAddAndDelete_addsInfoMessages() throws Exception {
        final Group testGroup = mock( Group.class );
        when( groupManager.parseGroup( anyString(), eq( "" ), eq( true ) ) ).thenReturn( testGroup );
        when( groupDatabase.groups() )
                .thenReturn( new Group[0] )
                .thenReturn( new Group[]{ testGroup } )
                .thenReturn( new Group[0] );

        buildVerifier();

        verify( session ).addMessage( eq( SecurityVerifier.INFO_GROUPS ),
                contains( "allows new groups to be created" ) );
        verify( session ).addMessage( eq( SecurityVerifier.INFO_GROUPS ),
                contains( "allows groups to be deleted" ) );
        verify( session ).addMessage( eq( SecurityVerifier.INFO_GROUPS ),
                contains( "configuration looks fine" ) );
    }

    @Test
    void testVerifyGroupDatabase_groupsThrows_addsErrorMessage() throws Exception {
        when( groupDatabase.groups() )
                .thenThrow( new WikiSecurityException( "groups error" ) );

        buildVerifier();

        verify( session ).addMessage( eq( SecurityVerifier.ERROR_GROUPS ),
                contains( "Could not obtain a list of current groups" ) );
    }

    @Test
    void testVerifyGroupDatabase_saveThrows_addsErrorMessage() throws Exception {
        when( groupDatabase.groups() ).thenReturn( new Group[0] );
        when( groupManager.parseGroup( anyString(), eq( "" ), eq( true ) ) )
                .thenThrow( new WikiSecurityException( "parse failed" ) );

        buildVerifier();

        verify( session ).addMessage( eq( SecurityVerifier.ERROR_GROUPS ),
                contains( "Could not add a group" ) );
    }

    @Test
    void testVerifyGroupDatabase_addDidNotIncreaseCount_addsErrorMessage() throws Exception {
        final Group testGroup = mock( Group.class );
        when( groupManager.parseGroup( anyString(), eq( "" ), eq( true ) ) ).thenReturn( testGroup );
        // Count stays at 0 after save
        when( groupDatabase.groups() )
                .thenReturn( new Group[0] )
                .thenReturn( new Group[0] );

        buildVerifier();

        verify( session ).addMessage( eq( SecurityVerifier.ERROR_GROUPS ),
                contains( "Could not add a test group" ) );
    }

    @Test
    void testVerifyGroupDatabase_deleteDidNotDecreaseCount_addsErrorMessage() throws Exception {
        final Group testGroup = mock( Group.class );
        when( groupManager.parseGroup( anyString(), eq( "" ), eq( true ) ) ).thenReturn( testGroup );
        // After save: 1 group; after delete: still 1
        when( groupDatabase.groups() )
                .thenReturn( new Group[0] )
                .thenReturn( new Group[]{ testGroup } )
                .thenReturn( new Group[]{ testGroup } );

        buildVerifier();

        verify( session ).addMessage( eq( SecurityVerifier.ERROR_GROUPS ),
                contains( "Could not delete a test group" ) );
    }

    @Test
    void testVerifyGroupDatabase_deleteThrows_addsErrorMessage() throws Exception {
        final Group testGroup = mock( Group.class );
        when( groupManager.parseGroup( anyString(), eq( "" ), eq( true ) ) ).thenReturn( testGroup );
        when( groupDatabase.groups() )
                .thenReturn( new Group[0] )
                .thenReturn( new Group[]{ testGroup } );
        doThrow( new WikiSecurityException( "delete failed" ) )
                .when( groupDatabase ).delete( any( Group.class ) );

        buildVerifier();

        verify( session ).addMessage( eq( SecurityVerifier.ERROR_GROUPS ),
                contains( "Could not delete a test group" ) );
    }

    // ---- verifyStaticPermission tests ----

    @Test
    void testVerifyStaticPermission_delegatesToAuthorizationManager() {
        when( authorizationManager.allowedByLocalPolicy( any(), any() ) ).thenReturn( true );

        final SecurityVerifier verifier = buildVerifier();
        final boolean result = verifier.verifyStaticPermission( Role.AUTHENTICATED,
                new com.wikantik.auth.permissions.WikiPermission( "test", "login" ) );

        assertTrue( result );
        verify( authorizationManager ).allowedByLocalPolicy( any(), any() );
    }

    @Test
    void testVerifyStaticPermission_returnsFalseWhenDenied() {
        when( authorizationManager.allowedByLocalPolicy( any(), any() ) ).thenReturn( false );

        final SecurityVerifier verifier = buildVerifier();
        final boolean result = verifier.verifyStaticPermission( Role.ANONYMOUS,
                new com.wikantik.auth.permissions.AllPermission( "test" ) );

        assertFalse( result );
    }

    // ---- webContainerRoles tests ----

    @Test
    void testWebContainerRoles_nonWebContainerAuthorizer_returnsEmptyArray() throws Exception {
        // authorizer is a plain mock, not a WebContainerAuthorizer
        final SecurityVerifier verifier = buildVerifier();

        final Principal[] roles = verifier.webContainerRoles();
        assertNotNull( roles );
        assertEquals( 0, roles.length );
    }

    // ---- isSecurityPolicyConfigured tests ----

    @Test
    void testIsSecurityPolicyConfigured_trueWhenPolicyIsValid() {
        final SecurityVerifier verifier = buildVerifier();

        assertTrue( verifier.isSecurityPolicyConfigured() );
    }

    @Test
    void testIsSecurityPolicyConfigured_falseWhenPolicyFileNotFound() throws Exception {
        // Point to a non-existent policy file. PolicyReader constructor throws
        // IllegalArgumentException for missing files, which is not caught by verifyPolicy(),
        // so the constructor will throw.
        final URL badUrl = new URL( "file:///nonexistent/path/wikantik.policy" );
        when( engine.findConfigFile( AuthorizationManager.DEFAULT_POLICY ) ).thenReturn( badUrl );

        assertThrows( IllegalArgumentException.class, this::buildVerifier );
    }

    // ---- Constructor clears session messages ----

    @Test
    void testConstructor_clearsSessionMessages() {
        buildVerifier();

        verify( session ).clearMessages();
    }

    // ---- policyRoleTable produces HTML ----

    @Test
    void testPolicyRoleTable_returnsHtmlTable() {
        final SecurityVerifier verifier = buildVerifier();

        final String html = verifier.policyRoleTable();
        assertNotNull( html );
        assertTrue( html.startsWith( "<table" ), "Should start with <table" );
        assertTrue( html.endsWith( "</table>" ), "Should end with </table>" );
    }

    @Test
    void testPolicyRoleTable_containsPermissionEntries() {
        final SecurityVerifier verifier = buildVerifier();

        final String html = verifier.policyRoleTable();
        assertTrue( html.contains( "PagePermission" ), "Should contain PagePermission entries" );
        assertTrue( html.contains( "WikiPermission" ), "Should contain WikiPermission entries" );
        assertTrue( html.contains( "AllPermission" ), "Should contain AllPermission entry" );
    }
}
