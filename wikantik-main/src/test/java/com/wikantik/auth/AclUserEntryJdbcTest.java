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
import com.wikantik.TestJNDIContext;
import com.wikantik.WikiSession;
import com.wikantik.WikiSessionTest;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.managers.PageManager;
import com.wikantik.auth.acl.UnresolvedPrincipal;
import com.wikantik.auth.authorize.Group;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.auth.permissions.PermissionFactory;
import com.wikantik.auth.user.JDBCUserDatabase;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NameAlreadyBoundException;
import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.security.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An ACL user entry is a login name and matches only that account's login principal. Full and wiki
 * names are editable profile data and name nobody; login, group and role entries keep working.
 */
class AclUserEntryJdbcTest extends AbstractJdbcUsersEngineTest {

    // ---- I-1: an ACL user entry resolves to one account and matches its login --------------------

    @Test
    void fullNameAclGrantsNobodyNotEvenItsHolder() throws Exception {
        saveUser( PREFIX + "olive", "Olive Owner" );
        saveUser( PREFIX + "mallory", "Mallory Mal" );
        engine.saveText( "SdnOlivePage", "[{ALLOW edit Olive Owner}]\nsecret" );

        assertFalse( canEdit( login( PREFIX + "olive" ), "SdnOlivePage" ),
                "an ACL user entry is a login name; a full name names nobody" );
        renameFullName( PREFIX + "mallory", "Olive Owner" );
        assertFalse( canEdit( login( PREFIX + "mallory" ), "SdnOlivePage" ) );
    }

    @Test
    void wikiNameAclGrantsNobody() throws Exception {
        saveUser( PREFIX + "olive", "Olive Owner" );
        engine.saveText( "SdnOliveWikiPage", "[{ALLOW edit OliveOwner}]\nsecret" );
        assertFalse( canEdit( login( PREFIX + "olive" ), "SdnOliveWikiPage" ) );
    }

    @Test
    void anUnclaimedNameCannotBeClaimedThroughAFullNameAfterReparse() throws Exception {
        // R27: an ACL names a person who has no account yet; someone then takes that name as their
        // full name. A fresh parse of the ACL (cache expiry, restart) must not hand them the entry.
        engine.saveText( "SdnClaimPage", "[{ALLOW edit Carol Claimed}]\nsecret" );
        saveUser( PREFIX + "mallory", "Carol Claimed" );
        assertTrue( authz.resolvePrincipal( "Carol Claimed" ) instanceof UnresolvedPrincipal );
        engine.saveText( "SdnClaimPage2", "[{ALLOW edit Carol Claimed}]\nsecret" );
        assertFalse( canEdit( login( PREFIX + "mallory" ), "SdnClaimPage" ) );
        assertFalse( canEdit( login( PREFIX + "mallory" ), "SdnClaimPage2" ) );
    }

    @Test
    void userEntryResolvesByLoginNameOnly() throws Exception {
        saveUser( PREFIX + "olive", "Olive Owner" );

        assertEquals( new WikiPrincipal( PREFIX + "olive", WikiPrincipal.LOGIN_NAME ), authz.resolvePrincipal( PREFIX + "olive" ) );
        assertTrue( authz.resolvePrincipal( "Olive Owner" ) instanceof UnresolvedPrincipal, "full name" );
        assertTrue( authz.resolvePrincipal( "OliveOwner" ) instanceof UnresolvedPrincipal, "wiki name" );
        assertTrue( authz.resolvePrincipal( "Nobody Here" ) instanceof UnresolvedPrincipal, "unknown" );
    }

    @Test
    void unknownNameAclGrantsNobodyEvenToASessionCarryingThatName() throws Exception {
        saveUser( PREFIX + "ghost", "Ghost Writer" );
        engine.saveText( "SdnGhostPage", "[{ALLOW edit Casper Ghost}]\nsecret" );
        final Session ghost = login( PREFIX + "ghost" );
        assertFalse( canEdit( ghost, "SdnGhostPage" ) );

        // An unresolved entry no longer falls back to a plain-name match against session principals.
        final WikiSession session = ( WikiSession ) ghost;
        session.getSubject().getPrincipals().add( new WikiPrincipal( "Casper Ghost", WikiPrincipal.FULL_NAME ) );
        assertFalse( authz.hasRoleOrPrincipal( ghost, new UnresolvedPrincipal( "Casper Ghost" ) ),
                "an unresolved user name must match no session principal" );
        assertFalse( canEdit( ghost, "SdnGhostPage" ) );
    }

