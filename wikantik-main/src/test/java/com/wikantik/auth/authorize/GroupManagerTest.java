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

import org.apache.commons.lang3.ArrayUtils;
import com.wikantik.TestEngine;
import com.wikantik.WikiSessionTest;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.auth.GroupPrincipal;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.SecurityEventTrap;
import com.wikantik.auth.Users;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.event.WikiSecurityEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.Properties;

public class GroupManagerTest
{
    private TestEngine        m_engine;

    private GroupManager      m_groupMgr;

    private final SecurityEventTrap m_trap = new SecurityEventTrap();

    private Session m_session;

    @BeforeEach
    public void setUp() throws Exception
    {
        final Properties props = TestEngine.getTestProperties();

        m_engine = new TestEngine( props );
        m_groupMgr = m_engine.getManager( GroupManager.class );
        m_session = WikiSessionTest.adminSession( m_engine );

        // Flush any pre-existing groups (left over from previous Assertions.failures, perhaps)
        try
        {
            m_groupMgr.removeGroup( "Test" );
            m_groupMgr.removeGroup( "Test2" );
            m_groupMgr.removeGroup( "Test3" );
        }
        catch ( final NoSuchPrincipalException e )
        {
            // It's not a problem if we can't find the principals...
        }

        m_groupMgr.addWikiEventListener( m_trap );
        m_trap.clearEvents();

        // Add 3 test groups
        Group group;
        group = m_groupMgr.parseGroup( "Test", "Alice \n Bob \n Charlie", true );
        m_groupMgr.setGroup( m_session, group );
        group = m_groupMgr.parseGroup( "Test2", "Bob", true );
        m_groupMgr.setGroup( m_session, group );
        group = m_groupMgr.parseGroup( "Test3", "Fred Flintstone", true );
        m_groupMgr.setGroup( m_session, group );

        // We should see 3 events: 1 for each group add
        Assertions.assertEquals( 3, m_trap.events().length );
        m_trap.clearEvents();
    }

    @AfterEach
    public void tearDown() throws WikiException
    {
        m_groupMgr.removeGroup( "Test" );
        m_groupMgr.removeGroup( "Test2" );
        m_groupMgr.removeGroup( "Test3" );
   }

    @Test
    public void testParseGroup() throws WikiSecurityException
    {
        String members = "Biff";
        Group group = m_groupMgr.parseGroup( "Group1", members, true );
        Assertions.assertEquals( 1, group.members().length );
        Assertions.assertTrue ( group.isMember( new WikiPrincipal( "Biff" ) ) );

        members = "Biff \n SteveAustin \n FredFlintstone";
        group = m_groupMgr.parseGroup( "Group2", members, true );
        Assertions.assertEquals( 3, group.members().length );
        Assertions.assertTrue ( group.isMember( new WikiPrincipal( "Biff" ) ) );
        Assertions.assertTrue ( group.isMember( new WikiPrincipal( "SteveAustin" ) ) );
        Assertions.assertTrue ( group.isMember( new WikiPrincipal( "FredFlintstone" ) ) );
    }

    @Test
    public void testGetRoles()
    {
        final Principal[] roles = m_groupMgr.getRoles();
        Assertions.assertTrue( ArrayUtils.contains( roles, new GroupPrincipal( "Test" ) ), "Found Test" );
        Assertions.assertTrue( ArrayUtils.contains( roles, new GroupPrincipal( "Test2" ) ), "Found Test2" );
        Assertions.assertTrue( ArrayUtils.contains( roles, new GroupPrincipal( "Test3" ) ), "Found Test3" );
    }

