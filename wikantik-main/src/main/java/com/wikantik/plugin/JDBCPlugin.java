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

    Original code Copyright (C) 2014 David Vittor http://digitalspider.com.au
    Refactored for JSPWiki 3.x by the JSPWiki development team.
*/
package com.wikantik.plugin;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.PluginException;
import com.wikantik.api.plugin.Plugin;
import com.wikantik.render.RenderingManager;
import com.wikantik.util.TextUtil;
import com.wikantik.util.XHTML;
import com.wikantik.util.XhtmlUtil;
import org.jdom2.Element;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;

/**
 * A plugin that executes SELECT SQL queries against a database and displays the results as an HTML table.
 *
 * <p>The plugin supports multiple database types including MySQL, PostgreSQL, Microsoft SQL Server,
 * Oracle, DB2, and Sybase. Database connections can be configured either through JNDI DataSources
 * or direct JDBC connection properties.</p>
 *
 * <h3>Parameters:</h3>
 * <ul>
 *   <li><b>sql</b> - The SELECT query to execute. Must start with "SELECT" (case-insensitive). Default: "select 1"</li>
 *   <li><b>src</b> - The data source name. Can be a JNDI name or a suffix for property-based configuration. Default: null</li>
 *   <li><b>header</b> - Whether to display table headers. Default: true</li>
 *   <li><b>class</b> - CSS class for the wrapper div. Default: "jdbc-results"</li>
 * </ul>
 *
 * <h3>Configuration Properties (in jspwiki-custom.properties):</h3>
 * <p>For direct JDBC connections, configure these properties (optionally with a source suffix):</p>
 * <ul>
 *   <li><b>jdbc.driver</b> - JDBC driver class name (e.g., "org.postgresql.Driver")</li>
 *   <li><b>jdbc.url</b> - JDBC connection URL</li>
 *   <li><b>jdbc.user</b> - Database username</li>
 *   <li><b>jdbc.password</b> - Database password</li>
 *   <li><b>jdbc.maxresults</b> - Maximum number of rows to return. Default: 50</li>
 * </ul>
 *
 * <p>For multiple databases, use suffixed properties (e.g., jdbc.url.mydb, jdbc.user.mydb)
 * and reference them with the src parameter: [{JDBC src='mydb' sql='...'}]</p>
 *
 * <h3>JNDI Configuration:</h3>
 * <p>Alternatively, configure a JNDI DataSource in your container (e.g., Tomcat's context.xml)
 * and reference it by name: [{JDBC src='jdbc/MyDataSource' sql='...'}]</p>
 *
 * <h3>Usage Examples:</h3>
 * <pre>
 * [{JDBC sql='SELECT name, email FROM users'}]
 * [{JDBC src='reporting' sql='SELECT * FROM sales_report' class='report-table'}]
 * [{JDBC src='jdbc/AppDB' sql='SELECT COUNT(*) as total FROM orders' header='false'}]
 * </pre>
 *
 * <h3>Security Note:</h3>
 * <p>This plugin only allows SELECT queries to prevent data modification. The SQL is validated
 * to ensure it starts with "SELECT". Additionally, result sets are limited by the jdbc.maxresults
 * property to prevent excessive memory usage.</p>
 *
 * @since 3.0.7
 */
public class JDBCPlugin implements Plugin {

    private static final Logger LOG = LogManager.getLogger( JDBCPlugin.class );

    /**
     * Supported database types with their driver classes and URL patterns.
     */
    public enum DatabaseType {
        MYSQL( "com.mysql.cj.jdbc.Driver", "jdbc:mysql:", LimitStyle.LIMIT ),
        MARIADB( "org.mariadb.jdbc.Driver", "jdbc:mariadb:", LimitStyle.LIMIT ),
        POSTGRESQL( "org.postgresql.Driver", "jdbc:postgresql:", LimitStyle.LIMIT ),
        MSSQL( "com.microsoft.sqlserver.jdbc.SQLServerDriver", "jdbc:sqlserver:", LimitStyle.TOP ),
        ORACLE( "oracle.jdbc.driver.OracleDriver", "jdbc:oracle:", LimitStyle.ROWNUM ),
        DB2( "com.ibm.db2.jcc.DB2Driver", "jdbc:db2:", LimitStyle.FETCH_FIRST ),
        SYBASE( "com.sybase.jdbc4.jdbc.SybDriver", "jdbc:sybase:", LimitStyle.TOP ),
        H2( "org.h2.Driver", "jdbc:h2:", LimitStyle.LIMIT ),
        HSQLDB( "org.hsqldb.jdbc.JDBCDriver", "jdbc:hsqldb:", LimitStyle.FETCH_FIRST ),
        DERBY( "org.apache.derby.jdbc.ClientDriver", "jdbc:derby:", LimitStyle.FETCH_FIRST );

