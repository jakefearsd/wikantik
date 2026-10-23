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

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Session;
import com.wikantik.WikiSession;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.auth.authorize.Group;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.comments.PageOwnerService;
import com.wikantik.pagegraph.spine.TrustedAuthorsDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

import java.io.File;
import java.nio.file.Files;
import java.security.Principal;
import java.util.UUID;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Every cleanup step a hard-delete owes, in one place — see {@link UserDeletionService}. */
class UserDeletionServiceTest {

    private static final String LOGIN = "alice";

    private Engine engine;
    private Session session;
    private UserDatabase users;
    private GroupManager groups;
    private ApiKeyService apiKeys;
    private PageOwnerService pageOwners;
    private TrustedAuthorsDao trusted;
    private Group adminGroup;
    private Group otherGroup;

    @BeforeEach
    void setUp() throws Exception {
        engine = mock( Engine.class );
        session = mock( Session.class );
        users = mock( UserDatabase.class );
        groups = mock( GroupManager.class );
        apiKeys = mock( ApiKeyService.class );
        pageOwners = mock( PageOwnerService.class );
        trusted = mock( TrustedAuthorsDao.class );

        adminGroup = mock( Group.class );
        otherGroup = mock( Group.class );
        when( groups.getRoles() ).thenReturn( new Principal[]{ new GroupPrincipal( "Admin" ), new GroupPrincipal( "Editors" ) } );
        when( groups.getGroup( "Admin" ) ).thenReturn( adminGroup );
        when( groups.getGroup( "Editors" ) ).thenReturn( otherGroup );
        when( adminGroup.isMember( any() ) ).thenReturn( true );
        when( otherGroup.isMember( any() ) ).thenReturn( false );
    }

    private UserDeletionService service() {
        return new UserDeletionService( engine, users, groups, apiKeys, pageOwners, trusted );
    }

    @Test
    void deleteRevokesKeysRemovesGroupMembershipsTrustedAuthorOrphansPagesThenDeletesUser() throws Exception {
        service().delete( session, LOGIN );

        verify( apiKeys ).revokeAllForPrincipalOrThrow( LOGIN );
        verify( adminGroup ).remove( any( Principal.class ) );
        verify( groups ).setGroup( session, adminGroup );
        verify( otherGroup, never() ).remove( any( Principal.class ) );
        verify( trusted ).remove( LOGIN );
        verify( pageOwners ).orphanByOwner( LOGIN, "system:user-deleted:" + LOGIN );

        // The profile row goes last, so a failed cleanup leaves the account in place to retry.
        final InOrder order = inOrder( apiKeys, groups, trusted, users );
        order.verify( apiKeys ).revokeAllForPrincipalOrThrow( LOGIN );
        order.verify( groups ).setGroup( session, adminGroup );
        order.verify( trusted ).remove( LOGIN );
        order.verify( users ).deleteByLoginName( LOGIN );
    }

    @Test
    void failedKeyRevocationAbortsDeleteSoNoLiveKeyOutlivesItsUser() throws Exception {
        doThrow( new SQLException( "db down" ) ).when( apiKeys ).revokeAllForPrincipalOrThrow( LOGIN );

        assertThrows( WikiSecurityException.class, () -> service().delete( session, LOGIN ) );

        verify( users, never() ).deleteByLoginName( anyString() );
    }

    @Test
    void failedGroupRemovalAbortsDeleteButStillAttemptsTheOtherGroups() throws Exception {
        when( otherGroup.isMember( any() ) ).thenReturn( true );
        doThrow( new WikiSecurityException( "group db down" ) ).when( groups ).setGroup( session, adminGroup );

        assertThrows( WikiSecurityException.class, () -> service().delete( session, LOGIN ) );

        verify( otherGroup ).remove( any( Principal.class ) );
        verify( users, never() ).deleteByLoginName( anyString() );
    }