    @Test
    public void testGroupMembership() throws Exception
    {
        // Anonymous; should belong to NO groups
        Session s = WikiSessionTest.anonymousSession( m_engine );
        Assertions.assertFalse( m_groupMgr.isUserInRole( s, new GroupPrincipal( "Test" ) ) );
        Assertions.assertFalse( m_groupMgr.isUserInRole( s, new GroupPrincipal( "Test2" ) ) );
        Assertions.assertFalse( m_groupMgr.isUserInRole( s, new GroupPrincipal( "Test3" ) ) );
        Assertions.assertFalse( m_groupMgr.isUserInRole( s, new GroupPrincipal( "NonExistant" ) ) );

        // Alice is asserted; should belong to NO groups
        s = WikiSessionTest.assertedSession( m_engine, Users.ALICE );
        Assertions.assertFalse( m_groupMgr.isUserInRole( s, new GroupPrincipal( "Test" ) ) );
        Assertions.assertFalse( m_groupMgr.isUserInRole( s, new GroupPrincipal( "Test2" ) ) );
        Assertions.assertFalse( m_groupMgr.isUserInRole( s, new GroupPrincipal( "Test3" ) ) );
        Assertions.assertFalse( m_groupMgr.isUserInRole( s, new GroupPrincipal( "NonExistant" ) ) );

        // Alice is authenticated; should belong to Test
        s = WikiSessionTest.authenticatedSession( m_engine, Users.ALICE, Users.ALICE_PASS );
        Assertions.assertTrue( m_groupMgr.isUserInRole( s, new GroupPrincipal( "Test" ) ) );
        Assertions.assertFalse( m_groupMgr.isUserInRole( s, new GroupPrincipal( "Test2" ) ) );
        Assertions.assertFalse( m_groupMgr.isUserInRole( s, new GroupPrincipal( "Test3" ) ) );
        Assertions.assertFalse( m_groupMgr.isUserInRole( s, new GroupPrincipal( "NonExistant" ) ) );

        // Bob is authenticated; should belong to Test & Test2
        s = WikiSessionTest.authenticatedSession( m_engine, Users.BOB, Users.BOB_PASS );
        Assertions.assertTrue( m_groupMgr.isUserInRole( s, new GroupPrincipal( "Test" ) ) );
        Assertions.assertTrue( m_groupMgr.isUserInRole( s, new GroupPrincipal( "Test2" ) ) );
        Assertions.assertFalse( m_groupMgr.isUserInRole( s, new GroupPrincipal( "Test3" ) ) );
        Assertions.assertFalse( m_groupMgr.isUserInRole( s, new GroupPrincipal( "NonExistant" ) ) );

        // Charlie is authenticated; should belong to Test
        s = WikiSessionTest.authenticatedSession( m_engine, Users.CHARLIE, Users.CHARLIE_PASS );
        Assertions.assertTrue( m_groupMgr.isUserInRole( s, new GroupPrincipal( "Test" ) ) );
        Assertions.assertFalse( m_groupMgr.isUserInRole( s, new GroupPrincipal( "Test2" ) ) );
        Assertions.assertFalse( m_groupMgr.isUserInRole( s, new GroupPrincipal( "Test3" ) ) );
        Assertions.assertFalse( m_groupMgr.isUserInRole( s, new GroupPrincipal( "NonExistant" ) ) );

        // Fred is authenticated; should belong to Test3
        s = WikiSessionTest.authenticatedSession( m_engine, Users.FRED, Users.FRED_PASS );
        Assertions.assertFalse( m_groupMgr.isUserInRole( s, new GroupPrincipal( "Test" ) ) );
        Assertions.assertFalse( m_groupMgr.isUserInRole( s, new GroupPrincipal( "Test2" ) ) );
        Assertions.assertTrue( m_groupMgr.isUserInRole( s, new GroupPrincipal( "Test3" ) ) );
        Assertions.assertFalse( m_groupMgr.isUserInRole( s, new GroupPrincipal( "NonExistant" ) ) );

        // Nobody loves Biff!
        s = WikiSessionTest.authenticatedSession( m_engine, Users.BIFF, Users.BIFF_PASS );
        Assertions.assertFalse( m_groupMgr.isUserInRole( s, new GroupPrincipal( "Test" ) ) );
        Assertions.assertFalse( m_groupMgr.isUserInRole( s, new GroupPrincipal( "Test2" ) ) );
        Assertions.assertFalse( m_groupMgr.isUserInRole( s, new GroupPrincipal( "Test3" ) ) );
        Assertions.assertFalse( m_groupMgr.isUserInRole( s, new GroupPrincipal( "NonExistant" ) ) );
    }