        private final String driverClass;
        private final String urlPrefix;
        private final LimitStyle limitStyle;

        DatabaseType( final String driverClass, final String urlPrefix, final LimitStyle limitStyle ) {
            this.driverClass = driverClass;
            this.urlPrefix = urlPrefix;
            this.limitStyle = limitStyle;
        }

        public String getDriverClass() {
            return driverClass;
        }

        public String getUrlPrefix() {
            return urlPrefix;
        }

        public LimitStyle getLimitStyle() {
            return limitStyle;
        }

        /**
         * Detects the database type from a JDBC URL.
         *
         * @param url the JDBC URL
         * @return the detected DatabaseType, or null if unknown
         */
        public static DatabaseType fromUrl( final String url ) {
            if ( url != null ) {
                for ( final DatabaseType type : values() ) {
                    if ( url.startsWith( type.urlPrefix ) ) {
                        return type;
                    }
                }
            }
            return null;
        }

        /**
         * Detects the database type from a driver class name.
         *
         * @param driverClass the JDBC driver class name
         * @return the detected DatabaseType, or null if unknown
         */
        public static DatabaseType fromDriver( final String driverClass ) {
            if ( driverClass != null ) {
                for ( final DatabaseType type : values() ) {
                    if ( type.driverClass.equals( driverClass ) ) {
                        return type;
                    }
                }
            }
            return null;
        }
    }

    /**
     * Different SQL dialects for limiting result rows.
     */
    private enum LimitStyle {
        LIMIT,       // MySQL, PostgreSQL, H2, HSQLDB: LIMIT n
        TOP,         // SQL Server, Sybase: SELECT TOP n
        ROWNUM,      // Oracle: WHERE ROWNUM <= n
        FETCH_FIRST  // DB2, Derby: FETCH FIRST n ROWS ONLY
    }

    // Property names for jspwiki-custom.properties
    private static final String PROP_DRIVER = "jdbc.driver";
    private static final String PROP_URL = "jdbc.url";
    private static final String PROP_USER = "jdbc.user";
    private static final String PROP_PASSWORD = "jdbc.password";
    private static final String PROP_MAXRESULTS = "jdbc.maxresults";

    // Plugin parameter names
    /** Parameter name for the SQL query. Value is {@value}. */
    public static final String PARAM_SQL = "sql";

    /** Parameter name for the data source. Value is {@value}. */
    public static final String PARAM_SOURCE = "src";

    /** Parameter name for showing table headers. Value is {@value}. */
    public static final String PARAM_HEADER = "header";

    /** Parameter name for CSS class. Value is {@value}. */
    public static final String PARAM_CLASS = "class";

    // Default values
    private static final String DEFAULT_SQL = "select 1";
    private static final String DEFAULT_CLASS = "jdbc-results";
    private static final int DEFAULT_MAXRESULTS = 50;
    private static final boolean DEFAULT_HEADER = true;

    /**
     * {@inheritDoc}
     */
    @Override
    public String execute( final Context context, final Map< String, String > params ) throws PluginException {
        final Engine engine = context.getEngine();
        final Properties props = engine.getWikiProperties();

        // Parse parameters - all local variables for thread safety
        final String source = params.get( PARAM_SOURCE );
        final String cssClass = StringUtils.defaultIfBlank( params.get( PARAM_CLASS ), DEFAULT_CLASS );
        final boolean showHeader = parseBoolean( params.get( PARAM_HEADER ), DEFAULT_HEADER );

        // Validate and get SQL
        final String sql = validateAndGetSql( params.get( PARAM_SQL ) );

        // Get max results from properties
        final int maxResults = getMaxResults( props, source );

        // Get database connection configuration
        final ConnectionConfig config = getConnectionConfig( props, source );

        // Execute query and build result
        try {
            final String tableHtml = executeQuery( context, config, sql, maxResults, showHeader );
            return "<div class='" + TextUtil.replaceEntities( cssClass ) + "'>" + tableHtml + "</div>";
        } catch ( final SQLException e ) {
            LOG.error( "Database error executing query: {}", sql, e );
            throw new PluginException( "Database error: " + e.getMessage(), e );
        }
    }

