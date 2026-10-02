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
package com.wikantik.auth.authorize;

import com.wikantik.WikiEngine;
import com.wikantik.api.core.Session;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiSecurityEvent;
import com.wikantik.TestJNDIContext;
import com.wikantik.auth.AbstractJDBCDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NameAlreadyBoundException;
import javax.sql.DataSource;
import java.sql.Connection;

import java.lang.reflect.Field;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-unit tests for {@link DefaultGroupManager} branches not exercised by
 * {@code GroupManagerTest}/{@code DefaultGroupManagerCITest}: {@code getGroupDatabase()}/
 * {@code initialize()} instantiation failures (default class name fallback, reflective and
 * missing-property failures), {@code setGroupInternal()}'s rollback-on-save-failure paths
 * (with and without a previous version to roll back to), {@code removeWikiEventListener()},
 * and {@code actionPerformed()}'s failure path when the back-end GroupDatabase itself fails
 * mid profile-rename cascade. No engine/database wiring is needed for any of these —
 * collaborators are mocked and injected via reflection.
 */
class DefaultGroupManagerUnitTest {

    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger( DefaultGroupManagerUnitTest.class );

    private static String priorJndiFactory;
    private String pollutedJndiFactory;

    /**
     * {@link TestJNDIContext#initialize()} (used by the JDBC database tests) installs a JVM-wide
     * initial-context factory that is never removed, so whether {@code new InitialContext()}
     * resolves depends on which test class ran earlier in the same forked JVM. To make that
     * worst case deterministic, the class first installs that ambient JNDI state itself (with the
     * default datasource bound and valid), and every test then clears the factory property so it
     * must not depend on it. Everything is restored to the exact prior value afterwards.
     */
    @BeforeAll
    static void installAmbientJndi() throws Exception {
        priorJndiFactory = System.getProperty( Context.INITIAL_CONTEXT_FACTORY );
        TestJNDIContext.initialize();
        // initialize() is a no-op if an earlier class already ran it (and the property may since have been cleared)
        System.setProperty( Context.INITIAL_CONTEXT_FACTORY, TestJNDIContext.Factory.class.getName() );
        final Context initCtx = new InitialContext();
        try {
            initCtx.bind( "java:comp/env", new TestJNDIContext() );
        } catch( final NameAlreadyBoundException e ) {
            LOG.debug( "java:comp/env already bound by an earlier test in this JVM", e );
        }
        final DataSource ds = mock( DataSource.class );
        final Connection conn = mock( Connection.class );
        when( ds.getConnection() ).thenReturn( conn );
        when( conn.isValid( org.mockito.ArgumentMatchers.anyInt() ) ).thenReturn( true );
        ( (Context) initCtx.lookup( "java:comp/env" ) ).bind( AbstractJDBCDatabase.DEFAULT_DATASOURCE, ds );
    }

    @AfterAll
    static void restoreAmbientJndi() {
        TestJNDIContext.reset();
        if( priorJndiFactory != null ) {
            System.setProperty( Context.INITIAL_CONTEXT_FACTORY, priorJndiFactory );
        } else {
            System.clearProperty( Context.INITIAL_CONTEXT_FACTORY );
        }
    }

    @BeforeEach
    void isolateFromAmbientJndi() {
        pollutedJndiFactory = System.getProperty( Context.INITIAL_CONTEXT_FACTORY );
        System.clearProperty( Context.INITIAL_CONTEXT_FACTORY );
    }

    @AfterEach
    void restorePerTest() {
        if( pollutedJndiFactory != null ) {
            System.setProperty( Context.INITIAL_CONTEXT_FACTORY, pollutedJndiFactory );
        }
    }

    // --- getGroupDatabase()/initialize(): default class name + JNDI failure ---

    @Test
    void initialize_withNoConfiguredClass_fallsBackToJDBCGroupDatabaseAndWrapsFailure() {
        // No PROP_GROUPDATABASE set: getGroupDatabase() must default to JDBCGroupDatabase,
        // which then fails during its own initialize() (no JNDI context configured here) —
        // that failure must be wrapped and rethrown by DefaultGroupManager.initialize().
        final DefaultGroupManager mgr = new DefaultGroupManager();
        final WikiEngine engine = mock( WikiEngine.class );
        final Properties props = new Properties();
        when( engine.getWikiProperties() ).thenReturn( props );

        assertThrows( WikiSecurityException.class, () -> mgr.initialize( engine, props ) );
    }

    @Test
    void initialize_withUnknownGroupDatabaseClass_wrapsReflectiveFailure() {
        final DefaultGroupManager mgr = new DefaultGroupManager();
        final WikiEngine engine = mock( WikiEngine.class );
        final Properties props = new Properties();
        props.setProperty( GroupManager.PROP_GROUPDATABASE, "com.wikantik.auth.authorize.NoSuchGroupDatabaseClassXYZ" );
        when( engine.getWikiProperties() ).thenReturn( props );

        final WikiSecurityException ex = assertThrows( WikiSecurityException.class,
                () -> mgr.initialize( engine, props ) );
        assertTrue( ex.getMessage().contains( "denied" ) || ex.getCause() != null,
                "expected a wrapped reflective-instantiation failure: " + ex.getMessage() );
    }