    @Test
    void failedTrustedAuthorRemovalAbortsDelete() throws Exception {
        doThrow( new RuntimeException( "trusted_authors remove failed" ) ).when( trusted ).remove( LOGIN );

        assertThrows( WikiSecurityException.class, () -> service().delete( session, LOGIN ) );

        verify( users, never() ).deleteByLoginName( anyString() );
    }

    @Test
    void failedOrphaningIsBestEffortAndDoesNotAbortDelete() throws Exception {
        doThrow( new RuntimeException( "db connection down" ) ).when( pageOwners ).orphanByOwner( anyString(), anyString() );

        service().delete( session, LOGIN );

        verify( users ).deleteByLoginName( LOGIN );
    }

    @Test
    void unconfiguredOptionalCollaboratorsAreSkipped() throws Exception {
        new UserDeletionService( engine, users, groups, null, null, null ).delete( session, LOGIN );

        verify( users ).deleteByLoginName( LOGIN );
    }

    @Test
    void unknownUserPropagatesNoSuchPrincipal() throws Exception {
        doThrow( new NoSuchPrincipalException( "nope" ) ).when( users ).deleteByLoginName( LOGIN );

        assertThrows( NoSuchPrincipalException.class, () -> service().delete( session, LOGIN ) );
    }

    @Test
    void anUnknownLoginIsRejectedBeforeAnythingIsTouched() throws Exception {
        when( users.findByLoginName( LOGIN ) ).thenThrow( new NoSuchPrincipalException( "nope" ) );

        assertThrows( NoSuchPrincipalException.class, () -> service().delete( session, LOGIN ) );

        verify( apiKeys, never() ).revokeAllForPrincipalOrThrow( anyString() );
        verify( groups, never() ).setGroup( any(), any() );
        verify( trusted, never() ).remove( anyString() );
        verify( pageOwners, never() ).orphanByOwner( anyString(), anyString() );
        verify( users, never() ).deleteByLoginName( anyString() );
    }

    @Test
    void anAbortedDeleteLeavesTheStillExistingAccountsPagesOwned() throws Exception {
        doThrow( new RuntimeException( "trusted_authors remove failed" ) ).when( trusted ).remove( LOGIN );

        assertThrows( WikiSecurityException.class, () -> service().delete( session, LOGIN ) );

        verify( pageOwners, never() ).orphanByOwner( anyString(), anyString() );
    }

    @Test
    void deleteRemovesTheUsersRememberMeCookieFilesOnly( @TempDir final File workDir ) throws Exception {
        when( engine.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );
        final File cookieDir = new File( workDir, "logincookies" );
        assertTrue( cookieDir.mkdirs() );
        final File mine = new File( cookieDir, UUID.randomUUID().toString() );
        final File theirs = new File( cookieDir, UUID.randomUUID().toString() );
        Files.writeString( mine.toPath(), LOGIN );
        Files.writeString( theirs.toPath(), "bob" );

        service().delete( session, LOGIN );

        assertFalse( mine.exists(), "a deleted user's remember-me cookie must stop authenticating" );
        assertTrue( theirs.exists(), "another user's cookie is untouched" );
    }

    @Test
    void deleteDemotesTheUsersLiveSessionsToGuest() throws Exception {
        final WikiSession live = mock( WikiSession.class );
        when( live.isAuthenticated() ).thenReturn( true );
        when( live.getLoginPrincipal() ).thenReturn( new WikiPrincipal( LOGIN, WikiPrincipal.LOGIN_NAME ) );
        final jakarta.servlet.http.HttpSession http = mock( jakarta.servlet.http.HttpSession.class );
        when( http.getId() ).thenReturn( "deleted-user-live-session" );
        SessionMonitor.getInstance( engine ).register( http, live );
        try {
            service().delete( session, LOGIN );

            verify( live ).invalidate();
        } finally {
            SessionMonitor.getInstance( engine ).remove( "deleted-user-live-session" );
        }
    }
}