    /**
     * Validates the SQL parameter and returns a normalized query.
     */
    private String validateAndGetSql( final String sqlParam ) throws PluginException {
        final String sql = StringUtils.defaultIfBlank( sqlParam, DEFAULT_SQL ).trim();

        if ( !sql.toLowerCase().startsWith( "select" ) ) {
            throw new PluginException( "Only SELECT queries are allowed. Query must start with 'SELECT'." );
        }

        // Basic SQL injection prevention - check for multiple statements
        if ( sql.contains( ";" ) && !sql.trim().endsWith( ";" ) ) {
            throw new PluginException( "Multiple SQL statements are not allowed." );
        }

        return sql;
    }

    /**
     * Parses a boolean parameter value.
     */
    private boolean parseBoolean( final String value, final boolean defaultValue ) {
        if ( StringUtils.isBlank( value ) ) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase( value ) || "1".equals( value ) || "yes".equalsIgnoreCase( value );
    }

    /**
     * Gets the max results setting from properties.
     */
    private int getMaxResults( final Properties props, final String source ) {
        final String propKey = getPropKey( PROP_MAXRESULTS, source );
        return TextUtil.getIntegerProperty( props, propKey, DEFAULT_MAXRESULTS );
    }

    /**
     * Gets the connection configuration from properties or JNDI.
     */
    private ConnectionConfig getConnectionConfig( final Properties props, final String source ) throws PluginException {
        // First, try to get a JNDI DataSource
        final DataSource jndiDataSource = lookupJndiDataSource( source );
        if ( jndiDataSource != null ) {
            return new ConnectionConfig( jndiDataSource );
        }

        // Fall back to property-based configuration
        final String driverClass = props.getProperty( getPropKey( PROP_DRIVER, source ) );
        final String url = props.getProperty( getPropKey( PROP_URL, source ) );
        final String user = props.getProperty( getPropKey( PROP_USER, source ) );
        final String password = props.getProperty( getPropKey( PROP_PASSWORD, source ) );

        if ( StringUtils.isBlank( url ) ) {
            throw new PluginException( "No database configuration found. Configure either a JNDI DataSource " +
                    "or set jdbc.url" + ( source != null ? "." + source : "" ) + " in jspwiki-custom.properties" );
        }

        // Load the driver if specified
        if ( StringUtils.isNotBlank( driverClass ) ) {
            try {
                Class.forName( driverClass );
            } catch ( final ClassNotFoundException e ) {
                throw new PluginException( "JDBC driver not found: " + driverClass, e );
            }
        }

        // Detect database type for query limiting
        DatabaseType dbType = DatabaseType.fromDriver( driverClass );
        if ( dbType == null ) {
            dbType = DatabaseType.fromUrl( url );
        }

        return new ConnectionConfig( url, user, password, dbType );
    }

    /**
     * Attempts to look up a JNDI DataSource.
     */
    private DataSource lookupJndiDataSource( final String source ) {
        if ( StringUtils.isBlank( source ) ) {
            return null;
        }

        // Try various JNDI naming patterns
        final String[] jndiPatterns = {
            "java:/comp/env/jdbc/" + source,
            "java:/comp/env/" + source,
            "jdbc/" + source,
            source
        };

        for ( final String jndiName : jndiPatterns ) {
            try {
                final InitialContext ctx = new InitialContext();
                final Object lookup = ctx.lookup( jndiName );
                if ( lookup instanceof DataSource dataSource ) {
                    LOG.debug( "Found JNDI DataSource at: {}", jndiName );
                    return dataSource;
                }
            } catch ( final NamingException e ) {
                LOG.trace( "JNDI lookup failed for {}: {}", jndiName, e.getMessage() );
            }
        }

        return null;
    }

    /**
     * Executes the SQL query and returns HTML table markup.
     */
    private String executeQuery( final Context context, final ConnectionConfig config,
                                  final String sql, final int maxResults, final boolean showHeader ) throws SQLException, PluginException {
        final String limitedSql = addResultLimit( sql, config.getDatabaseType(), maxResults );

        try ( final Connection conn = config.getConnection();
              final Statement stmt = conn.createStatement();
              final ResultSet rs = stmt.executeQuery( limitedSql ) ) {

            return buildHtmlTable( rs, showHeader );
        }
    }