    // --- setGroupInternal(): rollback restores the previous version on save() failure ---

    @Test
    void setGroupInternal_updateFailure_rollsBackToPreviousVersion() throws Exception {
        final DefaultGroupManager mgr = new DefaultGroupManager();
        final GroupDatabase db = mock( GroupDatabase.class );
        setField( mgr, "groupDatabase", db );

        final Session session = mock( Session.class );
        when( session.getUserPrincipal() ).thenReturn( () -> "tester" );

        // First save succeeds: caches the original version.
        final Group original = new Group( "Existing", "TestWiki" );
        original.add( new WikiPrincipal( "Al" ) );
        mgr.setGroupInternal( session, original );

        // Second save (an update) fails: must roll back to the cached original.
        doThrow( new WikiSecurityException( "simulated save failure" ) ).when( db ).save( any(), any() );
        final Group updated = new Group( "Existing", "TestWiki" );
        updated.add( new WikiPrincipal( "Bob" ) );
        final WikiSecurityException ex = assertThrows( WikiSecurityException.class,
                () -> mgr.setGroupInternal( session, updated ) );
        assertTrue( ex.getMessage().contains( "rolled back" ), "unexpected message: " + ex.getMessage() );

        final Group restored = mgr.getGroup( "Existing" );
        assertTrue( restored.isMember( new WikiPrincipal( "Al" ) ), "rollback must restore the previous member list" );
        assertFalse( restored.isMember( new WikiPrincipal( "Bob" ) ), "the failed update's members must not stick" );
    }

    @Test
    void setGroupInternal_newGroupFailure_hasNothingToRollBackTo() throws Exception {
        final DefaultGroupManager mgr = new DefaultGroupManager();
        final GroupDatabase db = mock( GroupDatabase.class );
        doThrow( new WikiSecurityException( "simulated save failure" ) ).when( db ).save( any(), any() );
        setField( mgr, "groupDatabase", db );

        final Session session = mock( Session.class );
        when( session.getUserPrincipal() ).thenReturn( () -> "tester" );

        final Group fresh = new Group( "BrandNew", "TestWiki" );
        final WikiSecurityException ex = assertThrows( WikiSecurityException.class,
                () -> mgr.setGroupInternal( session, fresh ) );
        assertFalse( ex.getMessage().contains( "rolled back" ),
                "a brand-new group has no previous version to roll back to: " + ex.getMessage() );
    }

    // --- removeWikiEventListener ---

    @Test
    void removeWikiEventListener_doesNotThrow() {
        final DefaultGroupManager mgr = new DefaultGroupManager();
        final WikiEventListener listener = event -> { };
        assertDoesNotThrow( () -> mgr.addWikiEventListener( listener ) );
        assertDoesNotThrow( () -> mgr.removeWikiEventListener( listener ) );
    }

    // --- actionPerformed(): a GroupDatabase failure mid-cascade is logged, not propagated ---

    @Test
    void actionPerformed_whenGroupDatabaseFails_logsAndDoesNotThrow() throws Exception {
        final DefaultGroupManager mgr = new DefaultGroupManager();
        final GroupDatabase db = mock( GroupDatabase.class );
        when( db.groups() ).thenThrow( new WikiSecurityException( "simulated groups() failure" ) );
        setField( mgr, "groupDatabase", db );

        final UserProfile oldProfile = mock( UserProfile.class );
        when( oldProfile.getLoginName() ).thenReturn( "alice" );
        when( oldProfile.getFullname() ).thenReturn( "Alice" );
        when( oldProfile.getWikiName() ).thenReturn( "Alice" );
        final UserProfile newProfile = mock( UserProfile.class );
        when( newProfile.getFullname() ).thenReturn( "AliceRenamed" );

        final WikiSecurityEvent event = mock( WikiSecurityEvent.class );
        when( event.getType() ).thenReturn( WikiSecurityEvent.PROFILE_NAME_CHANGED );
        when( event.getSrc() ).thenReturn( mock( Session.class ) );
        when( event.getTarget() ).thenReturn( new UserProfile[]{ oldProfile, newProfile } );

        assertDoesNotThrow( () -> mgr.actionPerformed( event ),
                "a GroupDatabase failure during the profile-rename cascade must be logged, not thrown" );
    }

    // --- test helpers -------------------------------------------------------

    private static void setField( final Object target, final String fieldName, final Object value ) throws Exception {
        final Field f = DefaultGroupManager.class.getDeclaredField( fieldName );
        f.setAccessible( true );
        f.set( target, value );
    }
}
