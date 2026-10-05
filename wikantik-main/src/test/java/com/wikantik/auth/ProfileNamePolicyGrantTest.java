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
import com.wikantik.WikiSessionTest;
import com.wikantik.api.core.Session;
import com.wikantik.auth.permissions.AllPermission;
import com.wikantik.auth.permissions.WikiPermission;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Database-backed policy grants are keyed by role or group name. A user's display names (full
 * name, wiki name) are profile data the user controls, so they must never satisfy a role or group
 * grant. These tests drive a real login through the engine with the database policy in force.
 */
@RequiresPostgres
class ProfileNamePolicyGrantTest {

    private static final String PASSWORD = "correct-horse-battery-staple-1";

    private TestEngine engine;
    private DefaultAuthorizationManager authz;

    @BeforeEach
    void setUp() throws Exception {
        final DataSource ds = PostgresTestDb.createDataSource();
        try ( Connection c = ds.getConnection(); Statement st = c.createStatement() ) {
            st.executeUpdate( "DELETE FROM policy_grants" );
            st.executeUpdate( "INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions) "
                    + "VALUES ('role', 'Admin', 'all', '*', '*')" );
            st.executeUpdate( "INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions) "
                    + "VALUES ('role', 'Authenticated', 'wiki', '*', 'editProfile,createPages')" );
            st.executeUpdate( "INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions) "
                    + "VALUES ('user', 'grantee', 'wiki', '*', 'createGroups')" );
        }
        engine = new TestEngine( TestEngine.getTestProperties() );
        authz = ( DefaultAuthorizationManager ) engine.getManager( AuthorizationManager.class );
        final Field f = DefaultAuthorizationManager.class.getDeclaredField( "databasePolicy" );
        f.setAccessible( true );
        f.set( authz, new DatabasePolicy( ds, "policy_grants" ) );
    }

    @AfterEach
    void tearDown() {
        if ( engine != null ) {
            engine.stop();
        }
    }

    private Session loginWithNames( final String login, final String fullName, final String wikiName ) throws Exception {
        final UserManager um = engine.getManager( UserManager.class );
        final UserProfile profile = um.getUserDatabase().newProfile();
        profile.setLoginName( login );
        profile.setFullname( fullName );
        profile.setWikiName( wikiName );
        profile.setEmail( login + "@example.test" );
        profile.setPassword( PASSWORD );
        um.getUserDatabase().save( profile );
        return WikiSessionTest.authenticatedSession( engine, login, PASSWORD );
    }

    private boolean isAdmin( final Session s ) {
        return authz.checkPermission( s, new AllPermission( engine.getApplicationName() ) );
    }

    @Test
    void fullNameMatchingAdminRoleDoesNotConferAdmin() throws Exception {
        final Session s = loginWithNames( "pnfull", "Admin", "PnFull" );
        assertTrue( s.isAuthenticated() );
        assertFalse( isAdmin( s ), "a full name equal to a role name must not confer that role's grants" );
    }

    @Test
    void wikiNameMatchingAdminRoleDoesNotConferAdmin() throws Exception {
        final Session s = loginWithNames( "pnwiki", "Pn Wiki", "Admin" );
        assertTrue( s.isAuthenticated() );
        assertFalse( isAdmin( s ), "a wiki name equal to a role name must not confer that role's grants" );
    }

    @Test
    void roleGrantsStillApplyToOrdinaryUser() throws Exception {
        final Session s = loginWithNames( "pnplain", "Pn Plain", "PnPlain" );
        assertTrue( authz.checkPermission( s, WikiPermission.CREATE_PAGES ) );
        assertFalse( isAdmin( s ) );
    }

    @Test
    void userGrantMatchesLoginNameOnly() throws Exception {
        final Session byLogin = loginWithNames( "grantee", "Grantee Person", "GranteePerson" );
        assertTrue( authz.checkPermission( byLogin, WikiPermission.CREATE_GROUPS ),
                "a user-targeted grant applies to the login principal it names" );

        final Session byFullName = loginWithNames( "pnimpostor", "grantee", "PnImpostor" );
        assertFalse( authz.checkPermission( byFullName, WikiPermission.CREATE_GROUPS ),
                "a user-targeted grant must not match another user's display name" );
    }

    @Test
    void roleGrantNameFromPolicyTableIsReservedForProfiles() throws Exception {
        try ( Connection c = PostgresTestDb.createDataSource().getConnection(); Statement st = c.createStatement() ) {
            st.executeUpdate( "INSERT INTO policy_grants (principal_type, principal_name, permission_type, target, actions) "
                    + "VALUES ('role', 'Curators', 'page', '*', 'edit')" );
        }
        authz.getDatabasePolicy().refresh();
        assertTrue( ReservedProfileNames.isReserved( engine, "Curators" ) );
        assertTrue( ReservedProfileNames.isReserved( engine, "curators" ) );
        assertFalse( ReservedProfileNames.isReserved( engine, "grantee" ),
                "a user-grant login name is not a role or group name" );
    }
}