    /**
     * Adds database-specific result limiting to the SQL query.
     */
    private String addResultLimit( final String sql, final DatabaseType dbType, final int maxResults ) {
        if ( dbType == null || maxResults <= 0 ) {
            return sql;
        }

        String normalizedSql = sql.trim();
        if ( normalizedSql.endsWith( ";" ) ) {
            normalizedSql = normalizedSql.substring( 0, normalizedSql.length() - 1 ).trim();
        }

        final String lowerSql = normalizedSql.toLowerCase();

        switch ( dbType.getLimitStyle() ) {
            case LIMIT:
                if ( !lowerSql.contains( " limit " ) ) {
                    return normalizedSql + " LIMIT " + maxResults;
                }
                break;

            case TOP:
                if ( !lowerSql.contains( " top " ) ) {
                    return normalizedSql.replaceFirst( "(?i)^select\\s+", "SELECT TOP " + maxResults + " " );
                }
                break;

            case ROWNUM:
                if ( !lowerSql.contains( "rownum" ) ) {
                    return "SELECT * FROM ( " + normalizedSql + " ) WHERE ROWNUM <= " + maxResults;
                }
                break;

            case FETCH_FIRST:
                if ( !lowerSql.contains( " fetch " ) ) {
                    return normalizedSql + " FETCH FIRST " + maxResults + " ROWS ONLY";
                }
                break;
        }

        return normalizedSql;
    }

    /**
     * Builds an HTML table from a ResultSet using JDOM.
     */
    private String buildHtmlTable( final ResultSet rs, final boolean showHeader ) throws SQLException {
        final ResultSetMetaData metaData = rs.getMetaData();
        final int columnCount = metaData.getColumnCount();

        final Element table = XhtmlUtil.element( XHTML.table );
        table.setAttribute( XHTML.ATTR_class, "wikitable jdbc-table" );

        // Add header row
        if ( showHeader ) {
            final Element thead = XhtmlUtil.element( XHTML.thead );
            final Element headerRow = XhtmlUtil.element( XHTML.tr );

            for ( int i = 1; i <= columnCount; i++ ) {
                final Element th = XhtmlUtil.element( XHTML.th, metaData.getColumnLabel( i ) );
                headerRow.addContent( th );
            }

            thead.addContent( headerRow );
            table.addContent( thead );
        }

        // Add data rows
        final Element tbody = XhtmlUtil.element( XHTML.tbody );
        int rowCount = 0;

        while ( rs.next() ) {
            final Element row = XhtmlUtil.element( XHTML.tr );

            for ( int i = 1; i <= columnCount; i++ ) {
                String value = rs.getString( i );
                if ( value == null ) {
                    value = "";
                }
                final Element td = XhtmlUtil.element( XHTML.td, value );
                row.addContent( td );
            }

            tbody.addContent( row );
            rowCount++;
        }

        table.addContent( tbody );

        // Add "no results" message if empty
        if ( rowCount == 0 ) {
            final Element row = XhtmlUtil.element( XHTML.tr );
            final Element td = XhtmlUtil.element( XHTML.td, "No results" );
            td.setAttribute( XHTML.ATTR_colspan, String.valueOf( columnCount > 0 ? columnCount : 1 ) );
            td.setAttribute( XHTML.ATTR_class, "jdbc-no-results" );
            row.addContent( td );
            tbody.addContent( row );
        }

        return XhtmlUtil.serialize( table );
    }

    /**
     * Gets a property key with optional source suffix.
     */
    private String getPropKey( final String baseKey, final String source ) {
        if ( StringUtils.isNotBlank( source ) ) {
            return baseKey + "." + source;
        }
        return baseKey;
    }

    /**
     * Holds database connection configuration.
     */
    private static class ConnectionConfig {
        private final DataSource dataSource;
        private final String url;
        private final String user;
        private final String password;
        private final DatabaseType databaseType;

        ConnectionConfig( final DataSource dataSource ) {
            this.dataSource = dataSource;
            this.url = null;
            this.user = null;
            this.password = null;
            this.databaseType = null;
        }

        ConnectionConfig( final String url, final String user, final String password, final DatabaseType databaseType ) {
            this.dataSource = null;
            this.url = url;
            this.user = user;
            this.password = password;
            this.databaseType = databaseType;
        }

        Connection getConnection() throws SQLException {
            if ( dataSource != null ) {
                return dataSource.getConnection();
            }

            if ( StringUtils.isBlank( user ) && StringUtils.isBlank( password ) ) {
                return DriverManager.getConnection( url );
            }

            return DriverManager.getConnection( url, user, password );
        }

        DatabaseType getDatabaseType() {
            return databaseType;
        }
    }
}
