package com.wikantik.auth.authorize;

import com.wikantik.TestEngine;
import com.wikantik.TestJNDIContext;
import com.wikantik.auth.AbstractJDBCDatabase;
import com.wikantik.auth.WikiPrincipal;
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
import java.sql.Statement;
import java.util.Arrays;
import java.util.Date;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** JDBCGroupDatabase records who created and last changed a group, and when, and reads it back. */
@RequiresPostgres
@TestInstance( TestInstance.Lifecycle.PER_CLASS )
class JDBCGroupDatabaseAuditFieldsTest {

    private DataSource ds;
    private TestEngine engine;
    private JDBCGroupDatabase db;

    @BeforeAll
    void start() throws Exception {
        ds = PostgresTestDb.createDataSource();
        engine = new TestEngine( TestEngine.getTestProperties() );
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
        db = new JDBCGroupDatabase();
        db.initialize( engine, new Properties() );
    }

    @AfterAll
    void stop() throws Exception {
        TestJNDIContext.reset();
        engine.stop();
    }

    @BeforeEach
    void clean() throws Exception {
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "DELETE FROM group_members" );
            s.executeUpdate( "DELETE FROM groups" );
        }
    }

    private Group stored( final String name ) throws Exception {
        return Arrays.stream( db.groups() ).filter( g -> g.getName().equals( name ) ).findFirst().orElseThrow();
    }

    private Group groupOf( final String name, final String... members ) {
        final Group g = new Group( name, engine.getApplicationName() );
        for ( final String m : members ) {
            g.add( new WikiPrincipal( m ) );
        }
        return g;
    }

    @Test
    void aNewGroupIsStampedWithItsCreatorAndTimesOnTheCallersObjectAndInTheTable() throws Exception {
        final long before = System.currentTimeMillis();
        final Group g = groupOf( "Audited", "Alice" );
        db.save( g, new WikiPrincipal( "carol" ) );

        assertEquals( "carol", g.getCreator() );
        assertEquals( "carol", g.getModifier() );
        assertNotNull( g.getCreated() );
        assertNotNull( g.getLastModified() );
        assertTrue( g.getCreated().getTime() >= before - 1_000L );

        final Group read = stored( "Audited" );
        assertEquals( "carol", read.getCreator() );
        assertEquals( "carol", read.getModifier() );
        assertNotNull( read.getCreated() );
        assertNotNull( read.getLastModified() );
        assertTrue( read.isMember( new WikiPrincipal( "Alice" ) ) );
    }

    @Test
    void anEditKeepsTheCreatorAndMovesTheModifier() throws Exception {
        db.save( groupOf( "Edited", "Alice" ), new WikiPrincipal( "carol" ) );
        final Date created = stored( "Edited" ).getCreated();
        Thread.sleep( 20 );

        final Group edit = groupOf( "Edited", "Alice", "Bob" );
        db.save( edit, new WikiPrincipal( "dave" ) );

        final Group read = stored( "Edited" );
        assertEquals( "carol", read.getCreator(), "the creator never changes" );
        assertEquals( "dave", read.getModifier() );
        assertEquals( created, read.getCreated() );
        assertTrue( read.getLastModified().getTime() > created.getTime() );
        assertTrue( read.isMember( new WikiPrincipal( "Bob" ) ) );
        assertEquals( 2, read.members().length );
    }

    @Test
    void deletingAnUnknownGroupFailsAndDeletingAKnownOneRemovesIt() throws Exception {
        db.save( groupOf( "Doomed", "Alice" ), new WikiPrincipal( "carol" ) );
        final Group doomed = stored( "Doomed" );
        db.delete( doomed );
        assertEquals( 0, Arrays.stream( db.groups() ).filter( g -> g.getName().equals( "Doomed" ) ).count() );
        try ( Connection c = ds.getConnection(); var ps = c.prepareStatement( "SELECT count(*) FROM group_members WHERE name = 'Doomed'" );
              var rs = ps.executeQuery() ) {
            rs.next();
            assertEquals( 0, rs.getInt( 1 ), "members go with the group" );
        }
    }

    @Test
    void nullGroupOrModifierIsRefused() {
        assertThrows( IllegalArgumentException.class, () -> db.save( null, new WikiPrincipal( "carol" ) ) );
        assertThrows( IllegalArgumentException.class, () -> db.save( groupOf( "X" ), null ) );
    }
}
