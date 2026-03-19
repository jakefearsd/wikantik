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

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.NoRequiredPropertyException;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.util.Serializer;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import java.io.IOException;
import java.io.Serializable;
import java.security.Principal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * <p>
 * Implementation of UserDatabase that persists {@link DefaultUserProfile}
 * objects to a JDBC DataSource, as might typically be provided by a web
 * container. This implementation looks up the JDBC DataSource using JNDI. The
 * JNDI name of the datasource, backing table and mapped columns used by this 
 * class can be overridden by adding settings in <code>wikantik.properties</code>.
 * </p>
 * <p>
 * Configurable properties are these:
 * </p>
 * <table>
 * <tr> <thead>
 * <th>Property</th>
 * <th>Default</th>
 * <th>Definition</th>
 * <thead> </tr>
 * <tr>
 * <td><code>wikantik.userdatabase.datasource</code></td>
 * <td><code>jdbc/UserDatabase</code></td>
 * <td>The JNDI name of the DataSource</td>
 * </tr>
 * <tr>
 * <td><code>wikantik.userdatabase.table</code></td>
 * <td><code>users</code></td>
 * <td>The table that stores the user profiles</td>
 * </tr>
 * <tr>
 * <td><code>wikantik.userdatabase.attributes</code></td>
 * <td><code>attributes</code></td>
 * <td>The CLOB column containing the profile's custom attributes, stored as key/value strings, each separated by newline.</td>
 * </tr>
 * <tr>
 * <td><code>wikantik.userdatabase.created</code></td>
 * <td><code>created</code></td>
 * <td>The column containing the profile's creation timestamp</td>
 * </tr>
 * <tr>
 * <td><code>wikantik.userdatabase.email</code></td>
 * <td><code>email</code></td>
 * <td>The column containing the user's e-mail address</td>
 * </tr>
 * <tr>
 * <td><code>wikantik.userdatabase.fullName</code></td>
 * <td><code>full_name</code></td>
 * <td>The column containing the user's full name</td>
 * </tr>
 * <tr>
 * <td><code>wikantik.userdatabase.loginName</code></td>
 * <td><code>login_name</code></td>
 * <td>The column containing the user's login id</td>
 * </tr>
 * <tr>
 * <td><code>wikantik.userdatabase.password</code></td>
 * <td><code>password</code></td>
 * <td>The column containing the user's password</td>
 * </tr>
 * <tr>
 * <td><code>wikantik.userdatabase.modified</code></td>
 * <td><code>modified</code></td>
 * <td>The column containing the profile's last-modified timestamp</td>
 * </tr>
 * <tr>
 * <td><code>wikantik.userdatabase.uid</code></td>
 * <td><code>uid</code></td>
 * <td>The column containing the profile's unique identifier, as a long integer</td>
 * </tr>
 * <tr>
 * <td><code>wikantik.userdatabase.wikiName</code></td>
 * <td><code>wiki_name</code></td>
 * <td>The column containing the user's wiki name</td>
 * </tr>
 * <tr>
 * <td><code>wikantik.userdatabase.lockExpiry</code></td>
 * <td><code>lock_expiry</code></td>
 * <td>The column containing the date/time when the profile, if locked, should be unlocked.</td>
 * </tr>
 * <tr>
 * <td><code>wikantik.userdatabase.roleTable</code></td>
 * <td><code>roles</code></td>
 * <td>The table that stores user roles. When a new user is created, a new
 * record is inserted containing user's initial role. The table will have an ID
 * column whose name and values correspond to the contents of the user table's
 * login name column. It will also contain a role column (see next row).</td>
 * </tr>
 * <tr>
 * <td><code>wikantik.userdatabase.role</code></td>
 * <td><code>role</code></td>
 * <td>The column in the role table that stores user roles. When a new user is
 * created, this column will be populated with the value
 * <code>Authenticated</code>. Once created, JDBCUserDatabase does not use
 * this column again; it is provided strictly for the convenience of
 * container-managed authentication services.</td>
 * </tr>
 * </table>
 * <p>
 * This class hashes passwords using SHA-1. All of the underying SQL commands
 * used by this class are implemented using prepared statements, so it is immune
 * to SQL injection attacks.
 * </p>
 * <p>
 * This class is typically used in conjunction with a web container's JNDI
 * resource factory. For example, Tomcat provides a basic
 * JNDI factory for registering DataSources. To give JSPWiki access to the JNDI
 * resource named by <code></code>, you would declare the datasource resource
 * similar to this:
 * </p>
 * <blockquote><code>&lt;Context ...&gt;<br/>
 *  &nbsp;&nbsp;...<br/>
 *  &nbsp;&nbsp;&lt;Resource name="jdbc/UserDatabase" auth="Container"<br/>
 *  &nbsp;&nbsp;&nbsp;&nbsp;type="javax.sql.DataSource" username="dbusername" password="dbpassword"<br/>
 *  &nbsp;&nbsp;&nbsp;&nbsp;driverClassName="org.hsql.jdbcDriver" url="jdbc:HypersonicSQL:database"<br/>
 *  &nbsp;&nbsp;&nbsp;&nbsp;maxActive="8" maxIdle="4"/&gt;<br/>
 *  &nbsp;...<br/>
 * &lt;/Context&gt;</code></blockquote>
 * <p>
 * To configure JSPWiki to use JDBC support, first create a database 
 * with a structure similar to that provided by the HSQL and PostgreSQL 
 * scripts in src/main/config/db.  If you have different table or column 
 * names you can either alias them with a database view and have JSPWiki
 * use the views, or alter the WEB-INF/wikantik.properties file: the 
 * wikantik.userdatabase.* and wikantik.groupdatabase.* properties change the
 * names of the tables and columns that JSPWiki uses.
 * </p>
 * <p>
 * A JNDI datasource (named jdbc/UserDatabase by default but can be configured 
 * in the wikantik.properties file) will need to be created in your servlet container.
 * JDBC driver JARs should be added, e.g. in Tomcat's <code>lib</code>
 * directory. For more Tomcat JNDI configuration examples, see <a
 * href="http://tomcat.apache.org/tomcat-7.0-doc/jndi-resources-howto.html">
 * http://tomcat.apache.org/tomcat-7.0-doc/jndi-resources-howto.html</a>.
 * Once done, restart JSPWiki in the servlet container for it to read the 
 * new properties and switch to JDBC authentication.
 * </p>
 * <p>
 * JDBCUserDatabase commits changes as transactions if the back-end database
 * supports them. Changes are made immediately (during the {@link #save(UserProfile)} method).
 * </p>
 * 
 * @since 2.3
 */
public class JDBCUserDatabase extends AbstractUserDatabase {

    private static final String NOTHING = "";

    public static final String DEFAULT_DB_ATTRIBUTES = "attributes";

    public static final String DEFAULT_DB_CREATED = "created";

    public static final String DEFAULT_DB_EMAIL = "email";

    public static final String DEFAULT_DB_FULL_NAME = "full_name";

    public static final String DEFAULT_DB_JNDI_NAME = "jdbc/UserDatabase";

    public static final String DEFAULT_DB_LOCK_EXPIRY = "lock_expiry";

    public static final String DEFAULT_DB_MODIFIED = "modified";

    public static final String DEFAULT_DB_ROLE = "role";

    public static final String DEFAULT_DB_ROLE_TABLE = "roles";

    public static final String DEFAULT_DB_TABLE = "users";

    public static final String DEFAULT_DB_LOGIN_NAME = "login_name";

    public static final String DEFAULT_DB_PASSWORD = "password";

    public static final String DEFAULT_DB_UID = "uid";

    public static final String DEFAULT_DB_WIKI_NAME = "wiki_name";

    public static final String PROP_DB_ATTRIBUTES = "wikantik.userdatabase.attributes";

    public static final String PROP_DB_CREATED = "wikantik.userdatabase.created";

    public static final String PROP_DB_EMAIL = "wikantik.userdatabase.email";

    public static final String PROP_DB_FULL_NAME = "wikantik.userdatabase.fullName";

    public static final String PROP_DB_DATASOURCE = "wikantik.userdatabase.datasource";

    public static final String PROP_DB_LOCK_EXPIRY = "wikantik.userdatabase.lockExpiry";

    public static final String PROP_DB_LOGIN_NAME = "wikantik.userdatabase.loginName";

    public static final String PROP_DB_MODIFIED = "wikantik.userdatabase.modified";

    public static final String PROP_DB_PASSWORD = "wikantik.userdatabase.password";

    public static final String PROP_DB_UID = "wikantik.userdatabase.uid";

    public static final String PROP_DB_ROLE = "wikantik.userdatabase.role";

    public static final String PROP_DB_ROLE_TABLE = "wikantik.userdatabase.roleTable";

    public static final String PROP_DB_TABLE = "wikantik.userdatabase.table";

    public static final String PROP_DB_WIKI_NAME = "wikantik.userdatabase.wikiName";

    private DataSource ds;

    private String deleteUserByLoginName;

    private String deleteRoleByLoginName;

    private String findByEmail;

    private String findByFullName;

    private String findByLoginName;

    private String findByUid;

    private String findByWikiName;

    private String renameProfile;

    private String renameRoles;

    private String updateProfile;

    private String findAll;

    private String findRoles;

    private String insertProfile;

    private String insertRole;

    private String attributes;

    private String email;

    private String fullName;

    private String lockExpiry;

    private String loginName;

    private String password;

    private String uid;
    
    private String wikiName;

    private String created;

    private String modified;

    private boolean supportsCommits;

    /**
     * Looks up and deletes the first {@link UserProfile} in the user database
     * that matches a profile having a given login name. If the user database
     * does not contain a user with a matching attribute, throws a
     * {@link NoSuchPrincipalException}. This method is intended to be atomic;
     * results cannot be partially committed. If the commit fails, it should
     * roll back its state appropriately. Implementing classes that persist to
     * the file system may wish to make this method <code>synchronized</code>.
     * 
     * @param newLoginName the login name of the user profile that shall be deleted
     */
    @Override
    public void deleteByLoginName( final String loginNameToDelete ) throws WikiSecurityException {
        // Get the existing user; if not found, throws NoSuchPrincipalException
        findByLoginName( loginNameToDelete );

        try( final Connection conn = ds.getConnection() ;
             final PreparedStatement ps1 = conn.prepareStatement( deleteUserByLoginName );
             final PreparedStatement ps2 = conn.prepareStatement( deleteRoleByLoginName ) )
        {
            // Open the database connection
            if( supportsCommits ) {
                conn.setAutoCommit( false );
            }

            // Delete user record
            ps1.setString( 1, loginNameToDelete );
            ps1.execute();

            // Delete role record
            ps2.setString( 1, loginNameToDelete );
            ps2.execute();

            // Commit and close connection
            if( supportsCommits ) {
                conn.commit();
            }
        } catch( final SQLException e ) {
            throw new WikiSecurityException( e.getMessage(), e );
        }
    }

    /**
     * @see com.wikantik.auth.user.UserDatabase#findByEmail(java.lang.String)
     */
    @Override
    public UserProfile findByEmail( final String index ) throws NoSuchPrincipalException {
        return findByPreparedStatement( findByEmail, index );
    }

    /**
     * @see com.wikantik.auth.user.UserDatabase#findByFullName(java.lang.String)
     */
    @Override
    public UserProfile findByFullName( final String index ) throws NoSuchPrincipalException {
        return findByPreparedStatement( findByFullName, index );
    }

    /**
     * @see com.wikantik.auth.user.UserDatabase#findByLoginName(java.lang.String)
     */
    @Override
    public UserProfile findByLoginName( final String index ) throws NoSuchPrincipalException {
        return findByPreparedStatement( findByLoginName, index );
    }

    /**
     * @see com.wikantik.auth.user.UserDatabase#findByWikiName(String)
     */
    @Override
    public UserProfile findByUid( final String uidToFind ) throws NoSuchPrincipalException {
        return findByPreparedStatement( findByUid, uidToFind );
    }

    /**
     * @see com.wikantik.auth.user.UserDatabase#findByWikiName(String)
     */
    @Override
    public UserProfile findByWikiName( final String index ) throws NoSuchPrincipalException {
        return findByPreparedStatement( findByWikiName, index );
    }

    /**
     * Returns all WikiNames that are stored in the UserDatabase as an array of
     * WikiPrincipal objects. If the database does not contain any profiles,
     * this method will return a zero-length array.
     * 
     * @return the WikiNames
     */
    @Override
    public Principal[] getWikiNames() throws WikiSecurityException {
        final Set<Principal> principals = new HashSet<>();
        try( final Connection conn = ds.getConnection();
             final PreparedStatement ps = conn.prepareStatement( findAll );
             final ResultSet rs = ps.executeQuery() ) {
            while( rs.next() ) {
                final String wikiNameValue = rs.getString( wikiName );
                if( StringUtils.isEmpty( wikiNameValue ) ) {
                    LOG.warn( "Detected null or empty wiki name for {} in JDBCUserDataBase. Check your user database.", rs.getString( loginName ) );
                } else {
                    final Principal principal = new WikiPrincipal( wikiNameValue, WikiPrincipal.WIKI_NAME );
                    principals.add( principal );
                }
            }
        } catch( final SQLException e ) {
            throw new WikiSecurityException( e.getMessage(), e );
        }

        return principals.toArray( new Principal[0] );
    }

    /**
     * @see com.wikantik.auth.user.UserDatabase#initialize(com.wikantik.api.core.Engine, java.util.Properties)
     */
    @Override
    public void initialize( final Engine engine, final Properties props ) throws NoRequiredPropertyException, WikiSecurityException {
        final String jndiName = props.getProperty( PROP_DB_DATASOURCE, DEFAULT_DB_JNDI_NAME );
        try {
            final Context initCtx = new InitialContext();
            final Context ctx = (Context) initCtx.lookup( "java:comp/env" );
            ds = (DataSource) ctx.lookup( jndiName );

            // Prepare the SQL selectors
            final String userTable = props.getProperty( PROP_DB_TABLE, DEFAULT_DB_TABLE );
            email = props.getProperty( PROP_DB_EMAIL, DEFAULT_DB_EMAIL );
            fullName = props.getProperty( PROP_DB_FULL_NAME, DEFAULT_DB_FULL_NAME );
            lockExpiry = props.getProperty( PROP_DB_LOCK_EXPIRY, DEFAULT_DB_LOCK_EXPIRY );
            loginName = props.getProperty( PROP_DB_LOGIN_NAME, DEFAULT_DB_LOGIN_NAME );
            password = props.getProperty( PROP_DB_PASSWORD, DEFAULT_DB_PASSWORD );
            uid = props.getProperty( PROP_DB_UID, DEFAULT_DB_UID );
            wikiName = props.getProperty( PROP_DB_WIKI_NAME, DEFAULT_DB_WIKI_NAME );
            created = props.getProperty( PROP_DB_CREATED, DEFAULT_DB_CREATED );
            modified = props.getProperty( PROP_DB_MODIFIED, DEFAULT_DB_MODIFIED );
            attributes = props.getProperty( PROP_DB_ATTRIBUTES, DEFAULT_DB_ATTRIBUTES );

            findAll = "SELECT * FROM " + userTable;
            findByEmail = "SELECT * FROM " + userTable + " WHERE " + email + "=?";
            findByFullName = "SELECT * FROM " + userTable + " WHERE " + fullName + "=?";
            findByLoginName = "SELECT * FROM " + userTable + " WHERE " + loginName + "=?";
            findByUid = "SELECT * FROM " + userTable + " WHERE " + uid + "=?";
            findByWikiName = "SELECT * FROM " + userTable + " WHERE " + wikiName + "=?";

            // The user insert SQL prepared statement
            insertProfile = "INSERT INTO " + userTable + " ("
                              + uid + ","
                              + email + ","
                              + fullName + ","
                              + password + ","
                              + wikiName + ","
                              + modified + ","
                              + loginName + ","
                              + attributes + ","
                              + created
                              + ") VALUES (?,?,?,?,?,?,?,?,?)";
            
            // The user update SQL prepared statement
            updateProfile = "UPDATE " + userTable + " SET "
                              + uid + "=?,"
                              + email + "=?,"
                              + fullName + "=?,"
                              + password + "=?,"
                              + wikiName + "=?,"
                              + modified + "=?,"
                              + loginName + "=?,"
                              + attributes + "=?,"
                              + lockExpiry + "=? "
                              + "WHERE " + loginName + "=?";

            // Prepare the role insert SQL
            final String roleTable = props.getProperty( PROP_DB_ROLE_TABLE, DEFAULT_DB_ROLE_TABLE );
            final String role = props.getProperty( PROP_DB_ROLE, DEFAULT_DB_ROLE );
            insertRole = "INSERT INTO " + roleTable + " (" + loginName + "," + role + ") VALUES (?,?)";
            findRoles = "SELECT * FROM " + roleTable + " WHERE " + loginName + "=?";

            // Prepare the user delete SQL
            deleteUserByLoginName = "DELETE FROM " + userTable + " WHERE " + loginName + "=?";

            // Prepare the role delete SQL
            deleteRoleByLoginName = "DELETE FROM " + roleTable + " WHERE " + loginName + "=?";

            // Prepare the rename user/roles SQL
            renameProfile = "UPDATE " + userTable + " SET " + loginName + "=?," + modified + "=? WHERE " + loginName
                              + "=?";
            renameRoles = "UPDATE " + roleTable + " SET " + loginName + "=? WHERE " + loginName + "=?";
        } catch( final NamingException e ) {
            LOG.error( "JDBCUserDatabase initialization error: " + e.getMessage() );
            throw new NoRequiredPropertyException( PROP_DB_DATASOURCE, "JDBCUserDatabase initialization error: " + e.getMessage() );
        }

        // Test connection by doing a quickie select
        try( final Connection conn = ds.getConnection(); final PreparedStatement ps = conn.prepareStatement( findAll ) ) {
        } catch( final SQLException e ) {
            LOG.error( "DB connectivity error: " + e.getMessage() );
            throw new WikiSecurityException("DB connectivity error: " + e.getMessage(), e );
        }
        LOG.info( "JDBCUserDatabase initialized from JNDI DataSource: {}", jndiName );

        // Determine if the datasource supports commits
        try( final Connection conn = ds.getConnection() ) {
            final DatabaseMetaData dmd = conn.getMetaData();
            if( dmd.supportsTransactions() ) {
                supportsCommits = true;
                conn.setAutoCommit( false );
                LOG.info( "JDBCUserDatabase supports transactions. Good; we will use them." );
            }
        } catch( final SQLException e ) {
            LOG.warn( "JDBCUserDatabase warning: user database doesn't seem to support transactions. Reason: {}", e.getMessage() );
        }
    }

    /**
     * @see com.wikantik.auth.user.UserDatabase#rename(String, String)
     */
    @Override
    public void rename( final String oldLoginName, final String newName ) throws DuplicateUserException, WikiSecurityException {
        // Get the existing user; if not found, throws NoSuchPrincipalException
        final UserProfile profile = findByLoginName( oldLoginName );

        // Get user with the proposed name; if found, it's a collision
        try {
            final UserProfile otherProfile = findByLoginName( newName );
            if( otherProfile != null ) {
                throw new DuplicateUserException( "security.error.cannot.rename", newName );
            }
        } catch( final NoSuchPrincipalException e ) {
            // Good! That means it's safe to save using the new name
        }

        try( final Connection conn = ds.getConnection();
             final PreparedStatement ps1 = conn.prepareStatement( renameProfile );
             final PreparedStatement ps2 = conn.prepareStatement( renameRoles ) ) {
            if( supportsCommits ) {
                conn.setAutoCommit( false );
            }

            final Timestamp ts = new Timestamp( System.currentTimeMillis() );
            final Date modDate = new Date( ts.getTime() );

            // Change the login ID for the user record
            ps1.setString( 1, newName );
            ps1.setTimestamp( 2, ts );
            ps1.setString( 3, oldLoginName );
            ps1.execute();

            // Change the login ID for the role records
            ps2.setString( 1, newName );
            ps2.setString( 2, oldLoginName );
            ps2.execute();

            // Set the profile name and mod time
            profile.setLoginName( newName );
            profile.setLastModified( modDate );

            // Commit and close connection
            if( supportsCommits ) {
                conn.commit();
            }
        } catch( final SQLException e ) {
            throw new WikiSecurityException( e.getMessage(), e );
        }
    }

    /**
     * @see com.wikantik.auth.user.UserDatabase#save(com.wikantik.auth.user.UserProfile)
     */
    @Override
    public void save( final UserProfile profile ) throws WikiSecurityException {
        final String initialRole = "Authenticated";

        // Figure out which prepared statement to use & execute it
        final String loginName = profile.getLoginName();
        UserProfile existingProfile = null;

        try {
            existingProfile = findByLoginName( loginName );
        } catch( final NoSuchPrincipalException e ) {
            // Existing profile will be null
        }

        // Get a clean password from the passed profile.
        // Blank password is the same as null, which means we re-use the existing one.
        String password = profile.getPassword();
        final String existingPassword = (existingProfile == null) ? null : existingProfile.getPassword();
        if( NOTHING.equals( password ) ) {
            password = null;
        }
        if( password == null ) {
            password = existingPassword;
        }

        // If password changed, hash it before we save
        if( !Strings.CS.equals( password, existingPassword ) ) {
            password = getHash( password );
        }

        try( final Connection conn = ds.getConnection();
             final PreparedStatement ps1 = conn.prepareStatement( insertProfile );
             final PreparedStatement ps2 = conn.prepareStatement( findRoles );
             final PreparedStatement ps3 = conn.prepareStatement( insertRole );
             final PreparedStatement ps4 = conn.prepareStatement( updateProfile ) ) {
            if( supportsCommits ) {
                conn.setAutoCommit( false );
            }

            final Timestamp ts = new Timestamp( System.currentTimeMillis() );
            final Date modDate = new Date( ts.getTime() );
            final java.sql.Date lockExpiry = profile.getLockExpiry() == null ? null : new java.sql.Date( profile.getLockExpiry().getTime() );
            if( existingProfile == null ) {
                // User is new: insert new user record
                setProfileParameters( ps1, profile, password, ts );
                ps1.setTimestamp( 9, ts );
                ps1.execute();

                // Insert new role record
                ps2.setString( 1, profile.getLoginName() );
                int roles = 0;
                try ( final ResultSet rs = ps2.executeQuery() ) {
                    while ( rs.next() ) {
                        roles++;
                    }
                }

                if( roles == 0 ) {
                    ps3.setString( 1, profile.getLoginName() );
                    ps3.setString( 2, initialRole );
                    ps3.execute();
                }

                // Set the profile creation time
                profile.setCreated( modDate );
            } else {
                // User exists: modify existing record
                setProfileParameters( ps4, profile, password, ts );
                ps4.setDate( 9, lockExpiry );
                ps4.setString( 10, profile.getLoginName() );
                ps4.execute();
            }
            // Set the profile mod time
            profile.setLastModified( modDate );

            // Commit and close connection
            if( supportsCommits ) {
                conn.commit();
            }
        } catch( final SQLException e ) {
            throw new WikiSecurityException( e.getMessage(), e );
        }
    }

    /**
     * Private method that returns the first {@link UserProfile} matching a
     * named column's value. This method will also set the UID if it has not yet been set.     
     * @param sql the SQL statement that should be prepared; it must have one parameter
     * to set (either a String or a Long)
     * @param index the value to match
     * @return the resolved UserProfile
     * @throws NoSuchPrincipalException problems accessing the database
     */
    /**
     * Sets the common profile parameters (1-8) on a PreparedStatement for both insert and update operations.
     */
    private void setProfileParameters( final PreparedStatement ps, final UserProfile profile,
                                        final String password, final Timestamp ts ) throws WikiSecurityException, SQLException {
        ps.setString( 1, profile.getUid() );
        ps.setString( 2, profile.getEmail() );
        ps.setString( 3, profile.getFullname() );
        ps.setString( 4, password );
        ps.setString( 5, profile.getWikiName() );
        ps.setTimestamp( 6, ts );
        ps.setString( 7, profile.getLoginName() );
        try {
            ps.setString( 8, Serializer.serializeToBase64( profile.getAttributes() ) );
        } catch ( final IOException e ) {
            throw new WikiSecurityException( "Could not save user profile attribute. Reason: " + e.getMessage(), e );
        }
    }

    private UserProfile findByPreparedStatement( final String sql, final Object index ) throws NoSuchPrincipalException
    {
        UserProfile profile = null;
        boolean found = false;
        boolean unique = true;
        try( final Connection conn = ds.getConnection(); final PreparedStatement ps = conn.prepareStatement( sql ) ) {
            if( supportsCommits ) {
                conn.setAutoCommit( false );
            }
            
            // Set the parameter to search by
            if( index instanceof String str ) {
                ps.setString( 1, str );
            } else if ( index instanceof Long lng ) {
                ps.setLong( 1, lng );
            } else {
                throw new IllegalArgumentException( "Index type not recognized!" );
            }
            
            // Go and get the record!
            try( final ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    if( profile != null ) {
                        unique = false;
                        break;
                    }
                    profile = newProfile();
                    
                    // Fetch the basic user attributes
                    profile.setUid( rs.getString( uid ) );
                    if ( profile.getUid() == null ) {
                        profile.setUid( generateUid( this ) );
                    }
                    profile.setCreated( rs.getTimestamp( created ) );
                    profile.setEmail( rs.getString( email ) );
                    profile.setFullname( rs.getString( fullName ) );
                    profile.setLastModified( rs.getTimestamp( modified ) );
                    final Date lockExpiryDate = rs.getDate( lockExpiry );
                    profile.setLockExpiry( rs.wasNull() ? null : lockExpiryDate );
                    profile.setLoginName( rs.getString( loginName ) );
                    profile.setPassword( rs.getString( password ) );
                    
                    // Fetch the user attributes
                    final String rawAttributes = rs.getString( attributes );
                    if ( rawAttributes != null ) {
                        try {
                            final Map<String,? extends Serializable> userAttributes = Serializer.deserializeFromBase64( rawAttributes );
                            profile.getAttributes().putAll( userAttributes );
                        } catch ( final IOException e ) {
                            LOG.error( "Could not parse user profile attributes!", e );
                        }
                    }
                    found = true;
                }
            }
        } catch( final SQLException e ) {
            throw new NoSuchPrincipalException( e.getMessage() );
        }

        if( !found ) {
            throw new NoSuchPrincipalException( "Could not find profile in database!" );
        }
        if( !unique ) {
            throw new NoSuchPrincipalException( "More than one profile in database!" );
        }
        return profile;
    }

}
