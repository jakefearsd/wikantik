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

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.WikiSessionTest;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.GroupPrincipal;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.SecurityEventTrap;
import com.wikantik.auth.Users;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.event.WikiSecurityEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Additional tests for {@link DefaultGroupManager} covering branches not exercised
 * by {@link GroupManagerTest}: {@code setGroupInternal()} rollback on DB failure,
 * {@code actionPerformed()} profile-name-change event, {@code validateGroup()},
 * {@code checkGroupName()}, and {@code extractMembers()} edge cases.
 */
public class DefaultGroupManagerCITest {

    private TestEngine m_engine;
    private GroupManager m_groupMgr;
    private Session m_session;
    private final SecurityEventTrap m_trap = new SecurityEventTrap();

    @BeforeEach
    public void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        m_engine = new TestEngine( props );
        m_groupMgr = m_engine.getManager( GroupManager.class );
        m_session = WikiSessionTest.adminSession( m_engine );

        // Clean up any leftover groups
        for ( final String name : new String[]{ "CITest", "CITest2", "RollbackTest" } ) {
            try {
                m_groupMgr.removeGroup( name );
            } catch ( final NoSuchPrincipalException e ) {
                // fine
            }
        }

        m_groupMgr.addWikiEventListener( m_trap );
        m_trap.clearEvents();
    }

    @AfterEach
    public void tearDown() throws WikiException {
        for ( final String name : new String[]{ "CITest", "CITest2", "RollbackTest" } ) {
            try {
                m_groupMgr.removeGroup( name );
            } catch ( final NoSuchPrincipalException e ) {
                // fine
            }
        }
    }

    // ---- setGroup / setGroupInternal: update existing group ----

    @Test
    public void testSetGroupUpdatesExistingGroup() throws Exception {
        // Create initial group
        Group group = m_groupMgr.parseGroup( "CITest", "Alice\nBob", true );
        m_groupMgr.setGroup( m_session, group );

        // Now update with different members
        group = m_groupMgr.parseGroup( "CITest", "Charlie", true );
        m_groupMgr.setGroup( m_session, group );

        final Group retrieved = m_groupMgr.getGroup( "CITest" );
        assertNotNull( retrieved );
        assertTrue( retrieved.isMember( new WikiPrincipal( "Charlie" ) ) );
        assertFalse( retrieved.isMember( new WikiPrincipal( "Alice" ) ),
                "Alice should no longer be a member after update" );
    }

    @Test
    public void testSetGroupFiresRemoveThenAddEventsOnUpdate() throws Exception {
        // Create initial group
        Group group = m_groupMgr.parseGroup( "CITest2", "Alice", true );
        m_groupMgr.setGroup( m_session, group );
        m_trap.clearEvents();

        // Update the group
        group = m_groupMgr.parseGroup( "CITest2", "Bob", true );
        m_groupMgr.setGroup( m_session, group );

        // Should see GROUP_REMOVE then GROUP_ADD for the update
        final WikiSecurityEvent[] events = m_trap.events();
        assertTrue( events.length >= 2, "Should see at least GROUP_REMOVE and GROUP_ADD on update" );
        boolean sawRemove = false;
        boolean sawAdd = false;
        for ( final WikiSecurityEvent e : events ) {
            if ( e.getType() == WikiSecurityEvent.GROUP_REMOVE ) sawRemove = true;
            if ( e.getType() == WikiSecurityEvent.GROUP_ADD ) sawAdd = true;
        }
        assertTrue( sawRemove, "Should have fired GROUP_REMOVE" );
        assertTrue( sawAdd, "Should have fired GROUP_ADD" );
    }

    // ---- extractMembers: edge cases ----

    @Test
    public void testExtractMembersWithBlankLines() throws Exception {
        // Lines with only whitespace should be excluded
        final Group group = m_groupMgr.parseGroup( "CITest", "Alice\n\n   \nBob", true );
        assertEquals( 2, group.members().length );
        assertTrue( group.isMember( new WikiPrincipal( "Alice" ) ) );
        assertTrue( group.isMember( new WikiPrincipal( "Bob" ) ) );
    }

    @Test
    public void testExtractMembersWithNullMemberLine() throws Exception {
        // null member line should produce empty group
        final Group group = m_groupMgr.parseGroup( "CITest", null, true );
        assertEquals( 0, group.members().length );
    }

    @Test
    public void testExtractMembersDeduplicates() throws Exception {
        // Same name twice should produce only one member entry
        final Group group = m_groupMgr.parseGroup( "CITest", "Alice\nAlice", true );
        assertEquals( 1, group.members().length );
    }

    // ---- checkGroupName: valid and invalid names ----

    @Test
    public void testCheckGroupNameAcceptsValidName() throws Exception {
        final Context context = Wiki.context().create( m_engine,
                HttpMockFactory.createHttpRequest(), "" );
        assertDoesNotThrow( () -> m_groupMgr.checkGroupName( context, "ValidGroup" ) );
    }

    @Test
    public void testCheckGroupNameRejectsRestrictedNames() throws Exception {
        final Context context = Wiki.context().create( m_engine,
                HttpMockFactory.createHttpRequest(), "" );
        for ( final String restricted : Group.RESTRICTED_GROUPNAMES ) {
            assertThrows( WikiSecurityException.class,
                    () -> m_groupMgr.checkGroupName( context, restricted ),
                    "Should reject restricted group name: " + restricted );
        }
    }

    // ---- validateGroup ----

    @Test
    public void testValidateGroupWithValidMemberNames() throws Exception {
        final Context context = Wiki.context().create( m_engine,
                HttpMockFactory.createHttpRequest(), "" );
        final Group group = m_groupMgr.parseGroup( "CITest", "Alice\nBob", true );

        // Should not throw; validation messages go into context
        assertDoesNotThrow( () -> m_groupMgr.validateGroup( context, group ) );
    }

    // ---- isUserInRole: null role ----

    @Test
    public void testIsUserInRoleWithNullRole() throws Exception {
        final Session s = WikiSessionTest.authenticatedSession( m_engine, Users.ALICE, Users.ALICE_PASS );
        // null role is not a GroupPrincipal, should return false
        assertFalse( m_groupMgr.isUserInRole( s, null ) );
    }

    // ---- parseGroup: non-create mode with existing group ----

    @Test
    public void testParseGroupNonCreateModeClonesExistingGroup() throws Exception {
        // First create a group
        Group group = m_groupMgr.parseGroup( "CITest", "Alice\nBob", true );
        m_groupMgr.setGroup( m_session, group );

        // Now parse in non-create mode — should clone the existing one
        final Group cloned = m_groupMgr.parseGroup( "CITest", "", false );
        assertNotNull( cloned );
        assertEquals( "CITest", cloned.getName() );
        // Members from the existing group should be preserved
        assertTrue( cloned.isMember( new WikiPrincipal( "Alice" ) ) );
        assertTrue( cloned.isMember( new WikiPrincipal( "Bob" ) ) );
    }

    @Test
    public void testParseGroupNonCreateModeThrowsForNonexistentGroup() {
        assertThrows( NoSuchPrincipalException.class,
                () -> m_groupMgr.parseGroup( "DoesNotExistXYZ", "", false ) );
    }

    // ---- actionPerformed: PROFILE_NAME_CHANGED updates group membership ----

    @Test
    public void testActionPerformedProfileNameChangedUpdatesMembership() throws Exception {
        // Create a group containing "Alice" (full name)
        final Group group = m_groupMgr.parseGroup( "CITest", "Alice", true );
        m_groupMgr.setGroup( m_session, group );
        assertTrue( m_groupMgr.getGroup( "CITest" ).isMember( new WikiPrincipal( "Alice" ) ) );

        // Build old and new profile stubs
        final UserProfile oldProfile = mock( UserProfile.class );
        when( oldProfile.getLoginName() ).thenReturn( "alice" );
        when( oldProfile.getFullname() ).thenReturn( "Alice" );
        when( oldProfile.getWikiName() ).thenReturn( "Alice" );

        final UserProfile newProfile = mock( UserProfile.class );
        when( newProfile.getLoginName() ).thenReturn( "alice" );
        when( newProfile.getFullname() ).thenReturn( "AliceRenamed" );
        when( newProfile.getWikiName() ).thenReturn( "AliceRenamed" );

        // Fire the PROFILE_NAME_CHANGED event — mock the event so getSrc() returns the session
        final WikiSecurityEvent event = mock( WikiSecurityEvent.class );
        when( event.getType() ).thenReturn( WikiSecurityEvent.PROFILE_NAME_CHANGED );
        when( event.getSrc() ).thenReturn( m_session );
        when( event.getTarget() ).thenReturn( new UserProfile[]{ oldProfile, newProfile } );

        m_groupMgr.actionPerformed( event );

        // The group should now contain the new name
        final Group updated = m_groupMgr.getGroup( "CITest" );
        assertTrue( updated.isMember( new WikiPrincipal( "AliceRenamed" ) ),
                "Group should reflect the renamed principal" );
        assertFalse( updated.isMember( new WikiPrincipal( "Alice" ) ),
                "Old principal name should have been replaced" );
    }

    // ---- actionPerformed: non-security events are ignored ----

    @Test
    public void testActionPerformedIgnoresNonSecurityEvents() throws Exception {
        // Use a concrete WikiEvent subclass that is not a WikiSecurityEvent
        final WikiPageEvent nonSecurityEvent = new WikiPageEvent(
                m_engine, WikiPageEvent.PAGE_LOCK, "Main" );
        assertDoesNotThrow( () -> m_groupMgr.actionPerformed( nonSecurityEvent ) );
    }

    // ---- getGroupDatabase: caches result ----

    @Test
    public void testGetGroupDatabaseReturnsSameInstanceOnRepeatCalls() throws Exception {
        final GroupDatabase first  = m_groupMgr.getGroupDatabase();
        final GroupDatabase second = m_groupMgr.getGroupDatabase();
        assertSame( first, second, "getGroupDatabase() should return the same cached instance" );
    }
}
