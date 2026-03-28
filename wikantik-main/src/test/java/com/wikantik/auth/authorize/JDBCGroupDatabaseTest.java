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
import com.wikantik.*;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.WikiSecurityException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NameAlreadyBoundException;
import javax.sql.DataSource;
import java.io.File;
import java.security.Principal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 */
@TestInstance( TestInstance.Lifecycle.PER_CLASS )
public class JDBCGroupDatabaseTest
{
    private final HsqlDbUtils m_hu = new HsqlDbUtils();
    private DataSource        m_ds;
    private WikiEngine        m_engine;

    private JDBCGroupDatabase m_db;

    private String            m_wiki;

    @BeforeAll
    void startDatabase() throws Exception
    {
        m_hu.setUp();
        final Properties props = TestEngine.getTestProperties();
        m_engine = new TestEngine( props );
        m_wiki = m_engine.getApplicationName();

        // Set up the mock JNDI initial context
        TestJNDIContext.initialize();
        final Context initCtx = new InitialContext();
        try
        {
            initCtx.bind( "java:comp/env", new TestJNDIContext() );
        }
        catch( final NameAlreadyBoundException e )
        {
            // ignore
        }
        final Context ctx = (Context) initCtx.lookup( "java:comp/env" );
        m_ds = new TestJDBCDataSource( new File( "target/test-classes/wikantik-custom.properties" ), m_hu.getDriverUrl() );
        ctx.bind( JDBCGroupDatabase.DEFAULT_GROUPDB_DATASOURCE, m_ds );

        m_db = new JDBCGroupDatabase();
        m_db.initialize( m_engine, new Properties() );
    }

    @BeforeEach
    public void setUp() throws Exception
    {
        // Reset data using DML instead of DDL teardown+setup
        try( final Connection conn = m_ds.getConnection();
             final Statement stmt = conn.createStatement() )
        {
            stmt.executeUpdate( "DELETE FROM group_members" );
            stmt.executeUpdate( "DELETE FROM groups" );
            // Re-insert seed data
            stmt.executeUpdate( "INSERT INTO groups (name, created, modified) VALUES ('TV', '2006-06-20 14:50:54.00000000', '2006-06-20 14:50:54.00000000')" );
            stmt.executeUpdate( "INSERT INTO group_members (name, member) VALUES ('TV', 'Archie Bunker')" );
            stmt.executeUpdate( "INSERT INTO group_members (name, member) VALUES ('TV', 'BullwinkleMoose')" );
            stmt.executeUpdate( "INSERT INTO group_members (name, member) VALUES ('TV', 'Fred Friendly')" );
            stmt.executeUpdate( "INSERT INTO groups (name, created, modified) VALUES ('Literature', '2006-06-20 14:50:54.00000000', '2006-06-20 14:50:54.00000000')" );
            stmt.executeUpdate( "INSERT INTO group_members (name, member) VALUES ('Literature', 'Charles Dickens')" );
            stmt.executeUpdate( "INSERT INTO group_members (name, member) VALUES ('Literature', 'Homer')" );
            stmt.executeUpdate( "INSERT INTO groups (name, created, modified) VALUES ('Art', '2006-06-20 14:50:54.00000000', '2006-06-20 14:50:54.00000000')" );
            stmt.executeUpdate( "INSERT INTO groups (name, created, modified) VALUES ('Admin', '2006-06-20 14:50:54.00000000', '2006-06-20 14:50:54.00000000')" );
            stmt.executeUpdate( "INSERT INTO group_members (name, member) VALUES ('Admin', 'Administrator')" );
        }
        catch( final SQLException e )
        {
            Assertions.fail("Looks like your database could not be connected to - "+
                  "please make sure that you have started your database, exception: " + e.getMessage());
        }
    }

    @AfterAll
    void stopDatabase() throws Exception
    {
        m_hu.tearDown();
    }

    @Test
    public void testDelete() throws WikiException
    {
        // First, count the number of groups in the db now.
        final int oldUserCount = m_db.groups().length;

        // Create a new group with random name
        final String name = "TestGroup" + System.currentTimeMillis();
        Group group = new Group( name, m_wiki );
        final Principal al = new WikiPrincipal( "Al" );
        final Principal bob = new WikiPrincipal( "Bob" );
        group.add( al );
        group.add( bob );
        m_db.save(group, new WikiPrincipal( "Tester") );

        // Make sure the profile saved successfully
        group = backendGroup( name );
        Assertions.assertEquals( name, group.getName() );
        Assertions.assertEquals( oldUserCount+1, m_db.groups().length );

        // Now delete the profile; should be back to old count
        m_db.delete( group );
        Assertions.assertEquals( oldUserCount, m_db.groups().length );
    }

    @Test
    public void testGroups() throws WikiSecurityException
    {
        // Test file has 4 groups in it: TV, Literature, Art, and Admin
        final Group[] groups = m_db.groups();
        Assertions.assertEquals( 4, groups.length );

        Group group;

        // Group TV has 3 members
        group = backendGroup( "TV" );
        Assertions.assertEquals("TV", group.getName() );
        Assertions.assertEquals( 3, group.members().length );

        // Group Literature has 2 members
        group = backendGroup( "Literature" );
        Assertions.assertEquals("Literature", group.getName() );
        Assertions.assertEquals( 2, group.members().length );

        // Group Art has no members
        group = backendGroup( "Art" );
        Assertions.assertEquals("Art", group.getName() );
        Assertions.assertEquals( 0, group.members().length );

        // Group Admin has 1 member (Administrator)
        group = backendGroup( "Admin" );
        Assertions.assertEquals("Admin", group.getName() );
        Assertions.assertEquals( 1, group.members().length );
        Assertions.assertEquals( "Administrator", group.members()[0].getName() );

        // Group Archaeology doesn't exist
        try
        {
            backendGroup( "Archaeology" );
            // We should never get here
            Assertions.fail();
        }
        catch (final NoSuchPrincipalException e)
        {
            Assertions.assertTrue(true);
        }
    }

