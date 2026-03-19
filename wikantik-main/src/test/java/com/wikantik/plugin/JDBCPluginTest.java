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
import com.wikantik.pages.PageManager;
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

    // ============== SQL Validation Tests ==============

    @Test
    void testRejectInsertQuery() throws Exception {
        final Context context = createContext();
        final Map< String, String > params = new HashMap<>();
        params.put( JDBCPlugin.PARAM_SQL, "INSERT INTO users VALUES (1, 'test')" );

        final PluginException ex = Assertions.assertThrows( PluginException.class, () ->
            plugin.execute( context, params )
        );
        Assertions.assertTrue( ex.getMessage().contains( "SELECT" ), "Should mention SELECT requirement" );
    }

    @Test
    void testRejectUpdateQuery() throws Exception {
        final Context context = createContext();
        final Map< String, String > params = new HashMap<>();
        params.put( JDBCPlugin.PARAM_SQL, "UPDATE users SET name='hack'" );

        final PluginException ex = Assertions.assertThrows( PluginException.class, () ->
            plugin.execute( context, params )
        );
        Assertions.assertTrue( ex.getMessage().contains( "SELECT" ), "Should mention SELECT requirement" );
    }

    @Test
    void testRejectDeleteQuery() throws Exception {
        final Context context = createContext();
        final Map< String, String > params = new HashMap<>();
        params.put( JDBCPlugin.PARAM_SQL, "DELETE FROM users" );

        final PluginException ex = Assertions.assertThrows( PluginException.class, () ->
            plugin.execute( context, params )
        );
        Assertions.assertTrue( ex.getMessage().contains( "SELECT" ), "Should mention SELECT requirement" );
    }

    @Test
    void testRejectDropQuery() throws Exception {
        final Context context = createContext();
        final Map< String, String > params = new HashMap<>();
        params.put( JDBCPlugin.PARAM_SQL, "DROP TABLE users" );

        final PluginException ex = Assertions.assertThrows( PluginException.class, () ->
            plugin.execute( context, params )
        );
        Assertions.assertTrue( ex.getMessage().contains( "SELECT" ), "Should mention SELECT requirement" );
    }

    @Test
    void testRejectMultipleStatements() throws Exception {
        final Context context = createContext();
        final Map< String, String > params = new HashMap<>();
        params.put( JDBCPlugin.PARAM_SQL, "SELECT 1; DROP TABLE users" );

        final PluginException ex = Assertions.assertThrows( PluginException.class, () ->
            plugin.execute( context, params )
        );
        Assertions.assertTrue( ex.getMessage().contains( "Multiple" ), "Should mention multiple statements" );
    }

    @Test
    void testAcceptSelectWithTrailingSemicolon() throws Exception {
        final Context context = createContext();
        final Map< String, String > params = new HashMap<>();
        params.put( JDBCPlugin.PARAM_SQL, "SELECT 1;" );

        // Should fail due to missing database config, not SQL validation
        final PluginException ex = Assertions.assertThrows( PluginException.class, () ->
            plugin.execute( context, params )
        );
        Assertions.assertTrue( ex.getMessage().contains( "database configuration" ) ||
                               ex.getMessage().contains( "jdbc.url" ),
                "Should fail due to missing config, not SQL validation: " + ex.getMessage() );
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

    @Test
    void testMissingDatabaseConfiguration() throws Exception {
        final Context context = createContext();
        final Map< String, String > params = new HashMap<>();
        params.put( JDBCPlugin.PARAM_SQL, "SELECT 1" );

        // Without any database configuration, plugin should fail with helpful message
        final PluginException ex = Assertions.assertThrows( PluginException.class, () ->
            plugin.execute( context, params )
        );
        Assertions.assertTrue( ex.getMessage().contains( "database configuration" ) ||
                               ex.getMessage().contains( "jdbc.url" ),
                "Should provide helpful error about missing configuration: " + ex.getMessage() );
    }

    @Test
    void testMissingNamedSourceConfiguration() throws Exception {
        final Context context = createContext();
        final Map< String, String > params = new HashMap<>();
        params.put( JDBCPlugin.PARAM_SOURCE, "nonexistent" );
        params.put( JDBCPlugin.PARAM_SQL, "SELECT 1" );

        // Request a named source that doesn't exist
        final PluginException ex = Assertions.assertThrows( PluginException.class, () ->
            plugin.execute( context, params )
        );
        Assertions.assertTrue( ex.getMessage().contains( "database configuration" ) ||
                               ex.getMessage().contains( "jdbc.url" ),
                "Should fail with missing config error: " + ex.getMessage() );
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
    void testDefaultSqlIsUsed() throws Exception {
        final Context context = createContext();
        final Map< String, String > params = new HashMap<>();
        // Don't provide SQL parameter - should use default "select 1"

        // Should fail due to no DB, not due to missing SQL parameter
        final PluginException ex = Assertions.assertThrows( PluginException.class, () ->
            plugin.execute( context, params )
        );
        // Error should be about missing database config, not about SQL
        Assertions.assertTrue( ex.getMessage().contains( "database" ) || ex.getMessage().contains( "jdbc" ),
            "Default SQL should be used, error should be about database config: " + ex.getMessage() );
    }

    @Test
    void testCaseInsensitiveSelect() throws Exception {
        final Context context = createContext();

        // "SELECT", "select", "Select" should all be valid
        for ( final String selectKeyword : new String[]{ "SELECT", "select", "Select", "sElEcT" } ) {
            final Map< String, String > params = new HashMap<>();
            params.put( JDBCPlugin.PARAM_SQL, selectKeyword + " 1" );

            final PluginException ex = Assertions.assertThrows( PluginException.class, () ->
                plugin.execute( context, params )
            );
            // Should fail due to missing config, not SELECT validation
            Assertions.assertFalse( ex.getMessage().toLowerCase().contains( "must start with" ),
                selectKeyword + " should be accepted as valid SELECT: " + ex.getMessage() );
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