    @Test
    void loginGroupAndRoleAclsStillWork() throws Exception {
        saveUser( PREFIX + "lena", "Lena Login" );
        saveUser( PREFIX + "other", "Other Person" );
        final GroupManager groups = engine.getManager( GroupManager.class );
        final Group g = groups.parseGroup( "SdnEditors", PREFIX + "lena", true );
        groups.setGroup( login( PREFIX + "lena" ), g );
        try {
            engine.saveText( "SdnLoginPage", "[{ALLOW edit " + PREFIX + "lena}]\nsecret" );
            engine.saveText( "SdnGroupPage", "[{ALLOW edit SdnEditors}]\nsecret" );
            engine.saveText( "SdnAuthPage", "[{ALLOW edit Authenticated}]\nsecret" );
            engine.saveText( "SdnAllPage", "[{ALLOW edit All}]\nsecret" );

            // Test sessions share one mock HTTP session id, so log in right before each check.
            assertTrue( canEdit( login( PREFIX + "lena" ), "SdnLoginPage" ) );
            assertFalse( canEdit( login( PREFIX + "other" ), "SdnLoginPage" ) );
            assertTrue( canEdit( login( PREFIX + "lena" ), "SdnGroupPage" ) );
            assertFalse( canEdit( login( PREFIX + "other" ), "SdnGroupPage" ) );
            assertTrue( canEdit( login( PREFIX + "other" ), "SdnAuthPage" ) );
            assertTrue( canEdit( login( PREFIX + "other" ), "SdnAllPage" ) );
        } finally {
            groups.removeGroup( "SdnEditors" );
        }
    }

    // ---- R28: container-authenticated users with no profile row --------------------------------

    @Test
    void loginAclGrantsAContainerUserWhoHasNoProfile() throws Exception {
        engine.saveText( "SdnContainerPage", "[{ALLOW edit " + PREFIX + "bob}]\nsecret" );
        final Session bob = WikiSessionTest.containerAuthenticatedSession( engine, PREFIX + "bob", new Principal[ 0 ] );
        assertEquals( PREFIX + "bob", bob.getLoginPrincipal().getName() );
        assertTrue( canEdit( bob, "SdnContainerPage" ),
                "an ACL naming a login with no profile row matches that container-authenticated login" );
    }

    @Test
    void loginAclForAProfilelessLoginIsNotSatisfiedByAFullName() throws Exception {
        engine.saveText( "SdnContainerPage2", "[{ALLOW edit " + PREFIX + "bob}]\nsecret" );
        saveUser( PREFIX + "robert", PREFIX + "bob" );   // full name equals the ACL name; login differs
        assertFalse( canEdit( login( PREFIX + "robert" ), "SdnContainerPage2" ),
                "an unresolved ACL name matches only a session login, never a full or wiki name" );
    }

    @Test
    void unresolvedNameMatchesOnlyTheSessionLoginPrincipal() throws Exception {
        final Session bob = WikiSessionTest.containerAuthenticatedSession( engine, PREFIX + "bob", new Principal[ 0 ] );
        assertTrue( authz.hasRoleOrPrincipal( bob, new UnresolvedPrincipal( PREFIX + "bob" ) ) );
        final WikiSession session = ( WikiSession ) bob;
        session.getSubject().getPrincipals().add( new WikiPrincipal( "Bob Fullname", WikiPrincipal.FULL_NAME ) );
        session.getSubject().getPrincipals().add( new WikiPrincipal( "BobWiki", WikiPrincipal.WIKI_NAME ) );
        assertFalse( authz.hasRoleOrPrincipal( bob, new UnresolvedPrincipal( "Bob Fullname" ) ) );
        assertFalse( authz.hasRoleOrPrincipal( bob, new UnresolvedPrincipal( "BobWiki" ) ) );
    }
}
