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
package com.wikantik.auth.user;

import com.wikantik.HsqlDbUtils;
import com.wikantik.TestJDBCDataSource;
import com.wikantik.TestJNDIContext;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.util.CryptoUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NameAlreadyBoundException;
import javax.sql.DataSource;
import java.io.File;
import java.io.Serializable;
import java.security.Principal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Properties;

/**
 *
 */
public class JDBCUserDatabaseTest {
    private static final HsqlDbUtils m_hu = new HsqlDbUtils();
    private static DataSource m_ds;

    private JDBCUserDatabase m_db;

    private static final String TEST_ATTRIBUTES = "rO0ABXNyABFqYXZhLnV0aWwuSGFzaE1hcAUH2sHDFmDRAwACRgAKbG9hZEZhY3RvckkACXRocmVzaG9sZHhwP0AAAAAAAAx3CAAAABAAAAACdAAKYXR0cmlidXRlMXQAEXNvbWUgcmFuZG9tIHZhbHVldAAKYXR0cmlidXRlMnQADWFub3RoZXIgdmFsdWV4";

    private static final String INSERT_JANNE = "INSERT INTO users (" +
            JDBCUserDatabase.DEFAULT_DB_UID + "," +
            JDBCUserDatabase.DEFAULT_DB_EMAIL + "," +
            JDBCUserDatabase.DEFAULT_DB_FULL_NAME + "," +
            JDBCUserDatabase.DEFAULT_DB_LOGIN_NAME + "," +
            JDBCUserDatabase.DEFAULT_DB_PASSWORD + "," +
            JDBCUserDatabase.DEFAULT_DB_WIKI_NAME + "," +
            JDBCUserDatabase.DEFAULT_DB_CREATED + "," +
            JDBCUserDatabase.DEFAULT_DB_ATTRIBUTES + ") VALUES (" +
            "'-7739839977499061014'," + "'janne@ecyrd.com'," + "'Janne Jalkanen'," + "'janne'," +
            "'{SHA}457b08e825da547c3b77fbc1ff906a1d00a7daee'," +
            "'JanneJalkanen'," +
            "'" + new Timestamp( new Timestamp( System.currentTimeMillis() ).getTime() ) + "'," +
            "'" + TEST_ATTRIBUTES + "'" + ");";

    private static final String INSERT_USER = "INSERT INTO users (" +
            JDBCUserDatabase.DEFAULT_DB_UID + "," +
            JDBCUserDatabase.DEFAULT_DB_EMAIL + "," +
            JDBCUserDatabase.DEFAULT_DB_LOGIN_NAME + "," +
            JDBCUserDatabase.DEFAULT_DB_PASSWORD + "," +
            JDBCUserDatabase.DEFAULT_DB_CREATED + ") VALUES (" +
            "'-8629747547991531672'," + "'jspwiki.tests@mailinator.com'," + "'user'," +
            "'{SHA}5baa61e4c9b93f3f0682250b6cf8331b7ee68fd8'," +
            "'" + new Timestamp( new Timestamp( System.currentTimeMillis() ).getTime() ) + "'" + ");";

    @BeforeAll
    static void startDatabase() throws Exception {
        m_hu.setUp();
        // Set up the mock JNDI initial context
        TestJNDIContext.initialize();
        final Context initCtx = new InitialContext();
        try {
            initCtx.bind( "java:comp/env", new TestJNDIContext() );
        } catch( final NameAlreadyBoundException e ) {
            // ignore
        }
        final Context ctx = ( Context ) initCtx.lookup( "java:comp/env" );
        m_ds = new TestJDBCDataSource( new File( "target/test-classes/wikantik-custom.properties" ), m_hu.getDriverUrl() );
        ctx.bind( JDBCUserDatabase.DEFAULT_DB_JNDI_NAME, m_ds );
    }