    @Test
    public void testSave() throws Exception
    {
        // Create a new group with random name
        final String name = "TestGroup" + System.currentTimeMillis();
        Group group = new Group( name, m_wiki );
        final Principal al = new WikiPrincipal( "Al" );
        final Principal bob = new WikiPrincipal( "Bob" );
        final Principal cookie = new WikiPrincipal( "Cookie" );
        group.add( al );
        group.add( bob );
        group.add( cookie );
        m_db.save(group, new WikiPrincipal( "Tester" ) );

        // Make sure the profile saved successfully
        group = backendGroup( name );
        Assertions.assertEquals( name, group.getName() );
        Assertions.assertEquals( 3, group.members().length );
        Assertions.assertTrue( group.isMember( new WikiPrincipal( "Al" ) ) );
        Assertions.assertTrue( group.isMember( new WikiPrincipal( "Bob" ) ) );
        Assertions.assertTrue( group.isMember( new WikiPrincipal( "Cookie" ) ) );

        // The back-end should have timestamped the create/modify fields
        Assertions.assertNotNull( group.getCreator() );
        Assertions.assertEquals( "Tester", group.getCreator() );
        Assertions.assertNotNull( group.getCreated() );
        Assertions.assertNotNull( group.getModifier() );
        Assertions.assertEquals( "Tester", group.getModifier() );
        Assertions.assertNotNull( group.getLastModified() );
        Assertions.assertNotSame( group.getCreated(), group.getLastModified() );

        // Remove the group
        m_db.delete( group );
    }

    @Test
    public void testResave() throws Exception
    {
        // Create a new group with random name & 3 members
        final String name = "TestGroup" + System.currentTimeMillis();
        Group group = new Group( name, m_wiki );
        final Principal al = new WikiPrincipal( "Al" );
        final Principal bob = new WikiPrincipal( "Bob" );
        final Principal cookie = new WikiPrincipal( "Cookie" );
        group.add( al );
        group.add( bob );
        group.add( cookie );
        m_db.save(group, new WikiPrincipal( "Tester" ) );

        // Make sure the profile saved successfully
        group = backendGroup( name );
        Assertions.assertEquals( name, group.getName() );

        // Modify the members by adding the group; re-add Al while we're at it
        final Principal dave = new WikiPrincipal( "Dave" );
        group.add( al );
        group.add( dave );
        m_db.save(group, new WikiPrincipal( "SecondTester" ) );

        // We should see 4 members and new timestamp info
        Principal[] members = group.members();
        Assertions.assertEquals( 4, members.length );
        Assertions.assertNotNull( group.getCreator() );
        Assertions.assertEquals( "Tester", group.getCreator() );
        Assertions.assertNotNull( group.getCreated() );
        Assertions.assertNotNull( group.getModifier() );
        Assertions.assertEquals( "SecondTester", group.getModifier() );
        Assertions.assertNotNull( group.getLastModified() );

        // Check the back-end; We should see the same thing
        group = backendGroup( name );
        members = group.members();
        Assertions.assertEquals( 4, members.length );
        Assertions.assertNotNull( group.getCreator() );
        Assertions.assertEquals( "Tester", group.getCreator() );
        Assertions.assertNotNull( group.getCreated() );
        Assertions.assertNotNull( group.getModifier() );
        Assertions.assertEquals( "SecondTester", group.getModifier() );
        Assertions.assertNotNull( group.getLastModified() );

        // Remove the group
        m_db.delete( group );
    }

    @Test
    public void testDeleteAdminGroupThrows() throws WikiSecurityException {
        final Group adminGroup = backendGroup( "Admin" );
        Assertions.assertThrows( WikiSecurityException.class, () -> m_db.delete( adminGroup ),
                "Deleting the Admin group must throw WikiSecurityException" );
        // Verify it still exists
        Assertions.assertNotNull( backendGroup( "Admin" ) );
    }

    @Test
    public void testSaveAdminGroupWithZeroMembersThrows() throws WikiSecurityException {
        final Group emptyAdmin = new Group( "Admin", m_wiki );
        Assertions.assertThrows( WikiSecurityException.class,
                () -> m_db.save( emptyAdmin, new WikiPrincipal( "Tester" ) ),
                "Saving the Admin group with zero members must throw WikiSecurityException" );
        // Verify original Admin group still has its member
        final Group actual = backendGroup( "Admin" );
        Assertions.assertEquals( 1, actual.members().length );
    }

    @Test
    public void testSaveAdminGroupWithMembersSucceeds() throws WikiSecurityException {
        final Group adminGroup = backendGroup( "Admin" );
        adminGroup.add( new WikiPrincipal( "NewAdmin" ) );
        m_db.save( adminGroup, new WikiPrincipal( "Tester" ) );
        final Group updated = backendGroup( "Admin" );
        Assertions.assertEquals( 2, updated.members().length );
    }

    private Group backendGroup( final String name ) throws WikiSecurityException {
        final Group[] groups = m_db.groups();
        for( final Group group : groups ) {
            if( group.getName().equals( name ) ) {
                return group;
            }
        }
        throw new NoSuchPrincipalException( "No group named " + name );
    }
}
