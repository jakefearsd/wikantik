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

import com.wikantik.TestJNDIContext;
import com.wikantik.WikiEngine;
import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.NoRequiredPropertyException;
import com.wikantik.auth.AbstractJDBCDatabase;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.jdbc.Jdbc;
import com.wikantik.jdbc.RowMapper;
import com.wikantik.jdbc.SqlBinder;
import org.junit.jupiter.api.Test;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NameAlreadyBoundException;
import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-unit (no Postgres/Docker required) tests for {@link JDBCGroupDatabase} branches that
 * are either pure argument validation, JNDI-lookup/connectivity failures reproducible with a
 * mock {@link DataSource}, or private-method logic exercised directly via reflection with a
 * mocked {@link Jdbc} collaborator — avoiding the need to force real SQL-level failures (some
 * of which, e.g. a duplicate primary-key row, are not reproducible against the real schema at
 * all). DB-dependent transactional rollback behavior stays in {@link JDBCGroupDatabaseTest}
 * (which requires Postgres).
 */
class JDBCGroupDatabaseUnitTest {

    // --- save(): null-argument guard ---

    @Test
    void save_nullGroup_throwsIllegalArgumentException() {
        final JDBCGroupDatabase db = new JDBCGroupDatabase();
        assertThrows( IllegalArgumentException.class,
                () -> db.save( null, new WikiPrincipal( "someone" ) ) );
    }

    @Test
    void save_nullModifier_throwsIllegalArgumentException() {
        final JDBCGroupDatabase db = new JDBCGroupDatabase();
        final Group group = new Group( "SomeGroup", "TestWiki" );
        assertThrows( IllegalArgumentException.class, () -> db.save( group, null ) );
    }

    // --- initialize(): JNDI lookup failure ---

    @Test
    void initialize_whenDatasourceNameNotBound_throwsNoRequiredPropertyException() throws Exception {
        final Context ctx = bindEnv();
        final String unboundName = "jdbc/DefinitelyNotBound" + System.nanoTime();
        // Sanity: nothing is bound under this name.
        assertThrows( javax.naming.NamingException.class, () -> ctx.lookup( unboundName ) );

        final WikiEngine engine = mock( WikiEngine.class );
        final Properties props = new Properties();
        props.setProperty( AbstractJDBCDatabase.PROP_DATASOURCE, unboundName );

        final JDBCGroupDatabase db = new JDBCGroupDatabase();
        assertThrows( NoRequiredPropertyException.class, () -> db.initialize( engine, props ) );
    }

    // --- initialize(): datasource bound, but the connectivity probe fails ---

    @Test
    void initialize_whenPingFails_throwsWikiSecurityException() throws Exception {
        final Context ctx = bindEnv();
        final DataSource broken = mock( DataSource.class );
        when( broken.getConnection() ).thenThrow( new SQLException( "connection refused" ) );
        final String name = "jdbc/BrokenGroupDatabase" + System.nanoTime();
        ctx.bind( name, broken );

        final WikiEngine engine = mock( WikiEngine.class );
        final Properties props = new Properties();
        props.setProperty( AbstractJDBCDatabase.PROP_DATASOURCE, name );

        final JDBCGroupDatabase db = new JDBCGroupDatabase();
        final WikiSecurityException ex = assertThrows( WikiSecurityException.class,
                () -> db.initialize( engine, props ) );
        assertTrue( ex.getMessage().contains( "connectivity" ), "unexpected message: " + ex.getMessage() );
    }

    // --- groups(): a SQL failure while listing must surface as WikiSecurityException ---

    @Test
    @SuppressWarnings( "unchecked" )
    void groups_whenQueryThrowsSQLException_wrapsAsWikiSecurityException() throws Exception {
        final JDBCGroupDatabase db = new JDBCGroupDatabase();
        final Jdbc jdbc = mock( Jdbc.class );
        when( jdbc.query( anyString(), any( SqlBinder.class ), any( RowMapper.class ) ) )
                .thenThrow( new SQLException( "listing failed" ) );
        setField( db, "jdbc", jdbc );
        setField( db, "engine", mock( Engine.class ) );

        assertThrows( WikiSecurityException.class, db::groups );
    }

    // --- findGroup() (private, reached via exists()/save()/delete()): SQL failure ---