    @Test
    public void testGroupAddEvents() throws Exception
    {
        // Flush any pre-existing groups (left over from previous Assertions.failures, perhaps)
        try
        {
            m_groupMgr.removeGroup( "Events" );
        }
        catch ( final NoSuchPrincipalException e )
        {
            // It's not a problem if we get here...
        }
        m_trap.clearEvents();

        Group group = m_groupMgr.parseGroup( "Events", "", true );
        m_groupMgr.setGroup( m_session, group );
        final WikiSecurityEvent event;
        group = m_groupMgr.getGroup( "Events" );
        group.add( new WikiPrincipal( "Alice" ) );
        group.add( new WikiPrincipal( "Bob" ) );
        group.add( new WikiPrincipal( "Charlie" ) );

        // We should see a GROUP_ADD event
        final WikiSecurityEvent[] events = m_trap.events();
        Assertions.assertEquals( 1, events.length );
        event = events[0];
        Assertions.assertEquals( m_groupMgr, event.getSrc() );
        Assertions.assertEquals( WikiSecurityEvent.GROUP_ADD, event.getType() );
        Assertions.assertEquals( group, event.getTarget() );

        // Clean up
        m_groupMgr.removeGroup( "Events" );
    }

    /**
     * Tests that getGroup returns an existing group by name.
     */
    @Test
    public void testGetGroupExisting() throws Exception {
        final Group group = m_groupMgr.getGroup( "Test" );
        Assertions.assertNotNull( group, "Should return the Test group" );
        Assertions.assertEquals( "Test", group.getName() );
        Assertions.assertTrue( group.isMember( new WikiPrincipal( "Alice" ) ) );
        Assertions.assertTrue( group.isMember( new WikiPrincipal( "Bob" ) ) );
        Assertions.assertTrue( group.isMember( new WikiPrincipal( "Charlie" ) ) );
    }

    /**
     * Tests that getGroup throws NoSuchPrincipalException for a nonexistent group.
     */
    @Test
    public void testGetGroupNonexistent() {
        Assertions.assertThrows( NoSuchPrincipalException.class,
            () -> m_groupMgr.getGroup( "NonExistentGroupXYZ" ),
            "Should throw NoSuchPrincipalException for nonexistent group" );
    }

    /**
     * Tests the CRUD lifecycle: create, read, modify, delete a group.
     */
    @Test
    public void testGroupCrudLifecycle() throws Exception {
        final String groupName = "LifecycleTest";
        try {
            m_groupMgr.removeGroup( groupName );
        } catch ( final NoSuchPrincipalException e ) {
            // Fine, doesn't exist yet
        }
        m_trap.clearEvents();

        // CREATE: parse and set a new group
        Group group = m_groupMgr.parseGroup( groupName, "Alice\nBob", true );
        m_groupMgr.setGroup( m_session, group );

        // READ: retrieve and verify members
        group = m_groupMgr.getGroup( groupName );
        Assertions.assertNotNull( group );
        Assertions.assertEquals( 2, group.members().length );
        Assertions.assertTrue( group.isMember( new WikiPrincipal( "Alice" ) ) );
        Assertions.assertTrue( group.isMember( new WikiPrincipal( "Bob" ) ) );

        // UPDATE: modify membership
        group = m_groupMgr.parseGroup( groupName, "Alice\nCharlie\nDave", true );
        m_groupMgr.setGroup( m_session, group );
        group = m_groupMgr.getGroup( groupName );
        Assertions.assertEquals( 3, group.members().length );
        Assertions.assertTrue( group.isMember( new WikiPrincipal( "Alice" ) ) );
        Assertions.assertFalse( group.isMember( new WikiPrincipal( "Bob" ) ), "Bob should have been removed" );
        Assertions.assertTrue( group.isMember( new WikiPrincipal( "Charlie" ) ) );
        Assertions.assertTrue( group.isMember( new WikiPrincipal( "Dave" ) ) );

        // DELETE
        m_groupMgr.removeGroup( groupName );
        Assertions.assertThrows( NoSuchPrincipalException.class,
            () -> m_groupMgr.getGroup( groupName ),
            "Group should no longer exist after removal" );
    }

