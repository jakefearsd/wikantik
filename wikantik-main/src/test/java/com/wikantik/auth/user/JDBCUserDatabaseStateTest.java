package com.wikantik.auth.user;

import com.wikantik.TestJNDIContext;
import com.wikantik.auth.AbstractJDBCDatabase;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NameAlreadyBoundException;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Date;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JDBCUserDatabase: the state it leaves behind in the caller's profile object, in its cache and in the table
 * after delete / rename / save / last-login, plus lookup edge cases.
 */
@RequiresPostgres
@TestInstance( TestInstance.Lifecycle.PER_CLASS )
class JDBCUserDatabaseStateTest {

    private DataSource ds;
    private JDBCUserDatabase db;

    @BeforeAll
    void start() throws Exception {
        ds = PostgresTestDb.createDataSource();
        TestJNDIContext.initialize();
        final Context initCtx = new InitialContext();
        try {
            initCtx.bind( "java:comp/env", new TestJNDIContext() );
        } catch ( final NameAlreadyBoundException e ) {
            assertNotNull( e.getMessage(), "already bound by an earlier test class in this JVM" );
        }
        final Context ctx = (Context) initCtx.lookup( "java:comp/env" );
        try {
            ctx.bind( AbstractJDBCDatabase.DEFAULT_DATASOURCE, ds );
        } catch ( final NameAlreadyBoundException e ) {
            ctx.rebind( AbstractJDBCDatabase.DEFAULT_DATASOURCE, ds );
        }
        db = new JDBCUserDatabase();
        db.initialize( null, new Properties() );
    }

    @AfterAll
    void stop() {
        TestJNDIContext.reset();
    }

