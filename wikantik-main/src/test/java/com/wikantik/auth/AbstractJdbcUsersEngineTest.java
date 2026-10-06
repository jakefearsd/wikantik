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

import com.wikantik.TestEngine;
import com.wikantik.TestJNDIContext;
import com.wikantik.WikiSession;
import com.wikantik.WikiSessionTest;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.managers.PageManager;
import com.wikantik.auth.acl.UnresolvedPrincipal;
import com.wikantik.auth.authorize.Group;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.auth.permissions.PermissionFactory;
import com.wikantik.auth.user.JDBCUserDatabase;
import com.wikantik.auth.user.UserDatabase;
import com.wikantik.auth.user.UserProfile;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NameAlreadyBoundException;
import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.security.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixture for tests that need a real {@link JDBCUserDatabase} on PostgreSQL (where
 * {@code users.wiki_name} is UNIQUE and a shared full name is genuinely ambiguous) behind an
 * otherwise ordinary {@link TestEngine}. Test accounts use the {@code sdn-} login prefix and are
 * deleted before and after each test.
 */
@RequiresPostgres
@TestInstance( TestInstance.Lifecycle.PER_CLASS )
abstract class AbstractJdbcUsersEngineTest {

    protected static final String PASSWORD = "correct-horse-battery-staple-5";
    protected static final String PREFIX = "sdn-";

    protected DataSource ds;
    protected TestEngine engine;
    protected UserDatabase db;
    protected AuthorizationManager authz;

    @BeforeAll
    void startDatabase() throws Exception {
        ds = PostgresTestDb.createDataSource();
        TestJNDIContext.initialize();
        final Context initCtx = new InitialContext();
        try {
            initCtx.bind( "java:comp/env", new TestJNDIContext() );
        } catch ( final NameAlreadyBoundException e ) {
            // Bound by an earlier class in this JVM; the datasource is (re)bound below.
            org.apache.logging.log4j.LogManager.getLogger( AbstractJdbcUsersEngineTest.class )
                    .debug( "java:comp/env already bound: {}", e.getMessage() );
        }
        final Context ctx = ( Context ) initCtx.lookup( "java:comp/env" );
        try {
            ctx.bind( com.wikantik.auth.AbstractJDBCDatabase.DEFAULT_DATASOURCE, ds );
        } catch ( final NameAlreadyBoundException e ) {
            ctx.rebind( com.wikantik.auth.AbstractJDBCDatabase.DEFAULT_DATASOURCE, ds );
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        deleteTestUsers();
        engine = new TestEngine( TestEngine.getTestProperties() );
        final JDBCUserDatabase jdbc = new JDBCUserDatabase();
        jdbc.initialize( engine, new Properties() );
        final UserManager um = engine.getManager( UserManager.class );
        final Field f = DefaultUserManager.class.getDeclaredField( "database" );
        f.setAccessible( true );
        f.set( um, jdbc );
        db = jdbc;
        authz = engine.getManager( AuthorizationManager.class );
    }

    @AfterEach
    void tearDown() throws Exception {
        engine.stop();
        deleteTestUsers();
    }

    @AfterAll
    void cleanUp() throws Exception {
        deleteTestUsers();
        // Unbind the datasource: a later engine test in this JVM would otherwise find it and wire
        // database-backed subsystems (audit, policy) it does not expect.
        TestJNDIContext.reset();
    }

    private void deleteTestUsers() throws Exception {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement( "DELETE FROM users WHERE login_name LIKE ?" ) ) {
            ps.setString( 1, PREFIX + "%" );
            ps.executeUpdate();
        }
    }

    /** Saves an account the way the write paths do: full name, then a free wiki name. */
    protected void saveUser( final String login, final String fullName ) throws Exception {
        final UserProfile p = db.newProfile();
        p.setLoginName( login );
        ProfileNameRules.applyFullName( engine, db, p, fullName );
        p.setEmail( login + "@example.test" );
        p.setPassword( PASSWORD );
        db.save( p );
    }

    protected void renameFullName( final String login, final String fullName ) throws Exception {
        final UserProfile p = db.findByLoginName( login );
        ProfileNameRules.applyFullName( engine, db, p, fullName );
        db.save( p );
    }

    protected Session login( final String login ) throws Exception {
        return WikiSessionTest.authenticatedSession( engine, login, PASSWORD );
    }

    protected boolean canEdit( final Session s, final String pageName ) throws Exception {
        final Page page = engine.getManager( PageManager.class ).getPage( pageName );
        return authz.checkPermission( s, PermissionFactory.getPagePermission( page, "edit" ) );
    }
}
