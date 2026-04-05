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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-style tests for {@link JDBCPlugin} that exercise the JDBC execution path
 * using an HSQLDB in-memory database.  These tests cover the ConnectionConfig inner class,
 * SQL result formatting, error-handling paths, and the addResultLimit variants that are
 * not exercised by the pure-unit JDBCPluginTest.
 *
 * <p>HSQLDB is available as a test-scope dependency in wikantik-main.
 * HSQLDB uses the FETCH FIRST limit style, which exercises that code path.</p>
 *
 * <p>A static anchor connection keeps the HSQLDB in-memory database alive for the
 * duration of the test class; HSQLDB drops the in-memory DB when its last connection
 * is closed.</p>
 */
class JDBCPluginCITest {

    // HSQLDB in-memory — kept alive via a static anchor connection.
    // The URL without 'shutdown=true' lets HSQLDB keep the DB until the last connection closes.
    private static final String HSQL_URL  = "jdbc:hsqldb:mem:jdbcplugintest";
    private static final String HSQL_USER = "SA";
    private static final String HSQL_PASS = "";

    /** Keeps the in-memory database alive for all tests in this class. */
    private static Connection anchorConnection;

    private TestEngine engine;
    private JDBCPlugin plugin;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        Class.forName( "org.hsqldb.jdbc.JDBCDriver" );
        // Open and hold an anchor connection so the DB doesn't evaporate between tests
        anchorConnection = DriverManager.getConnection( HSQL_URL, HSQL_USER, HSQL_PASS );

