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
import com.wikantik.WikiEngine;
import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.auth.subsystem.AuthSubsystem;
import com.wikantik.collab.CollabConfig;
import com.wikantik.collab.CollabPermissions;
import com.wikantik.collab.CollabSession;
import com.wikantik.collab.CollabSessionRegistry;
import com.wikantik.collab.CollabSessionRegistry.Joined;
import com.wikantik.collab.adapter.CollabEngineAccess;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.PushResponse;
import com.wikantik.collab.model.Update;
import com.wikantik.collab.testing.InMemoryCollabStore;
import com.wikantik.collab.testing.MutableClock;
import com.wikantik.collab.testing.ScriptedPageWriter;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.comments.PageOwnerService;
import com.wikantik.pagegraph.spine.TrustedAuthorsDao;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

import java.io.File;
import java.nio.file.Files;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.sql.SQLException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void deleteRecordsTheLoginTombstoneBeforeRemovingTheProfileRow() throws Exception {
        service().delete( session, LOGIN );

        final InOrder order = inOrder( users );
        order.verify( users ).recordDeletedLogin( LOGIN );
        order.verify( users ).deleteByLoginName( LOGIN );
    }

    @Test
    void failedTombstoneAbortsDeleteSoNoDeletedAccountIsLeftWithoutOne() throws Exception {
        doThrow( new WikiSecurityException( "deleted_logins write failed" ) ).when( users ).recordDeletedLogin( LOGIN );

        assertThrows( WikiSecurityException.class, () -> service().delete( session, LOGIN ) );

        verify( users, never() ).deleteByLoginName( anyString() );
    }

    // ---- collaboration (MultiEdit Phase 2 Task 6, §0 Minor: Deleted users) ------------------------------------------

    private UserDeletionService serviceWithCollab( final Function< Engine, CollabSessionRegistry > collab ) {
        return new UserDeletionService( engine, users, groups, apiKeys, pageOwners, trusted, collab );
    }

    /** Edit, create and sign-in granted to everyone: the eviction under test is by author, not by permission. */
    private static final class AllowAll implements CollabPermissions {
        @Override
        public boolean canEdit( final Object participantSession, final String pageName ) {
            return true;
        }

        @Override
        public boolean canCreate( final Object participantSession, final String pageName ) {
            return true;
        }

        @Override
        public boolean signedIn( final Object participantSession ) {
            return true;
        }
    }

    private static Joined join( final CollabSessionRegistry registry, final String page, final String author ) {
        return assertInstanceOf( Joined.class, registry.join( page,
                new JoinRequest( author, null, new Object(), author.toUpperCase(), null, null ) ) );
    }

    private static PushResponse type( final Joined j, final String author ) {
        final CollabSession.View v = j.session().view();
        return j.session().push( j.response().participantId(), author, v.head(),
                List.of( new Update( j.response().clientId(), ChangeSet.replace( v.doc().length(), 0, 0, "x" ) ) ) );
    }

    @Test
    void aDeletedUsersParticipantsAreEvictedInEverySession() throws Exception {
        final ScriptedPageWriter writer = new ScriptedPageWriter();
        writer.seed( "One", "one\n", Map.of() );
        writer.seed( "Two", "two\n", Map.of() );
        final CollabSessionRegistry registry = new CollabSessionRegistry( new InMemoryCollabStore(), writer,
                new AllowAll(), key -> null, new MutableClock( Instant.parse( "2026-01-01T00:00:00Z" ) ),
                new CollabConfig( true, 20, 65536, 25, 600, 30, 100, 500, 10, 0, 1000 ), 1 << 20 );
        final Joined aliceOne = join( registry, "One", LOGIN );
        final Joined aliceTwo = join( registry, "Two", LOGIN );
        final Joined bob = join( registry, "Two", "bob" );

        serviceWithCollab( e -> registry ).delete( session, LOGIN );

        verify( users ).deleteByLoginName( LOGIN );
        for ( final Joined gone : List.of( aliceOne, aliceTwo ) ) {
            final PushResponse refused = type( gone, LOGIN );
            assertEquals( 403, refused.status() );
            assertEquals( CollabSession.EVICTED, refused.reason() );
            assertEquals( CollabSession.ACCOUNT_DELETED, refused.detail() );
        }
        assertEquals( 200, type( bob, "bob" ).status(), "another user's participant stays" );
    }

    @Test
    void theCollaborationEvictionComesAfterTheProfileRowIsDeleted() throws Exception {
        final CollabSessionRegistry registry = mock( CollabSessionRegistry.class );

        serviceWithCollab( e -> registry ).delete( session, LOGIN );

        final InOrder order = inOrder( users, registry );
        order.verify( users ).deleteByLoginName( LOGIN );
        order.verify( registry ).evictAuthor( LOGIN );
    }

    @Test
    void anAbortedDeleteEvictsNobody() throws Exception {
        final CollabSessionRegistry registry = mock( CollabSessionRegistry.class );
        doThrow( new RuntimeException( "trusted_authors remove failed" ) ).when( trusted ).remove( LOGIN );

        assertThrows( WikiSecurityException.class, () -> serviceWithCollab( e -> registry ).delete( session, LOGIN ) );

        verify( registry, never() ).evictAuthor( anyString() );
    }

    @Test
    void aCollabFailureNeverAbortsAUserDelete() throws Exception {   // Review Focus #4
        final CollabSessionRegistry registry = mock( CollabSessionRegistry.class );
        when( registry.evictAuthor( LOGIN ) ).thenThrow( new IllegalStateException( "test: collab store down" ) );

        final List< LogEvent > warns = capturingWarnings( () -> serviceWithCollab( e -> registry ).delete( session, LOGIN ) );

        verify( users ).deleteByLoginName( LOGIN );
        assertTrue( warns.stream().anyMatch( w -> w.getMessage().getFormattedMessage().contains( LOGIN )
                && w.getMessage().getFormattedMessage().contains( "collaboration" )
                && w.getThrown() instanceof IllegalStateException ), "a WARN with the cause: " + warns );
    }

    @Test
    void aFailingRegistryLookupNeverAbortsAUserDeleteEither() throws Exception {
        final List< LogEvent > warns = capturingWarnings( () -> serviceWithCollab( e -> {
            throw new IllegalStateException( "test: engine half-built" );
        } ).delete( session, LOGIN ) );

        verify( users ).deleteByLoginName( LOGIN );
        assertTrue( warns.stream().anyMatch( w -> w.getMessage().getFormattedMessage().contains( LOGIN )
                && w.getMessage().getFormattedMessage().contains( "collaboration" )
                && w.getThrown() instanceof IllegalStateException
                && "test: engine half-built".equals( w.getThrown().getMessage() ) ), "a WARN with the cause: " + warns );
    }

    @Test
    void theDeletedProfileIsStillAnnouncedAfterACollabFailure() throws Exception {
        final WikiEngine wikiEngine = mock( WikiEngine.class );
        final PublicIdentity identity = mock( PublicIdentity.class );
        when( wikiEngine.getAuthSubsystem() ).thenReturn(
                new AuthSubsystem.Services( null, null, null, null, null, null, null, null, identity ) );
        final CollabSessionRegistry registry = mock( CollabSessionRegistry.class );
        when( registry.evictAuthor( LOGIN ) ).thenThrow( new IllegalStateException( "test: collab store down" ) );

        new UserDeletionService( wikiEngine, users, groups, apiKeys, pageOwners, trusted, e -> registry )
                .delete( session, LOGIN );

        final InOrder order = inOrder( registry, identity );
        order.verify( registry ).evictAuthor( LOGIN );
        order.verify( identity ).invalidate( LOGIN );   // ProfileChangeNotifier.profileDeleted ran after the failure
    }

    @Test
    void unwiredCollabIsANoOp() throws Exception {
        assertNull( CollabEngineAccess.registry( engine ), "a mock engine is not a WikiEngine: nothing is wired" );
        serviceWithCollab( e -> null ).delete( session, LOGIN );
        service().delete( session, LOGIN );

        verify( users, org.mockito.Mockito.times( 2 ) ).deleteByLoginName( LOGIN );
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    /** Runs {@code action} with the WARN events of {@link UserDeletionService} captured. */
    private static List< LogEvent > capturingWarnings( final Action action ) throws Exception {
        final List< LogEvent > captured = new CopyOnWriteArrayList<>();
        final LoggerContext ctx = (LoggerContext) LogManager.getContext( false );
        final Configuration config = ctx.getConfiguration();
        final String name = "CollabEvictCapture-" + Thread.currentThread().threadId();
        final AbstractAppender appender = new AbstractAppender( name, null, PatternLayout.createDefaultLayout(), true,
                null ) {
            @Override
            public void append( final LogEvent event ) {
                captured.add( event.toImmutable() );
            }
        };
        appender.start();
        config.addAppender( appender );
        final String loggerName = UserDeletionService.class.getName();
        LoggerConfig loggerConfig = config.getLoggerConfig( loggerName );
        final boolean added = !loggerConfig.getName().equals( loggerName );
        if ( added ) {
            loggerConfig = LoggerConfig.newBuilder().setAdditivity( true ).setLevel( Level.WARN )
                    .setLoggerName( loggerName ).setRefs( new AppenderRef[ 0 ] ).setConfig( config ).build();
            config.addLogger( loggerName, loggerConfig );
        }
        loggerConfig.addAppender( appender, Level.WARN, null );
        ctx.updateLoggers();
        try {
            action.run();
        } finally {
            loggerConfig.removeAppender( name );
            if ( added ) {
                config.removeLogger( loggerName );
            }
            ctx.updateLoggers();
            appender.stop();
        }
        return captured.stream().filter( e -> e.getLevel() == Level.WARN ).toList();
    }
}