    @Test
    @SuppressWarnings( "unchecked" )
    void findGroup_whenQueryThrowsSQLException_throwsNoSuchPrincipalException() throws Exception {
        final JDBCGroupDatabase db = new JDBCGroupDatabase();
        final Jdbc jdbc = mock( Jdbc.class );
        when( jdbc.query( anyString(), any( SqlBinder.class ), any( RowMapper.class ) ) )
                .thenThrow( new SQLException( "lookup failed" ) );
        setField( db, "jdbc", jdbc );
        setField( db, "engine", mock( Engine.class ) );

        final Exception ex = assertThrows( InvocationTargetException.class,
                () -> invokeFindGroup( db, "AnyGroup" ) );
        assertTrue( ex.getCause() instanceof NoSuchPrincipalException,
                "expected NoSuchPrincipalException, got " + ex.getCause() );
    }

    // --- findGroup(): more than one row for the same name is reported, not silently picked ---

    @Test
    @SuppressWarnings( "unchecked" )
    void findGroup_whenMultipleRowsMatch_throwsNoSuchPrincipalException() throws Exception {
        final JDBCGroupDatabase db = new JDBCGroupDatabase();
        final Jdbc jdbc = mock( Jdbc.class );
        final Group first = new Group( "Dup", "TestWiki" );
        final Group second = new Group( "Dup", "TestWiki" );
        when( jdbc.query( anyString(), any( SqlBinder.class ), any( RowMapper.class ) ) )
                .thenReturn( List.of( first, second ) );
        setField( db, "jdbc", jdbc );
        setField( db, "engine", mock( Engine.class ) );

        final InvocationTargetException ite = assertThrows( InvocationTargetException.class,
                () -> invokeFindGroup( db, "Dup" ) );
        assertTrue( ite.getCause() instanceof NoSuchPrincipalException );
        assertTrue( ite.getCause().getMessage().contains( "More than one" ), ite.getCause().getMessage() );
    }

    // --- mapGroupRow(): a row with a null name is skipped (logged), not surfaced as a broken Group ---

    @Test
    void mapGroupRow_whenNameIsNull_returnsNull() throws Exception {
        final JDBCGroupDatabase db = new JDBCGroupDatabase();
        final ResultSet rs = mock( ResultSet.class );
        when( rs.getString( "name" ) ).thenReturn( null );

        final Method m = JDBCGroupDatabase.class.getDeclaredMethod( "mapGroupRow", ResultSet.class );
        m.setAccessible( true );
        final Object result = m.invoke( db, rs );
        assertNull( result, "a row with a null group name must be skipped, not turned into a broken Group" );
    }

    // --- populateGroup(): a SQL failure while loading members must fail closed (empty group), not throw ---

    @Test
    @SuppressWarnings( "unchecked" )
    void populateGroup_whenQueryThrowsSQLException_leavesGroupWithNoMembers() throws Exception {
        final JDBCGroupDatabase db = new JDBCGroupDatabase();
        final Jdbc jdbc = mock( Jdbc.class );
        when( jdbc.query( anyString(), any( SqlBinder.class ), any( RowMapper.class ) ) )
                .thenThrow( new SQLException( "members lookup failed" ) );
        setField( db, "jdbc", jdbc );

        final Group group = new Group( "G", "TestWiki" );
        final Method m = JDBCGroupDatabase.class.getDeclaredMethod( "populateGroup", Group.class );
        m.setAccessible( true );
        final Object result = assertDoesNotThrow( () -> m.invoke( db, group ) );
        assertEquals( 0, ( ( Group ) result ).members().length,
                "a member-lookup failure must fail closed to an empty membership list, not throw" );
    }

    @org.junit.jupiter.api.AfterAll
    static void resetJndi() {
        TestJNDIContext.reset();
    }

    // --- test helpers -------------------------------------------------------

    private static Context bindEnv() throws Exception {
        TestJNDIContext.initialize();
        final Context initCtx = new InitialContext();
        try {
            initCtx.bind( "java:comp/env", new TestJNDIContext() );
        } catch( final NameAlreadyBoundException e ) {
            // Another test class in this fork already bound it — fine, reuse it.
        }
        return ( Context ) initCtx.lookup( "java:comp/env" );
    }

    private static void setField( final Object target, final String fieldName, final Object value ) throws Exception {
        final Field f = JDBCGroupDatabase.class.getDeclaredField( fieldName );
        f.setAccessible( true );
        f.set( target, value );
    }

    private static Object invokeFindGroup( final JDBCGroupDatabase db, final String name ) throws Exception {
        final Method m = JDBCGroupDatabase.class.getDeclaredMethod( "findGroup", String.class );
        m.setAccessible( true );
        return m.invoke( db, name );
    }
}