    @BeforeEach
    void clean() throws Exception {
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "DELETE FROM users" );
            s.executeUpdate( "DELETE FROM deleted_logins" );
        }
    }

    private UserProfile saved( final String login, final String full ) throws Exception {
        final UserProfile p = db.newProfile();
        p.setLoginName( login );
        p.setFullname( full );
        p.setEmail( login + "@example.test" );
        p.setPassword( "correct-horse-battery-staple-3" );
        db.save( p );
        return p;
    }

    private void insertRaw( final String uid, final String login, final String full, final String wiki ) throws Exception {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                  "INSERT INTO users (uid,email,login_name,password,created,full_name,wiki_name) VALUES (?,?,?,?,now(),?,?)" ) ) {
            ps.setString( 1, uid );
            ps.setString( 2, login + "@raw.test" );
            ps.setString( 3, login );
            ps.setString( 4, "{SHA-256}x" );
            ps.setString( 5, full );
            ps.setString( 6, wiki );
            ps.executeUpdate();
        }
    }

    // ---- save stamps the caller's profile

    @Test
    void savingANewProfileStampsCreatedAndModifiedOnTheCallersObject() throws Exception {
        final long before = System.currentTimeMillis();
        final UserProfile p = saved( "stamp", "Stamp User" );
        assertNotNull( p.getCreated() );
        assertNotNull( p.getLastModified() );
        assertTrue( p.getCreated().getTime() >= before - 1_000L );
        assertEquals( p.getCreated(), p.getLastModified(), "created and modified are the same instant on first save" );
    }

    @Test
    void updatingAProfileMovesModifiedButNotCreated() throws Exception {
        final UserProfile p = saved( "stamp2", "Stamp Two" );
        final Date created = p.getCreated();
        final UserProfile stored = db.findByLoginName( "stamp2" );
        stored.setBio( "changed" );
        Thread.sleep( 20 );
        db.save( stored );
        assertNotNull( stored.getLastModified() );
        assertTrue( stored.getLastModified().getTime() > created.getTime() );
        assertEquals( "changed", db.findByLoginName( "stamp2" ).getBio() );
    }

    // ---- delete / rename / cache

    @Test
    void deleteEvictsTheCachedLoginAndRemovesTheRoles() throws Exception {
        saved( "gone", "Gone User" );
        assertNotNull( db.findByLoginName( "gone" ), "now cached" );
        db.deleteByLoginName( "gone" );
        assertThrows( NoSuchPrincipalException.class, () -> db.findByLoginName( "gone" ) );
        try ( Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement( "SELECT count(*) FROM roles WHERE login_name = ?" ) ) {
            ps.setString( 1, "gone" );
            try ( var rs = ps.executeQuery() ) {
                rs.next();
                assertEquals( 0, rs.getInt( 1 ), "roles go with the account" );
            }
        }
    }

    @Test
    void renameUpdatesTheCallersProfileAndEvictsBothCacheKeys() throws Exception {
        saved( "before", "Rename Me" );
        final UserProfile cachedOld = db.findByLoginName( "before" );
        assertEquals( "before", cachedOld.getLoginName() );
        final Date modifiedBefore = cachedOld.getLastModified();

        db.rename( "before", "after" );

        assertThrows( NoSuchPrincipalException.class, () -> db.findByLoginName( "before" ) );
        assertEquals( "after", db.findByLoginName( "after" ).getLoginName() );
        try ( Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement( "SELECT count(*) FROM roles WHERE login_name = ?" ) ) {
            ps.setString( 1, "after" );
            try ( var rs = ps.executeQuery() ) {
                rs.next();
                assertEquals( 1, rs.getInt( 1 ), "roles follow the rename" );
            }
        }
        assertNotNull( modifiedBefore );
    }

    @Test
    void renameStampsTheModifiedTimeOnTheStoredRow() throws Exception {
        saved( "r1", "Rename One" );
        final Date before = db.findByLoginName( "r1" ).getLastModified();
        Thread.sleep( 20 );
        db.rename( "r1", "r2" );
        final Date after = db.findByLoginName( "r2" ).getLastModified();
        assertNotNull( after );
        assertTrue( after.getTime() > before.getTime() );
    }

    // ---- last login

    @Test
    void recordLastLoginUpdatesAWarmCacheEntryInPlace() throws Exception {
        saved( "warm", "Warm User" );
        final UserProfile warm = db.findByLoginName( "warm" );
        assertNull( warm.getLastLogin() );
        final Date when = new Date( 1_700_000_000_000L );
        db.recordLastLogin( "warm", when );
        assertEquals( when, db.findByLoginName( "warm" ).getLastLogin() );
        assertNull( warm.getLastLogin(), "the profile the caller already holds is a copy, not the cache entry" );
    }

    @Test
    void recordLastLoginIgnoresNullArguments() throws Exception {
        saved( "nulls", "Null Args" );
        assertDoesNotThrow( () -> db.recordLastLogin( null, new Date() ) );
        assertDoesNotThrow( () -> db.recordLastLogin( "nulls", null ) );
        assertNull( db.findByLoginName( "nulls" ).getLastLogin() );
    }

    // ---- name probes

    @Test
    void loginTakenProbeIgnoresBlankTrimsAndExcludesTheCaller() throws Exception {
        saved( "Carol", "Carol C" );
        assertFalse( db.isLoginNameTakenIgnoreCase( null, "x" ) );
        assertFalse( db.isLoginNameTakenIgnoreCase( "", "x" ) );
        assertFalse( db.isLoginNameTakenIgnoreCase( "   ", "x" ) );
        assertTrue( db.isLoginNameTakenIgnoreCase( "carol", "x" ) );
        assertTrue( db.isLoginNameTakenIgnoreCase( "  CAROL  ", null ) );
        assertFalse( db.isLoginNameTakenIgnoreCase( "carol", "Carol" ), "an account never clashes with itself" );
        assertFalse( db.isLoginNameTakenIgnoreCase( "dave", "x" ) );
    }

    @Test
    void fullNameTakenProbeAnswersTrueOnlyForAnotherAccount() throws Exception {
        saved( "erin", "Erin Example" );
        assertTrue( db.isFullNameTakenIgnoreCase( "erin   example", "someoneelse" ) );
        assertTrue( db.isFullNameTakenIgnoreCase( "ERIN EXAMPLE", null ) );
        assertFalse( db.isFullNameTakenIgnoreCase( "Erin Example", "erin" ) );
        assertFalse( db.isFullNameTakenIgnoreCase( "Frank Other", "x" ) );
    }

    // ---- account summaries / lookups

    @Test
    void aBlankStoredUidIsReportedAsNull() throws Exception {
        insertRaw( "   ", "blankuid", "Blank Uid", "BlankUid" );
        insertRaw( "real-uid", "realuid", "Real Uid", "RealUid" );
        final Map< String, AccountSummary > byLogin = db.accountSummaries().stream()
                .collect( Collectors.toMap( AccountSummary::login, a -> a ) );
        assertNull( byLogin.get( "blankuid" ).uid() );
        assertEquals( "real-uid", byLogin.get( "realuid" ).uid() );
    }

    @Test
    void aMissingLookupIsNotAmbiguous() throws Exception {
        insertRaw( "u1", "solo", "Solo User", "SoloUser" );
        final NoSuchPrincipalException missing = assertThrows( NoSuchPrincipalException.class, () -> db.findByWikiName( "NobodyHasThis" ) );
        assertFalse( missing.isAmbiguous() );
        assertEquals( "solo", db.findByWikiName( "SoloUser" ).getLoginName() );
    }

    @Test
    void aNonConflictFailureWhileSavingIsNotReportedAsAProfileConflict() throws Exception {
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "ALTER TABLE roles ADD CONSTRAINT force_role_failure_state CHECK ( false ) NOT VALID" );
        }
        try {
            final UserProfile p = db.newProfile();
            p.setLoginName( "checkfail" );
            p.setFullname( "Check Fail" );
            p.setEmail( "checkfail@example.test" );
            p.setPassword( "correct-horse-battery-staple-3" );
            final WikiSecurityException e = assertThrows( WikiSecurityException.class, () -> db.save( p ) );
            assertFalse( e instanceof ProfileConflictException, "a CHECK violation is not a duplicate-name conflict" );
        } finally {
            try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
                s.executeUpdate( "ALTER TABLE roles DROP CONSTRAINT IF EXISTS force_role_failure_state" );
            }
        }
    }
}