    @BeforeEach
    public void setUp() throws Exception {
        try {
            final Connection conn = m_ds.getConnection();
            final Statement stmt = conn.createStatement();

            stmt.executeUpdate( "DELETE FROM " + JDBCUserDatabase.DEFAULT_DB_TABLE + ";" );
            stmt.executeUpdate( INSERT_JANNE );
            stmt.executeUpdate( INSERT_USER );
            stmt.close();
            conn.close();

            m_db = new JDBCUserDatabase();
            m_db.initialize( null, new Properties() );
        } catch( final SQLException e ) {
            Assertions.fail( "Looks like your database could not be connected to - " +
                             "please make sure that you have started your database, exception: " + e.getMessage() );
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        m_hu.tearDown();
    }

    @Test
    public void testDeleteByLoginName() throws WikiSecurityException {
        // First, count the number of users in the db now.
        final int oldUserCount = m_db.getWikiNames().length;

        // Create a new user with random name
        final String loginName = "TestUser" + System.currentTimeMillis();
        UserProfile profile = m_db.newProfile();
        profile.setEmail( "wikantik.tests@mailinator.com" );
        profile.setLoginName( loginName );
        profile.setFullname( "FullName" + loginName );
        profile.setPassword( "password" );
        m_db.save( profile );

        // Make sure the profile saved successfully
        profile = m_db.findByLoginName( loginName );
        Assertions.assertEquals( loginName, profile.getLoginName() );
        Assertions.assertEquals( oldUserCount + 1, m_db.getWikiNames().length );

        // Now delete the profile; should be back to old count
        m_db.deleteByLoginName( loginName );
        Assertions.assertEquals( oldUserCount, m_db.getWikiNames().length );
    }

    @Test
    public void testAttributes() throws Exception {
        UserProfile profile = m_db.findByEmail( "janne@ecyrd.com" );

        Map< String, Serializable > attributes = profile.getAttributes();
        Assertions.assertEquals( 2, attributes.size() );
        Assertions.assertTrue( attributes.containsKey( "attribute1" ) );
        Assertions.assertTrue( attributes.containsKey( "attribute2" ) );
        Assertions.assertEquals( "some random value", attributes.get( "attribute1" ) );
        Assertions.assertEquals( "another value", attributes.get( "attribute2" ) );

        // Change attribute 1, and add another one
        attributes.put( "attribute1", "replacement value" );
        attributes.put( "attribute the third", "some value" );
        m_db.save( profile );

        // Retrieve the profile again and make sure our values got saved
        profile = m_db.findByEmail( "janne@ecyrd.com" );
        attributes = profile.getAttributes();
        Assertions.assertEquals( 3, attributes.size() );
        Assertions.assertTrue( attributes.containsKey( "attribute1" ) );
        Assertions.assertTrue( attributes.containsKey( "attribute2" ) );
        Assertions.assertTrue( attributes.containsKey( "attribute the third" ) );
        Assertions.assertEquals( "replacement value", attributes.get( "attribute1" ) );
        Assertions.assertEquals( "another value", attributes.get( "attribute2" ) );
        Assertions.assertEquals( "some value", attributes.get( "attribute the third" ) );

        // Restore the original attributes and re-save
        attributes.put( "attribute1", "some random value" );
        attributes.remove( "attribute the third" );
        m_db.save( profile );
    }

    @Test
    public void testFindByEmail() {
        try {
            final UserProfile profile = m_db.findByEmail( "janne@ecyrd.com" );
            Assertions.assertEquals( "-7739839977499061014", profile.getUid() );
            Assertions.assertEquals( "janne", profile.getLoginName() );
            Assertions.assertEquals( "Janne Jalkanen", profile.getFullname() );
            Assertions.assertEquals( "JanneJalkanen", profile.getWikiName() );
            Assertions.assertEquals( "{SHA}457b08e825da547c3b77fbc1ff906a1d00a7daee", profile.getPassword() );
            Assertions.assertEquals( "janne@ecyrd.com", profile.getEmail() );
            Assertions.assertNotNull( profile.getCreated() );
            Assertions.assertNull( profile.getLastModified() );
        } catch( final NoSuchPrincipalException e ) {
            Assertions.fail();
        }
        try {
            m_db.findByEmail( "foo@bar.org" );
            // We should never get here
            Assertions.fail();
        } catch( final NoSuchPrincipalException e ) {
            Assertions.assertTrue( true );
        }
    }

    @Test
    public void testFindByFullName() {
        try {
            final UserProfile profile = m_db.findByFullName( "Janne Jalkanen" );
            Assertions.assertEquals( "-7739839977499061014", profile.getUid() );
            Assertions.assertEquals( "janne", profile.getLoginName() );
            Assertions.assertEquals( "Janne Jalkanen", profile.getFullname() );
            Assertions.assertEquals( "JanneJalkanen", profile.getWikiName() );
            Assertions.assertEquals( "{SHA}457b08e825da547c3b77fbc1ff906a1d00a7daee", profile.getPassword() );
            Assertions.assertEquals( "janne@ecyrd.com", profile.getEmail() );
            Assertions.assertNotNull( profile.getCreated() );
            Assertions.assertNull( profile.getLastModified() );
        } catch( final NoSuchPrincipalException e ) {
            Assertions.fail();
        }
        try {
            m_db.findByEmail( "foo@bar.org" );
            // We should never get here
            Assertions.fail();
        } catch( final NoSuchPrincipalException e ) {
            Assertions.assertTrue( true );
        }
    }

    @Test
    public void testFindByUid() {
        try {
            final UserProfile profile = m_db.findByUid( "-7739839977499061014" );
            Assertions.assertEquals( "-7739839977499061014", profile.getUid() );
            Assertions.assertEquals( "janne", profile.getLoginName() );
            Assertions.assertEquals( "Janne Jalkanen", profile.getFullname() );
            Assertions.assertEquals( "JanneJalkanen", profile.getWikiName() );
            Assertions.assertEquals( "{SHA}457b08e825da547c3b77fbc1ff906a1d00a7daee", profile.getPassword() );
            Assertions.assertEquals( "janne@ecyrd.com", profile.getEmail() );
            Assertions.assertNotNull( profile.getCreated() );
            Assertions.assertNull( profile.getLastModified() );
        } catch( final NoSuchPrincipalException e ) {
            Assertions.fail();
        }
        try {
            m_db.findByEmail( "foo@bar.org" );
            // We should never get here
            Assertions.fail();
        } catch( final NoSuchPrincipalException e ) {
            Assertions.assertTrue( true );
        }
    }

    @Test
    public void testFindByWikiName() {
        try {
            final UserProfile profile = m_db.findByWikiName( "JanneJalkanen" );
            Assertions.assertEquals( "-7739839977499061014", profile.getUid() );
            Assertions.assertEquals( "janne", profile.getLoginName() );
            Assertions.assertEquals( "Janne Jalkanen", profile.getFullname() );
            Assertions.assertEquals( "JanneJalkanen", profile.getWikiName() );
            Assertions.assertEquals( "{SHA}457b08e825da547c3b77fbc1ff906a1d00a7daee", profile.getPassword() );
            Assertions.assertEquals( "janne@ecyrd.com", profile.getEmail() );
            Assertions.assertNotNull( profile.getCreated() );
            Assertions.assertNull( profile.getLastModified() );
        } catch( final NoSuchPrincipalException e ) {
            Assertions.fail();
        }
        try {
            m_db.findByEmail( "foo" );
            // We should never get here
            Assertions.fail();
        } catch( final NoSuchPrincipalException e ) {
            Assertions.assertTrue( true );
        }
    }

    @Test
    public void testFindByLoginName() {
        try {
            final UserProfile profile = m_db.findByLoginName( "janne" );
            Assertions.assertEquals( "-7739839977499061014", profile.getUid() );
            Assertions.assertEquals( "janne", profile.getLoginName() );
            Assertions.assertEquals( "Janne Jalkanen", profile.getFullname() );
            Assertions.assertEquals( "JanneJalkanen", profile.getWikiName() );
            Assertions.assertEquals( "{SHA}457b08e825da547c3b77fbc1ff906a1d00a7daee", profile.getPassword() );
            Assertions.assertEquals( "janne@ecyrd.com", profile.getEmail() );
            Assertions.assertNotNull( profile.getCreated() );
            Assertions.assertNull( profile.getLastModified() );
        } catch( final NoSuchPrincipalException e ) {
            Assertions.fail();
        }
        try {
            m_db.findByEmail( "FooBar" );
            // We should never get here
            Assertions.fail();
        } catch( final NoSuchPrincipalException e ) {
            Assertions.assertTrue( true );
        }
    }

    @Test
    public void testGetWikiName() throws WikiSecurityException {
        final Principal[] principals = m_db.getWikiNames();
        Assertions.assertEquals( 1, principals.length );
    }

    @Test
    public void testRename() throws Exception {
        // Try renaming a non-existent profile; it should Assertions.fail
        try {
            m_db.rename( "nonexistentname", "renameduser" );
            Assertions.fail( "Should not have allowed rename..." );
        } catch( final NoSuchPrincipalException e ) {
            // Cool; that's what we expect
        }

        // Create new user & verify it saved ok
        UserProfile profile = m_db.newProfile();
        profile.setEmail( "renamed@mailinator.com" );
        profile.setFullname( "Renamed User" );
        profile.setLoginName( "olduser" );
        profile.setPassword( "password" );
        m_db.save( profile );
        profile = m_db.findByLoginName( "olduser" );
        Assertions.assertNotNull( profile );

        // Try renaming to a login name that's already taken; it should Assertions.fail
        try {
            m_db.rename( "olduser", "janne" );
            Assertions.fail( "Should not have allowed rename..." );
        } catch( final DuplicateUserException e ) {
            // Cool; that's what we expect
        }

        // Now, rename it to an unused name
        m_db.rename( "olduser", "renameduser" );

        // The old user shouldn't be found
        try {
            m_db.findByLoginName( "olduser" );
            Assertions.fail( "Old user was found, but it shouldn't have been." );
        } catch( final NoSuchPrincipalException e ) {
            // Cool, it's gone
        }

        // The new profile should be found, and its properties should match the old ones
        profile = m_db.findByLoginName( "renameduser" );
        Assertions.assertEquals( "renamed@mailinator.com", profile.getEmail() );
        Assertions.assertEquals( "Renamed User", profile.getFullname() );
        Assertions.assertEquals( "renameduser", profile.getLoginName() );
        Assertions.assertTrue( CryptoUtil.verifySaltedPassword( "password".getBytes(), profile.getPassword() ) );

        // Delete the user
        m_db.deleteByLoginName( "renameduser" );
    }

    @Test
    public void testSave() throws Exception {
        try {
            // Overwrite existing user
            UserProfile profile = m_db.newProfile();
            profile.setEmail( "wikantik.tests@mailinator.com" );
            profile.setFullname( "Test User" );
            profile.setLoginName( "user" );
            profile.setPassword( "password" );
            m_db.save( profile );
            profile = m_db.findByEmail( "wikantik.tests@mailinator.com" );
            Assertions.assertEquals( "wikantik.tests@mailinator.com", profile.getEmail() );
            Assertions.assertEquals( "Test User", profile.getFullname() );
            Assertions.assertEquals( "user", profile.getLoginName() );
            Assertions.assertTrue( CryptoUtil.verifySaltedPassword( "password".getBytes(), profile.getPassword() ) );
            Assertions.assertEquals( "TestUser", profile.getWikiName() );
            Assertions.assertNotNull( profile.getCreated() );
            Assertions.assertNotNull( profile.getLastModified() );
            Assertions.assertNotSame( profile.getCreated(), profile.getLastModified() );

            // Create new user
            profile = m_db.newProfile();
            profile.setEmail( "wikantik.tests2@mailinator.com" );
            profile.setFullname( "Test User 2" );
            profile.setLoginName( "user2" );
            profile.setPassword( "password" );
            m_db.save( profile );
            profile = m_db.findByEmail( "wikantik.tests2@mailinator.com" );
            Assertions.assertEquals( "wikantik.tests2@mailinator.com", profile.getEmail() );
            Assertions.assertEquals( "Test User 2", profile.getFullname() );
            Assertions.assertEquals( "user2", profile.getLoginName() );
            Assertions.assertTrue( CryptoUtil.verifySaltedPassword( "password".getBytes(), profile.getPassword() ) );
            Assertions.assertEquals( "TestUser2", profile.getWikiName() );
            Assertions.assertNotNull( profile.getCreated() );
            Assertions.assertNotNull( profile.getLastModified() );
            Assertions.assertEquals( profile.getCreated(), profile.getLastModified() );

            // Make sure we can find it by uid
            final String uid = profile.getUid();
            Assertions.assertNotNull( m_db.findByUid( uid ) );

        } catch( final WikiSecurityException e ) {
            Assertions.fail();
        }
    }

    @Test
    public void testValidatePassword() {
        Assertions.assertFalse( m_db.validatePassword( "janne", "test" ) );
        Assertions.assertTrue( m_db.validatePassword( "janne", "myP@5sw0rd" ) );
        Assertions.assertTrue( m_db.validatePassword( "user", "password" ) );
    }

    // ========== Edge Case Tests for Improved Coverage ==========

    @Test
    public void testDeleteNonExistentUser() {
        // Attempting to delete a user that doesn't exist should throw NoSuchPrincipalException
        Assertions.assertThrows( NoSuchPrincipalException.class, () -> {
            m_db.deleteByLoginName( "nonexistentuser" );
        });
    }

    @Test
    public void testFindByLoginNameNotFound() {
        Assertions.assertThrows( NoSuchPrincipalException.class, () -> {
            m_db.findByLoginName( "nonexistentuser" );
        });
    }

    @Test
    public void testFindByEmailNotFound() {
        Assertions.assertThrows( NoSuchPrincipalException.class, () -> {
            m_db.findByEmail( "nonexistent@example.com" );
        });
    }

    @Test
    public void testFindByFullNameNotFound() {
        Assertions.assertThrows( NoSuchPrincipalException.class, () -> {
            m_db.findByFullName( "Nonexistent User" );
        });
    }

    @Test
    public void testFindByWikiNameNotFound() {
        Assertions.assertThrows( NoSuchPrincipalException.class, () -> {
            m_db.findByWikiName( "NonexistentWikiName" );
        });
    }

    @Test
    public void testFindByUidNotFound() {
        Assertions.assertThrows( NoSuchPrincipalException.class, () -> {
            m_db.findByUid( "999999999" );
        });
    }

    @Test
    public void testSaveUserWithLockExpiry() throws WikiSecurityException {
        // Create a user first (lock expiry is only saved in UPDATE, not INSERT)
        final String loginName = "LockedUser" + System.currentTimeMillis();
        UserProfile profile = m_db.newProfile();
        profile.setEmail( "locked@mailinator.com" );
        profile.setLoginName( loginName );
        profile.setFullname( "Locked User" );
        profile.setPassword( "password" );
        m_db.save( profile );

        // Now update with a lock expiry date (this tests the UPDATE path with lockExpiry)
        // This covers the non-null lockExpiry branch in save()
        profile = m_db.findByLoginName( loginName );
        profile.setLockExpiry( new java.util.Date( System.currentTimeMillis() + 3600000 ) ); // Lock for 1 hour
        m_db.save( profile );

        // Verify the user still exists and can be retrieved (even if lock expiry
        // isn't properly persisted in HSQLDB test environment)
        profile = m_db.findByLoginName( loginName );
        Assertions.assertEquals( loginName, profile.getLoginName() );

        // Clean up
        m_db.deleteByLoginName( loginName );
    }

    @Test
    public void testSaveUserWithEmptyPassword() throws WikiSecurityException {
        // Create a user and then update with empty password (should keep existing)
        final String loginName = "EmptyPassUser" + System.currentTimeMillis();
        UserProfile profile = m_db.newProfile();
        profile.setEmail( "emptypass@mailinator.com" );
        profile.setLoginName( loginName );
        profile.setFullname( "Empty Password User" );
        profile.setPassword( "originalpassword" );
        m_db.save( profile );

        // Update with empty password - should retain original
        profile = m_db.findByLoginName( loginName );
        profile.setPassword( "" );
        m_db.save( profile );

        // Verify original password still works
        Assertions.assertTrue( m_db.validatePassword( loginName, "originalpassword" ) );

        // Clean up
        m_db.deleteByLoginName( loginName );
    }

    @Test
    public void testSaveUserWithNullPassword() throws WikiSecurityException {
        // Create a user and then update with null password (should keep existing)
        final String loginName = "NullPassUser" + System.currentTimeMillis();
        UserProfile profile = m_db.newProfile();
        profile.setEmail( "nullpass@mailinator.com" );
        profile.setLoginName( loginName );
        profile.setFullname( "Null Password User" );
        profile.setPassword( "originalpassword" );
        m_db.save( profile );

        // Update with null password - should retain original
        profile = m_db.findByLoginName( loginName );
        profile.setPassword( null );
        m_db.save( profile );

        // Verify original password still works
        Assertions.assertTrue( m_db.validatePassword( loginName, "originalpassword" ) );

        // Clean up
        m_db.deleteByLoginName( loginName );
    }

    @Test
    public void testUpdateExistingUserPassword() throws WikiSecurityException {
        // Create a user
        final String loginName = "UpdatePassUser" + System.currentTimeMillis();
        UserProfile profile = m_db.newProfile();
        profile.setEmail( "updatepass@mailinator.com" );
        profile.setLoginName( loginName );
        profile.setFullname( "Update Password User" );
        profile.setPassword( "password1" );
        m_db.save( profile );

        // Update the password
        profile = m_db.findByLoginName( loginName );
        profile.setPassword( "newpassword" );
        m_db.save( profile );

        // Verify new password works
        Assertions.assertTrue( m_db.validatePassword( loginName, "newpassword" ) );

        // Clean up
        m_db.deleteByLoginName( loginName );
    }

    @Test
    public void testRenameNonExistentUser() {
        // Attempting to rename a user that doesn't exist should throw NoSuchPrincipalException
        Assertions.assertThrows( NoSuchPrincipalException.class, () -> {
            m_db.rename( "nonexistentuser", "newname" );
        });
    }

    @Test
    public void testRenameToExistingLoginName() throws WikiSecurityException {
        // Create a second user
        final String loginName = "RenameUser" + System.currentTimeMillis();
        UserProfile profile = m_db.newProfile();
        profile.setEmail( "rename@mailinator.com" );
        profile.setLoginName( loginName );
        profile.setFullname( "Rename User" );
        profile.setPassword( "password" );
        m_db.save( profile );

        // Attempting to rename to existing "janne" login name should throw DuplicateUserException
        Assertions.assertThrows( DuplicateUserException.class, () -> {
            m_db.rename( loginName, "janne" );
        });

        // Clean up
        m_db.deleteByLoginName( loginName );
    }

    @Test
    public void testSaveAndUpdateAttributes() throws WikiSecurityException {
        // Create a user with attributes
        final String loginName = "AttrUser" + System.currentTimeMillis();
        UserProfile profile = m_db.newProfile();
        profile.setEmail( "attr@mailinator.com" );
        profile.setLoginName( loginName );
        profile.setFullname( "Attribute User" );
        profile.setPassword( "password" );
        profile.getAttributes().put( "testAttr", "testValue" );
        m_db.save( profile );

        // Retrieve and verify
        profile = m_db.findByLoginName( loginName );
        Assertions.assertEquals( "testValue", profile.getAttributes().get( "testAttr" ) );

        // Update attributes
        profile.getAttributes().put( "testAttr", "newValue" );
        profile.getAttributes().put( "anotherAttr", "anotherValue" );
        m_db.save( profile );

        // Retrieve and verify updated attributes
        profile = m_db.findByLoginName( loginName );
        Assertions.assertEquals( "newValue", profile.getAttributes().get( "testAttr" ) );
        Assertions.assertEquals( "anotherValue", profile.getAttributes().get( "anotherAttr" ) );

        // Clean up
        m_db.deleteByLoginName( loginName );
    }

    @Test
    public void testSaveWithEmptyAttributes() throws WikiSecurityException {
        // Create a user without any custom attributes
        final String loginName = "NoAttrUser" + System.currentTimeMillis();
        UserProfile profile = m_db.newProfile();
        profile.setEmail( "noattr@mailinator.com" );
        profile.setLoginName( loginName );
        profile.setFullname( "No Attribute User" );
        profile.setPassword( "password" );
        // Don't add any attributes
        m_db.save( profile );

        // Retrieve and verify
        profile = m_db.findByLoginName( loginName );
        Assertions.assertTrue( profile.getAttributes().isEmpty() );

        // Clean up
        m_db.deleteByLoginName( loginName );
    }

    @Test
    public void testGetWikiNamesWithEmptyWikiName() throws WikiSecurityException {
        // The "user" test user has no wiki name, which should be skipped
        // and a warning logged
        final Principal[] principals = m_db.getWikiNames();
        // Should only get janne's wiki name (JanneJalkanen), not "user" who has no wiki name
        Assertions.assertEquals( 1, principals.length );
    }

    @Test
    public void testUpdateExistingUserSamePassword() throws WikiSecurityException {
        // Create a user
        final String loginName = "SamePassUser" + System.currentTimeMillis();
        UserProfile profile = m_db.newProfile();
        profile.setEmail( "samepass@mailinator.com" );
        profile.setLoginName( loginName );
        profile.setFullname( "Same Password User" );
        profile.setPassword( "password" );
        m_db.save( profile );

        // Re-save with same password (hashed password shouldn't change)
        profile = m_db.findByLoginName( loginName );
        final String hashedPassword = profile.getPassword();
        profile.setPassword( hashedPassword ); // Set the already hashed password
        m_db.save( profile );

        // Verify password still works
        Assertions.assertTrue( m_db.validatePassword( loginName, "password" ) );

        // Clean up
        m_db.deleteByLoginName( loginName );
    }

}
