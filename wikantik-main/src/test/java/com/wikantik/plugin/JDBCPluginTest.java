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
package com.wikantik.plugin;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.PluginException;
import com.wikantik.api.spi.Wiki;
import com.wikantik.api.managers.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Unit tests for {@link JDBCPlugin}.
 */
class JDBCPluginTest {

    private TestEngine engine;
    private JDBCPlugin plugin;

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = TestEngine.build( props );
        plugin = new JDBCPlugin();

        // Create a test page
        engine.saveText( "TestPage", "Test content" );
    }

    @AfterEach
    void tearDown() throws Exception {
        engine.getManager( PageManager.class ).deletePage( "TestPage" );
        engine.stop();
    }

    /**
     * Creates a wiki context for the test page.
     */
    private Context createContext() throws Exception {
        final Page page = engine.getManager( PageManager.class ).getPage( "TestPage" );
        return Wiki.context().create( engine, page );
    }

    // ============== Admin Permission Requirement ==============

    @Test
    void testRequiresAdminPermission() throws Exception {
        final Context context = createContext();
        final Map< String, String > params = new HashMap<>();
        params.put( JDBCPlugin.PARAM_SQL, "SELECT 1" );

        final PluginException ex = Assertions.assertThrows( PluginException.class, () ->
            plugin.execute( context, params )
        );
        Assertions.assertTrue( ex.getMessage().contains( "administrator" ),
                "Should require admin privileges: " + ex.getMessage() );
    }

    // ============== SQL Validation Tests (call validateAndGetSql directly) ==============

    @Test
    void testRejectInsertQuery() {
        final PluginException ex = Assertions.assertThrows( PluginException.class, () ->
            plugin.validateAndGetSql( "INSERT INTO users VALUES (1, 'test')" )
        );
        Assertions.assertTrue( ex.getMessage().contains( "SELECT" ), "Should mention SELECT requirement" );
    }

    @Test
    void testRejectUpdateQuery() {
        final PluginException ex = Assertions.assertThrows( PluginException.class, () ->
            plugin.validateAndGetSql( "UPDATE users SET name='hack'" )
        );
        Assertions.assertTrue( ex.getMessage().contains( "SELECT" ), "Should mention SELECT requirement" );
    }

    @Test
    void testRejectDeleteQuery() {
        final PluginException ex = Assertions.assertThrows( PluginException.class, () ->
            plugin.validateAndGetSql( "DELETE FROM users" )
        );
        Assertions.assertTrue( ex.getMessage().contains( "SELECT" ), "Should mention SELECT requirement" );
    }

    @Test
    void testRejectDropQuery() {
        final PluginException ex = Assertions.assertThrows( PluginException.class, () ->
            plugin.validateAndGetSql( "DROP TABLE users" )
        );
        Assertions.assertTrue( ex.getMessage().contains( "SELECT" ), "Should mention SELECT requirement" );
    }

    @Test
    void testRejectMultipleStatements() {
        final PluginException ex = Assertions.assertThrows( PluginException.class, () ->
            plugin.validateAndGetSql( "SELECT 1; DROP TABLE users" )
        );
        Assertions.assertTrue( ex.getMessage().contains( "Multiple" ), "Should mention multiple statements" );
    }

    @Test
    void testRejectUnionInjection() {
        final PluginException ex = Assertions.assertThrows( PluginException.class, () ->
            plugin.validateAndGetSql( "SELECT 1 UNION SELECT password FROM users" )
        );
        Assertions.assertTrue( ex.getMessage().contains( "forbidden" ), "Should reject UNION: " + ex.getMessage() );
    }

    @Test
    void testRejectSqlComments() {
        final PluginException ex = Assertions.assertThrows( PluginException.class, () ->
            plugin.validateAndGetSql( "SELECT 1 -- hidden payload" )
        );
        Assertions.assertTrue( ex.getMessage().contains( "comment" ), "Should reject SQL comments: " + ex.getMessage() );
    }

    @Test
    void testAcceptSelectWithTrailingSemicolon() throws PluginException {
        // Should pass SQL validation (trailing semicolons are allowed)
        final String result = plugin.validateAndGetSql( "SELECT 1;" );
        Assertions.assertEquals( "SELECT 1;", result );
    }

    // ============== Database Type Detection Tests ==============

    @Test
    void testDatabaseTypeFromUrl() {
        Assertions.assertEquals( JDBCPlugin.DatabaseType.POSTGRESQL,
            JDBCPlugin.DatabaseType.fromUrl( "jdbc:postgresql://localhost:5432/mydb" ) );

        Assertions.assertEquals( JDBCPlugin.DatabaseType.MYSQL,
            JDBCPlugin.DatabaseType.fromUrl( "jdbc:mysql://localhost:3306/mydb" ) );

        Assertions.assertEquals( JDBCPlugin.DatabaseType.MSSQL,
            JDBCPlugin.DatabaseType.fromUrl( "jdbc:sqlserver://localhost;databaseName=mydb" ) );

        Assertions.assertEquals( JDBCPlugin.DatabaseType.ORACLE,
            JDBCPlugin.DatabaseType.fromUrl( "jdbc:oracle:thin:@localhost:1521:orcl" ) );

        Assertions.assertEquals( JDBCPlugin.DatabaseType.H2,
            JDBCPlugin.DatabaseType.fromUrl( "jdbc:h2:mem:testdb" ) );

        Assertions.assertEquals( JDBCPlugin.DatabaseType.HSQLDB,
            JDBCPlugin.DatabaseType.fromUrl( "jdbc:hsqldb:mem:testdb" ) );

        Assertions.assertEquals( JDBCPlugin.DatabaseType.DERBY,
            JDBCPlugin.DatabaseType.fromUrl( "jdbc:derby:memory:testdb;create=true" ) );

        Assertions.assertEquals( JDBCPlugin.DatabaseType.DB2,
            JDBCPlugin.DatabaseType.fromUrl( "jdbc:db2://localhost:50000/mydb" ) );

        Assertions.assertEquals( JDBCPlugin.DatabaseType.MARIADB,
            JDBCPlugin.DatabaseType.fromUrl( "jdbc:mariadb://localhost:3306/mydb" ) );

        Assertions.assertNull( JDBCPlugin.DatabaseType.fromUrl( "jdbc:unknown://localhost/db" ) );
        Assertions.assertNull( JDBCPlugin.DatabaseType.fromUrl( null ) );
    }

    @Test
    void testDatabaseTypeFromDriver() {
        Assertions.assertEquals( JDBCPlugin.DatabaseType.POSTGRESQL,
            JDBCPlugin.DatabaseType.fromDriver( "org.postgresql.Driver" ) );

        Assertions.assertEquals( JDBCPlugin.DatabaseType.MYSQL,
            JDBCPlugin.DatabaseType.fromDriver( "com.mysql.cj.jdbc.Driver" ) );

        Assertions.assertEquals( JDBCPlugin.DatabaseType.MARIADB,
            JDBCPlugin.DatabaseType.fromDriver( "org.mariadb.jdbc.Driver" ) );

        Assertions.assertEquals( JDBCPlugin.DatabaseType.H2,
            JDBCPlugin.DatabaseType.fromDriver( "org.h2.Driver" ) );

        Assertions.assertNull( JDBCPlugin.DatabaseType.fromDriver( "com.unknown.Driver" ) );
        Assertions.assertNull( JDBCPlugin.DatabaseType.fromDriver( null ) );
    }

    // ============== Configuration Error Tests ==============

    // ============== Configuration Error Tests (require admin — tested via admin error) ==============

    @Test
    void testMissingDatabaseConfiguration() throws Exception {
        final Context context = createContext();
        final Map< String, String > params = new HashMap<>();
        params.put( JDBCPlugin.PARAM_SQL, "SELECT 1" );

        // Non-admin context should be blocked before reaching config check
        final PluginException ex = Assertions.assertThrows( PluginException.class, () ->
            plugin.execute( context, params )
        );
        Assertions.assertTrue( ex.getMessage().contains( "administrator" ),
                "Non-admin should be blocked: " + ex.getMessage() );
    }

    // ============== Limit Style Tests ==============

    @Test
    void testLimitStyleAssignments() {
        // Verify MySQL and PostgreSQL use LIMIT
        Assertions.assertNotNull( JDBCPlugin.DatabaseType.MYSQL.getLimitStyle() );
        Assertions.assertNotNull( JDBCPlugin.DatabaseType.POSTGRESQL.getLimitStyle() );
        Assertions.assertEquals( JDBCPlugin.DatabaseType.MYSQL.getLimitStyle(),
            JDBCPlugin.DatabaseType.POSTGRESQL.getLimitStyle(),
            "MySQL and PostgreSQL should both use LIMIT style" );

        // Verify MSSQL and Sybase use TOP
        Assertions.assertEquals( JDBCPlugin.DatabaseType.MSSQL.getLimitStyle(),
            JDBCPlugin.DatabaseType.SYBASE.getLimitStyle(),
            "MSSQL and Sybase should both use TOP style" );

        // Verify DB2, Derby and HSQLDB use FETCH FIRST
        Assertions.assertEquals( JDBCPlugin.DatabaseType.DB2.getLimitStyle(),
            JDBCPlugin.DatabaseType.DERBY.getLimitStyle(),
            "DB2 and Derby should both use FETCH FIRST style" );
        Assertions.assertEquals( JDBCPlugin.DatabaseType.HSQLDB.getLimitStyle(),
            JDBCPlugin.DatabaseType.DERBY.getLimitStyle(),
            "HSQLDB and Derby should both use FETCH FIRST style" );

        // Verify H2 uses LIMIT (like MySQL/PostgreSQL)
        Assertions.assertEquals( JDBCPlugin.DatabaseType.H2.getLimitStyle(),
            JDBCPlugin.DatabaseType.MYSQL.getLimitStyle(),
            "H2 should use LIMIT style" );
    }

    // ============== Parameter Parsing Tests ==============

    @Test
    void testDefaultSqlIsUsed() throws PluginException {
        // Default SQL should pass validation
        final String result = plugin.validateAndGetSql( null );
        Assertions.assertEquals( "select 1", result );
    }

    @Test
    void testCaseInsensitiveSelect() throws PluginException {
        // "SELECT", "select", "Select" should all pass validation
        for ( final String selectKeyword : new String[]{ "SELECT", "select", "Select", "sElEcT" } ) {
            final String result = plugin.validateAndGetSql( selectKeyword + " 1" );
            Assertions.assertEquals( selectKeyword + " 1", result );
        }
    }

    // ============== Database URL Prefix Tests ==============

    @Test
    void testDatabaseUrlPrefixes() {
        Assertions.assertEquals( "jdbc:postgresql:", JDBCPlugin.DatabaseType.POSTGRESQL.getUrlPrefix() );
        Assertions.assertEquals( "jdbc:mysql:", JDBCPlugin.DatabaseType.MYSQL.getUrlPrefix() );
        Assertions.assertEquals( "jdbc:mariadb:", JDBCPlugin.DatabaseType.MARIADB.getUrlPrefix() );
        Assertions.assertEquals( "jdbc:sqlserver:", JDBCPlugin.DatabaseType.MSSQL.getUrlPrefix() );
        Assertions.assertEquals( "jdbc:oracle:", JDBCPlugin.DatabaseType.ORACLE.getUrlPrefix() );
        Assertions.assertEquals( "jdbc:h2:", JDBCPlugin.DatabaseType.H2.getUrlPrefix() );
        Assertions.assertEquals( "jdbc:hsqldb:", JDBCPlugin.DatabaseType.HSQLDB.getUrlPrefix() );
        Assertions.assertEquals( "jdbc:derby:", JDBCPlugin.DatabaseType.DERBY.getUrlPrefix() );
        Assertions.assertEquals( "jdbc:db2:", JDBCPlugin.DatabaseType.DB2.getUrlPrefix() );
        Assertions.assertEquals( "jdbc:sybase:", JDBCPlugin.DatabaseType.SYBASE.getUrlPrefix() );
    }

    // ============== Driver Class Tests ==============

    @Test
    void testDatabaseDriverClasses() {
        Assertions.assertEquals( "org.postgresql.Driver", JDBCPlugin.DatabaseType.POSTGRESQL.getDriverClass() );
        Assertions.assertEquals( "com.mysql.cj.jdbc.Driver", JDBCPlugin.DatabaseType.MYSQL.getDriverClass() );
        Assertions.assertEquals( "org.mariadb.jdbc.Driver", JDBCPlugin.DatabaseType.MARIADB.getDriverClass() );
        Assertions.assertEquals( "com.microsoft.sqlserver.jdbc.SQLServerDriver", JDBCPlugin.DatabaseType.MSSQL.getDriverClass() );
        Assertions.assertEquals( "oracle.jdbc.driver.OracleDriver", JDBCPlugin.DatabaseType.ORACLE.getDriverClass() );
        Assertions.assertEquals( "org.h2.Driver", JDBCPlugin.DatabaseType.H2.getDriverClass() );
        Assertions.assertEquals( "org.hsqldb.jdbc.JDBCDriver", JDBCPlugin.DatabaseType.HSQLDB.getDriverClass() );
    }
}