    /**
     * Tests that removeGroup with a null name throws IllegalArgumentException.
     */
    @Test
    public void testRemoveGroupNull() {
        Assertions.assertThrows( IllegalArgumentException.class,
            () -> m_groupMgr.removeGroup( null ) );
    }

    /**
     * Tests that removeGroup with a nonexistent name throws NoSuchPrincipalException.
     */
    @Test
    public void testRemoveGroupNonexistent() {
        Assertions.assertThrows( NoSuchPrincipalException.class,
            () -> m_groupMgr.removeGroup( "DoesNotExistXYZ" ) );
    }

    /**
     * Tests that findRole returns a principal for an existing group and null for a nonexistent one.
     */
    @Test
    public void testFindRole() {
        final Principal found = m_groupMgr.findRole( "Test" );
        Assertions.assertNotNull( found, "findRole should return principal for existing group" );
        Assertions.assertEquals( "Test", found.getName() );

        final Principal notFound = m_groupMgr.findRole( "NonExistentRole12345" );
        Assertions.assertNull( notFound, "findRole should return null for nonexistent group" );
    }

    /**
     * Tests isUserInRole returns false for a null session.
     */
    @Test
    public void testIsUserInRoleNullSession() {
        Assertions.assertFalse( m_groupMgr.isUserInRole( null, new GroupPrincipal( "Test" ) ),
                                "isUserInRole should return false for null session" );
    }

    /**
     * Tests isUserInRole returns false for a non-GroupPrincipal role.
     */
    @Test
    public void testIsUserInRoleNonGroupPrincipal() throws Exception {
        final Session s = WikiSessionTest.authenticatedSession( m_engine, Users.ALICE, Users.ALICE_PASS );
        // WikiPrincipal is not a GroupPrincipal, so should return false
        Assertions.assertFalse( m_groupMgr.isUserInRole( s, new WikiPrincipal( "Test" ) ),
                                "isUserInRole should return false for non-GroupPrincipal" );
    }

    /**
     * Tests that parseGroup with null name in create mode defaults to "MyGroup".
     */
    @Test
    public void testParseGroupNullNameCreate() throws Exception {
        final Group group = m_groupMgr.parseGroup( null, "Alice", true );
        Assertions.assertNotNull( group );
        Assertions.assertEquals( "MyGroup", group.getName() );
    }

    /**
     * Tests that parseGroup with null name in non-create mode throws WikiSecurityException.
     */
    @Test
    public void testParseGroupNullNameNoCreate() {
        Assertions.assertThrows( WikiSecurityException.class,
            () -> m_groupMgr.parseGroup( null, "Alice", false ) );
    }

    /**
     * Tests that parseGroup rejects restricted group names.
     */
    @Test
    public void testParseGroupRestrictedName() {
        Assertions.assertThrows( WikiSecurityException.class,
            () -> m_groupMgr.parseGroup( "Anonymous", "Alice", true ),
            "Should reject restricted group name 'Anonymous'" );
    }

    /**
     * Tests that getGroupDatabase returns a non-null database.
     */
    @Test
    public void testGetGroupDatabase() throws Exception {
        Assertions.assertNotNull( m_groupMgr.getGroupDatabase(),
                                  "Group database should not be null" );
    }

}