        try ( final Statement stmt = anchorConnection.createStatement() ) {
            stmt.execute( "CREATE TABLE IF NOT EXISTS employees (" +
                          "  id INT PRIMARY KEY," +
                          "  name VARCHAR(50)," +
                          "  dept VARCHAR(50)" +
                          ")" );
            stmt.execute( "DELETE FROM employees" );
            stmt.execute( "INSERT INTO employees VALUES (1, 'Alice', 'Engineering')" );
            stmt.execute( "INSERT INTO employees VALUES (2, 'Bob', 'Marketing')" );
            stmt.execute( "INSERT INTO employees VALUES (3, 'Carol', NULL)" );
        }
    }

    @AfterAll
    static void tearDownDatabase() throws Exception {
        if ( anchorConnection != null && !anchorConnection.isClosed() ) {
            try ( final Statement stmt = anchorConnection.createStatement() ) {
                stmt.execute( "DROP TABLE IF EXISTS employees" );
            }
            anchorConnection.close();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = TestEngine.build( props );
        plugin = new JDBCPlugin();
        engine.saveText( "TestPage", "Test content" );
    }

    @AfterEach
    void tearDown() throws Exception {
        engine.getManager( PageManager.class ).deletePage( "TestPage" );
        engine.stop();
    }

    private Context createContext() throws Exception {
        final Page page = engine.getManager( PageManager.class ).getPage( "TestPage" );
        return Wiki.context().create( engine, page );
    }

    // ============== ConnectionConfig via property-based JDBC path ==============

    /**
     * Happy path: executes a real SELECT against HSQLDB and verifies the table HTML is returned.
     */
    @Test
    void testExecuteSelectReturnsHtmlTable() throws Exception {
        final Properties engineProps = engine.getWikiProperties();
        engineProps.setProperty( "jdbc.url",      HSQL_URL );
        engineProps.setProperty( "jdbc.user",     HSQL_USER );
        engineProps.setProperty( "jdbc.password", HSQL_PASS );

        final Context ctx = createContext();
        final Map<String, String> params = new HashMap<>();
        params.put( JDBCPlugin.PARAM_SQL, "SELECT id, name, dept FROM employees ORDER BY id" );

        final String result = plugin.execute( ctx, params );

        assertNotNull( result );
        assertTrue( result.contains( "<div" ),   "Should wrap in a div" );
        assertTrue( result.contains( "Alice" ),  "Should contain row data" );
        assertTrue( result.contains( "Bob" ),    "Should contain row data" );
        assertTrue( result.contains( "<table" ), "Should produce a table" );
        assertTrue( result.contains( "<th" ),    "Should have header cells by default" );
    }

    /**
     * When {@code header=false} the table must have no {@code <th>} elements.
     */
    @Test
    void testHeaderFalseOmitsHeaderRow() throws Exception {
        final Properties engineProps = engine.getWikiProperties();
        engineProps.setProperty( "jdbc.url",      HSQL_URL );
        engineProps.setProperty( "jdbc.user",     HSQL_USER );
        engineProps.setProperty( "jdbc.password", HSQL_PASS );

        final Context ctx = createContext();
        final Map<String, String> params = new HashMap<>();
        params.put( JDBCPlugin.PARAM_SQL, "SELECT id FROM employees" );
        params.put( JDBCPlugin.PARAM_HEADER, "false" );

        final String result = plugin.execute( ctx, params );

        assertFalse( result.contains( "<th" ), "Should have no header cells when header=false" );
        assertTrue( result.contains( "<td" ),  "Should still have data cells" );
    }

    /**
     * When the query returns no rows the plugin must produce a "No results" cell.
     */
    @Test
    void testEmptyResultSetShowsNoResults() throws Exception {
        final Properties engineProps = engine.getWikiProperties();
        engineProps.setProperty( "jdbc.url",      HSQL_URL );
        engineProps.setProperty( "jdbc.user",     HSQL_USER );
        engineProps.setProperty( "jdbc.password", HSQL_PASS );

        final Context ctx = createContext();
        final Map<String, String> params = new HashMap<>();
        params.put( JDBCPlugin.PARAM_SQL, "SELECT id FROM employees WHERE id = 9999" );

        final String result = plugin.execute( ctx, params );

        assertTrue( result.contains( "No results" ), "Empty result set must show 'No results'" );
    }

    /**
     * NULL column values must be rendered as empty strings, not throw an NPE.
     */
    @Test
    void testNullColumnValueRenderedAsEmpty() throws Exception {
        final Properties engineProps = engine.getWikiProperties();
        engineProps.setProperty( "jdbc.url",      HSQL_URL );
        engineProps.setProperty( "jdbc.user",     HSQL_USER );
        engineProps.setProperty( "jdbc.password", HSQL_PASS );

        final Context ctx = createContext();
        final Map<String, String> params = new HashMap<>();
        params.put( JDBCPlugin.PARAM_SQL, "SELECT name, dept FROM employees WHERE id = 3" );

        final String result = plugin.execute( ctx, params );

        // Carol row with NULL dept must not contain "null" literally and must not throw
        assertFalse( result.toLowerCase().contains( ">null<" ),
                     "NULL values must be rendered as empty, not 'null'" );
        assertTrue( result.contains( "Carol" ) );
    }

    /**
     * A custom CSS class passed via the {@code class} parameter must appear in the wrapper div.
     */
    @Test
    void testCustomCssClass() throws Exception {
        final Properties engineProps = engine.getWikiProperties();
        engineProps.setProperty( "jdbc.url",      HSQL_URL );
        engineProps.setProperty( "jdbc.user",     HSQL_USER );
        engineProps.setProperty( "jdbc.password", HSQL_PASS );

        final Context ctx = createContext();
        final Map<String, String> params = new HashMap<>();
        params.put( JDBCPlugin.PARAM_SQL, "SELECT id FROM employees FETCH FIRST 1 ROWS ONLY" );
        params.put( JDBCPlugin.PARAM_CLASS, "my-results" );

        final String result = plugin.execute( ctx, params );

        assertTrue( result.contains( "my-results" ), "Custom CSS class must appear in output" );
    }

    /**
     * Default CSS class ("jdbc-results") is used when no {@code class} parameter is supplied.
     */
    @Test
    void testDefaultCssClass() throws Exception {
        final Properties engineProps = engine.getWikiProperties();
        engineProps.setProperty( "jdbc.url",      HSQL_URL );
        engineProps.setProperty( "jdbc.user",     HSQL_USER );
        engineProps.setProperty( "jdbc.password", HSQL_PASS );

        final Context ctx = createContext();
        final Map<String, String> params = new HashMap<>();
        params.put( JDBCPlugin.PARAM_SQL, "SELECT id FROM employees FETCH FIRST 1 ROWS ONLY" );

        final String result = plugin.execute( ctx, params );

        assertTrue( result.contains( "jdbc-results" ), "Default CSS class must be 'jdbc-results'" );
    }

    // ============== Named-source property path ==============

    /**
     * Using the {@code src} parameter reads {@code jdbc.url.<src>} from properties.
     */
    @Test
    void testNamedSourceReadsPropertyWithSuffix() throws Exception {
        final Properties engineProps = engine.getWikiProperties();
        engineProps.setProperty( "jdbc.url.mydb",      HSQL_URL );
        engineProps.setProperty( "jdbc.user.mydb",     HSQL_USER );
        engineProps.setProperty( "jdbc.password.mydb", HSQL_PASS );

        final Context ctx = createContext();
        final Map<String, String> params = new HashMap<>();
        params.put( JDBCPlugin.PARAM_SOURCE, "mydb" );
        params.put( JDBCPlugin.PARAM_SQL, "SELECT id FROM employees FETCH FIRST 1 ROWS ONLY" );

        final String result = plugin.execute( ctx, params );

        assertNotNull( result );
        assertTrue( result.contains( "<table" ), "Must return table HTML" );
    }

    // ============== Error handling paths ==============

    /**
     * An invalid JDBC driver class must throw {@link PluginException} with a helpful message.
     */
    @Test
    void testUnknownDriverClassThrowsPluginException() throws Exception {
        final Properties engineProps = engine.getWikiProperties();
        engineProps.setProperty( "jdbc.driver",   "com.nonexistent.Driver" );
        engineProps.setProperty( "jdbc.url",      HSQL_URL );
        engineProps.setProperty( "jdbc.user",     HSQL_USER );
        engineProps.setProperty( "jdbc.password", HSQL_PASS );

        final Context ctx = createContext();
        final Map<String, String> params = new HashMap<>();
        params.put( JDBCPlugin.PARAM_SQL, "SELECT id FROM employees FETCH FIRST 1 ROWS ONLY" );

        final PluginException ex = assertThrows( PluginException.class,
                () -> plugin.execute( ctx, params ) );
        assertTrue( ex.getMessage().contains( "driver" ) || ex.getMessage().contains( "Driver" ),
                    "Exception should mention the driver: " + ex.getMessage() );
    }

    /**
     * A query against a non-existent table must be wrapped in a PluginException.
     */
    @Test
    void testQueryAgainstMissingTableThrowsPluginException() throws Exception {
        final Properties engineProps = engine.getWikiProperties();
        engineProps.setProperty( "jdbc.url",      HSQL_URL );
        engineProps.setProperty( "jdbc.user",     HSQL_USER );
        engineProps.setProperty( "jdbc.password", HSQL_PASS );

        final Context ctx = createContext();
        final Map<String, String> params = new HashMap<>();
        params.put( JDBCPlugin.PARAM_SQL, "SELECT * FROM nosuchTable_xyz" );

        final PluginException ex = assertThrows( PluginException.class,
                () -> plugin.execute( ctx, params ) );
        assertTrue( ex.getMessage().toLowerCase().contains( "database" ),
                    "Exception should mention database: " + ex.getMessage() );
    }

    // ============== addResultLimit coverage (HSQLDB = FETCH FIRST style) ==============

    /**
     * HSQLDB uses FETCH FIRST n ROWS ONLY.  When the query already has a FETCH clause
     * the plugin must not append another one.
     */
    @Test
    void testFetchFirstNotDoubled() throws Exception {
        final Properties engineProps = engine.getWikiProperties();
        engineProps.setProperty( "jdbc.url",      HSQL_URL );
        engineProps.setProperty( "jdbc.user",     HSQL_USER );
        engineProps.setProperty( "jdbc.password", HSQL_PASS );

        final Context ctx = createContext();
        final Map<String, String> params = new HashMap<>();
        params.put( JDBCPlugin.PARAM_SQL,
                    "SELECT id FROM employees ORDER BY id FETCH FIRST 1 ROWS ONLY" );

        final String result = plugin.execute( ctx, params );
        assertNotNull( result );
        assertTrue( result.contains( "<table" ) );
    }

    /**
     * Verifies a trailing semicolon is stripped before the FETCH FIRST clause is appended.
     */
    @Test
    void testTrailingSemicolonStrippedBeforeFetchAppended() throws Exception {
        final Properties engineProps = engine.getWikiProperties();
        engineProps.setProperty( "jdbc.url",        HSQL_URL );
        engineProps.setProperty( "jdbc.user",       HSQL_USER );
        engineProps.setProperty( "jdbc.password",   HSQL_PASS );
        engineProps.setProperty( "jdbc.maxresults", "5" );

        final Context ctx = createContext();
        final Map<String, String> params = new HashMap<>();
        params.put( JDBCPlugin.PARAM_SQL, "SELECT id FROM employees ORDER BY id;" );

        final String result = plugin.execute( ctx, params );
        assertNotNull( result );
        assertTrue( result.contains( "<table" ) );
    }

    // ============== maxresults == 0 disables limiting ==============

    /**
     * When {@code jdbc.maxresults} is 0 no FETCH FIRST clause must be appended.
     */
    @Test
    void testMaxResultsZeroDisablesLimit() throws Exception {
        final Properties engineProps = engine.getWikiProperties();
        engineProps.setProperty( "jdbc.url",        HSQL_URL );
        engineProps.setProperty( "jdbc.user",       HSQL_USER );
        engineProps.setProperty( "jdbc.password",   HSQL_PASS );
        engineProps.setProperty( "jdbc.maxresults", "0" );

        final Context ctx = createContext();
        final Map<String, String> params = new HashMap<>();
        params.put( JDBCPlugin.PARAM_SQL, "SELECT id FROM employees ORDER BY id" );

        final String result = plugin.execute( ctx, params );

        // All three rows should be present
        assertTrue( result.contains( "1" ) );
        assertTrue( result.contains( "2" ) );
        assertTrue( result.contains( "3" ) );
    }

    // ============== ConnectionConfig — JNDI path (no DataSource configured) ==============

    /**
     * When no JNDI DataSource is bound and no jdbc.url property is set for a named source
     * the error message must mention the missing configuration including the source suffix.
     */
    @Test
    void testMissingNamedSourceConfigMentionsSuffix() throws Exception {
        final Context ctx = createContext();
        final Map<String, String> params = new HashMap<>();
        params.put( JDBCPlugin.PARAM_SOURCE, "reporting" );
        params.put( JDBCPlugin.PARAM_SQL, "SELECT id FROM employees" );

        final PluginException ex = assertThrows( PluginException.class,
                () -> plugin.execute( ctx, params ) );
        assertTrue( ex.getMessage().contains( "reporting" ),
                    "Error should mention the source name 'reporting': " + ex.getMessage() );
    }

    // ============== parseBoolean edge cases ==============

    /**
     * The header parameter accepts "1" and "yes" as truthy values, "false" as false.
     */
    @Test
    void testHeaderParseBooleanOneAndYes() throws Exception {
        final Properties engineProps = engine.getWikiProperties();
        engineProps.setProperty( "jdbc.url",      HSQL_URL );
        engineProps.setProperty( "jdbc.user",     HSQL_USER );
        engineProps.setProperty( "jdbc.password", HSQL_PASS );

        final Context ctx = createContext();
        final String baseSql = "SELECT id FROM employees FETCH FIRST 1 ROWS ONLY";

        // "1" → true (headers shown)
        final Map<String, String> params1 = new HashMap<>();
        params1.put( JDBCPlugin.PARAM_SQL, baseSql );
        params1.put( JDBCPlugin.PARAM_HEADER, "1" );
        assertTrue( plugin.execute( ctx, params1 ).contains( "<th" ),
                    "header='1' should show headers" );

        // "yes" → true (headers shown)
        final Map<String, String> params2 = new HashMap<>();
        params2.put( JDBCPlugin.PARAM_SQL, baseSql );
        params2.put( JDBCPlugin.PARAM_HEADER, "yes" );
        assertTrue( plugin.execute( ctx, params2 ).contains( "<th" ),
                    "header='yes' should show headers" );

        // "false" → false (no headers)
        final Map<String, String> params3 = new HashMap<>();
        params3.put( JDBCPlugin.PARAM_SQL, baseSql );
        params3.put( JDBCPlugin.PARAM_HEADER, "false" );
        assertFalse( plugin.execute( ctx, params3 ).contains( "<th" ),
                     "header='false' should not show headers" );
    }

    // ============== DatabaseType.fromDriver — additional types ==============

    @Test
    void testDatabaseTypeFromDriverAdditionalTypes() {
        assertEquals( JDBCPlugin.DatabaseType.MSSQL,
                JDBCPlugin.DatabaseType.fromDriver( "com.microsoft.sqlserver.jdbc.SQLServerDriver" ) );
        assertEquals( JDBCPlugin.DatabaseType.ORACLE,
                JDBCPlugin.DatabaseType.fromDriver( "oracle.jdbc.driver.OracleDriver" ) );
        assertEquals( JDBCPlugin.DatabaseType.DB2,
                JDBCPlugin.DatabaseType.fromDriver( "com.ibm.db2.jcc.DB2Driver" ) );
        assertEquals( JDBCPlugin.DatabaseType.SYBASE,
                JDBCPlugin.DatabaseType.fromDriver( "com.sybase.jdbc4.jdbc.SybDriver" ) );
        assertEquals( JDBCPlugin.DatabaseType.DERBY,
                JDBCPlugin.DatabaseType.fromDriver( "org.apache.derby.jdbc.ClientDriver" ) );
        assertEquals( JDBCPlugin.DatabaseType.HSQLDB,
                JDBCPlugin.DatabaseType.fromDriver( "org.hsqldb.jdbc.JDBCDriver" ) );
    }

    /**
     * Verifies that SYBASE reports TOP limit style (same as MSSQL).
     */
    @Test
    void testSybaseUsesSameTopStyleAsMssql() {
        assertEquals( JDBCPlugin.DatabaseType.MSSQL.getLimitStyle(),
                      JDBCPlugin.DatabaseType.SYBASE.getLimitStyle(),
                      "Both SYBASE and MSSQL should use TOP limit style" );
    }

    /**
     * Verifies that HSQLDB and DERBY both use FETCH FIRST style.
     */
    @Test
    void testHsqldbAndDerbyUseSameFetchFirstStyle() {
        assertEquals( JDBCPlugin.DatabaseType.HSQLDB.getLimitStyle(),
                      JDBCPlugin.DatabaseType.DERBY.getLimitStyle(),
                      "HSQLDB and DERBY should both use FETCH FIRST style" );
    }

    // ============== ConnectionConfig — no user/password uses simple DriverManager.getConnection ==============

    /**
     * When user and password are both blank, the plugin must use the single-arg
     * DriverManager.getConnection(url) path in ConnectionConfig.
     * HSQLDB accepts SA with empty password by default.
     */
    @Test
    void testConnectionWithoutCredentials() throws Exception {
        final Properties engineProps = engine.getWikiProperties();
        // Set only the URL; no user or password
        engineProps.setProperty( "jdbc.url", HSQL_URL );
        engineProps.remove( "jdbc.user" );
        engineProps.remove( "jdbc.password" );

        final Context ctx = createContext();
        final Map<String, String> params = new HashMap<>();
        params.put( JDBCPlugin.PARAM_SQL, "SELECT id FROM employees FETCH FIRST 1 ROWS ONLY" );

        final String result = plugin.execute( ctx, params );
        assertNotNull( result );
        assertTrue( result.contains( "<table" ) );
    }
}
