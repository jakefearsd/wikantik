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
import com.wikantik.auth.AbstractJDBCDatabase;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.util.Serializer;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
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
 *  &nbsp;&nbsp;&nbsp;&nbsp;driverClassName="org.postgresql.Driver" url="jdbc:postgresql://localhost:5432/wikantik"<br/>
 *  &nbsp;&nbsp;&nbsp;&nbsp;maxActive="8" maxIdle="4"/&gt;<br/>
 *  &nbsp;...<br/>
 * &lt;/Context&gt;</code></blockquote>
 * <p>
 * To configure JSPWiki to use JDBC support, first create a database 
 * with a structure similar to that provided by the PostgreSQL
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

    private static final String FIND_ALL = "SELECT * FROM users";
    private static final String FIND_BY_EMAIL = "SELECT * FROM users WHERE email=?";
    private static final String FIND_BY_FULL_NAME = "SELECT * FROM users WHERE full_name=?";
    private static final String FIND_BY_LOGIN_NAME = "SELECT * FROM users WHERE login_name=?";
    private static final String FIND_BY_UID = "SELECT * FROM users WHERE uid=?";
    private static final String FIND_BY_WIKI_NAME = "SELECT * FROM users WHERE wiki_name=?";
    private static final String INSERT_PROFILE = "INSERT INTO users (uid,email,full_name,password,wiki_name,modified,login_name,attributes,bio,created,password_must_change) VALUES (?,?,?,?,?,?,?,?,?,?,?)";
    private static final String UPDATE_PROFILE = "UPDATE users SET uid=?,email=?,full_name=?,password=?,wiki_name=?,modified=?,login_name=?,attributes=?,bio=?,lock_expiry=?,password_must_change=? WHERE login_name=?";
    private static final String COUNT_LOCKED_USERS = "SELECT COUNT(*) FROM users WHERE lock_expiry IS NOT NULL AND lock_expiry > CURRENT_TIMESTAMP";
    private static final String UPDATE_LAST_LOGIN = "UPDATE users SET last_login=? WHERE login_name=?";
    private static final String INSERT_ROLE = "INSERT INTO roles (login_name,role) VALUES (?,?)";
    private static final String FIND_ROLES = "SELECT * FROM roles WHERE login_name=?";
    private static final String DELETE_USER = "DELETE FROM users WHERE login_name=?";
    private static final String DELETE_ROLES = "DELETE FROM roles WHERE login_name=?";
    private static final String RENAME_PROFILE = "UPDATE users SET login_name=?,modified=? WHERE login_name=?";
    private static final String RENAME_ROLES = "UPDATE roles SET login_name=? WHERE login_name=?";

    private DataSource ds;

    private boolean supportsCommits;

    /**
     * Cookie-less basic-auth re-runs {@code authMgr.login()} per request, which calls
     * {@link #findByLoginName(String)} and issues a {@code users} SELECT (one DB connection)
     * each time. Cache the resolved profile by login name with a short TTL to absorb that
     * per-request read. Mutators (save / delete / rename) evict the affected login(s) so an
     * admin edit (including a password change) is reflected immediately. Only the DB read is
     * cached; {@code login()}/bcrypt are unaffected.
     */
    private static final long USER_TTL_SECONDS = 60L;
    private final Cache< String, UserProfile > byLoginCache = Caffeine.newBuilder()
            .expireAfterWrite( Duration.ofSeconds( USER_TTL_SECONDS ) )
            .maximumSize( 10_000 )
            .recordStats()
            .build();

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

        try( Connection conn = ds.getConnection() ;
             PreparedStatement ps1 = conn.prepareStatement( DELETE_USER );
             PreparedStatement ps2 = conn.prepareStatement( DELETE_ROLES ) )
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

            // Evict the now-deleted login so the next lookup goes to the DB.
            byLoginCache.invalidate( loginNameToDelete );
        } catch( final SQLException e ) {
            throw new WikiSecurityException( e.getMessage(), e );
        }
    }

    /**
     * @see com.wikantik.auth.user.UserDatabase#findByEmail(java.lang.String)
     */
    @Override
    public UserProfile findByEmail( final String index ) throws NoSuchPrincipalException {
        return findByPreparedStatement( FIND_BY_EMAIL, index );
    }

    /**
     * @see com.wikantik.auth.user.UserDatabase#findByFullName(java.lang.String)
     */
    @Override
    public UserProfile findByFullName( final String index ) throws NoSuchPrincipalException {
        return findByPreparedStatement( FIND_BY_FULL_NAME, index );
    }

    /**
     * @see com.wikantik.auth.user.UserDatabase#findByLoginName(java.lang.String)
     */
    @Override
    public UserProfile findByLoginName( final String index ) throws NoSuchPrincipalException {
        final UserProfile cached = byLoginCache.getIfPresent( index );
        if( cached != null ) {
            // Hand out a private copy: callers (AdminUserResource, AuthResource) mutate the
            // returned profile and save it, and the cached instance must never be shared.
            return DefaultUserProfile.copyOf( cached );
        }
        // Misses are not cached: unknown logins fall through to the DB each call (not the hot path).
        final UserProfile profile = findByPreparedStatement( FIND_BY_LOGIN_NAME, index );
        // Cache a private copy and return the caller's own fresh instance.
        byLoginCache.put( index, DefaultUserProfile.copyOf( profile ) );
        return profile;
    }

    /**
     * @see com.wikantik.auth.user.UserDatabase#findByWikiName(String)
     */
    @Override
    public UserProfile findByUid( final String uidToFind ) throws NoSuchPrincipalException {
        return findByPreparedStatement( FIND_BY_UID, uidToFind );
    }

    /**
     * @see com.wikantik.auth.user.UserDatabase#findByWikiName(String)
     */
    @Override
    public UserProfile findByWikiName( final String index ) throws NoSuchPrincipalException {
        return findByPreparedStatement( FIND_BY_WIKI_NAME, index );
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
        try( Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement( FIND_ALL );
             ResultSet rs = ps.executeQuery() ) {
            while( rs.next() ) {
                final String wikiNameValue = rs.getString( "wiki_name" );
                if( StringUtils.isEmpty( wikiNameValue ) ) {
                    LOG.warn( "Detected null or empty wiki name for {} in JDBCUserDataBase. Check your user database.", rs.getString( "login_name" ) );
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
     * {@inheritDoc}
     * <p>
     * Overrides the interface default with a single aggregate query against the {@code users} table, counting rows whose
     * {@code lock_expiry} is non-null and still in the future relative to the database's clock.
     */
    @Override
    public long countLockedUsers() throws WikiSecurityException {
        try( Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement( COUNT_LOCKED_USERS );
             ResultSet rs = ps.executeQuery() ) {
            if( rs.next() ) {
                return rs.getLong( 1 );
            }
            return 0L;
        } catch( final SQLException e ) {
            LOG.error( "Could not count locked users. Reason: {}", e.getMessage() );
            throw new WikiSecurityException( e.getMessage(), e );
        }
    }

    /**
     * @see com.wikantik.auth.user.UserDatabase#initialize(com.wikantik.api.core.Engine, java.util.Properties)
     */
    @Override
    @SuppressWarnings( "PMD.UnusedLocalVariable" ) // try-with-resources below holds the prepared statement only for its side effect.
    public void initialize( final Engine engine, final Properties props ) throws NoRequiredPropertyException, WikiSecurityException {
        final String jndiName = props.getProperty( AbstractJDBCDatabase.PROP_DATASOURCE, AbstractJDBCDatabase.DEFAULT_DATASOURCE );
        ds = com.wikantik.auth.JndiDataSources.lookup( jndiName, "JDBCUserDatabase", AbstractJDBCDatabase.PROP_DATASOURCE );

        // Test connection by doing a quickie select
        try( Connection conn = ds.getConnection(); PreparedStatement ps = conn.prepareStatement( FIND_ALL ) ) {
        } catch( final SQLException e ) {
            LOG.error( "DB connectivity error: {}", e.getMessage() );
            throw new WikiSecurityException("DB connectivity error: " + e.getMessage(), e );
        }
        LOG.info( "JDBCUserDatabase initialized from JNDI DataSource: {}", jndiName );

        // Determine if the datasource supports commits
        try( Connection conn = ds.getConnection() ) {
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
        // Get the existing user (uncached: this is a write path); if not found, throws NoSuchPrincipalException
        final UserProfile profile = findByPreparedStatement( FIND_BY_LOGIN_NAME, oldLoginName );

        // Get user with the proposed name; if found, it's a collision. Uncached so a stale entry
        // for a since-deleted login can't falsely block the rename.
        try {
            final UserProfile otherProfile = findByPreparedStatement( FIND_BY_LOGIN_NAME, newName );
            if( otherProfile != null ) {
                throw new DuplicateUserException( "security.error.cannot.rename", newName );
            }
        } catch( final NoSuchPrincipalException e ) {
            // Good! That means it's safe to save using the new name
        }

        try( Connection conn = ds.getConnection();
             PreparedStatement ps1 = conn.prepareStatement( RENAME_PROFILE );
             PreparedStatement ps2 = conn.prepareStatement( RENAME_ROLES ) ) {
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

            // A rename changes the login name itself, so evict both the old and new keys.
            byLoginCache.invalidate( oldLoginName );
            byLoginCache.invalidate( newName );
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
            // Read the existing record straight from the DB, never the cache: the password-reuse
            // logic below compares against this profile, and a cached instance may be the very
            // object the caller just mutated.
            existingProfile = findByPreparedStatement( FIND_BY_LOGIN_NAME, loginName );
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

        try( Connection conn = ds.getConnection();
             PreparedStatement ps1 = conn.prepareStatement( INSERT_PROFILE );
             PreparedStatement ps2 = conn.prepareStatement( FIND_ROLES );
             PreparedStatement ps3 = conn.prepareStatement( INSERT_ROLE );
             PreparedStatement ps4 = conn.prepareStatement( UPDATE_PROFILE ) ) {
            if( supportsCommits ) {
                conn.setAutoCommit( false );
            }

            final Timestamp ts = new Timestamp( System.currentTimeMillis() );
            final Date modDate = new Date( ts.getTime() );
            final java.sql.Date lockExpiry = profile.getLockExpiry() == null ? null : new java.sql.Date( profile.getLockExpiry().getTime() );
            if( existingProfile == null ) {
                // User is new: insert new user record
                setProfileParameters( ps1, profile, password, ts );
                ps1.setTimestamp( 10, ts );
                ps1.setBoolean( 11, profile.isPasswordMustChange() );
                ps1.execute();

                // Insert new role record
                ps2.setString( 1, profile.getLoginName() );
                int roles = 0;
                try ( ResultSet rs = ps2.executeQuery() ) {
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
                ps4.setDate( 10, lockExpiry );
                ps4.setBoolean( 11, profile.isPasswordMustChange() );
                ps4.setString( 12, profile.getLoginName() );
                ps4.execute();
            }
            // Set the profile mod time
            profile.setLastModified( modDate );

            // Commit and close connection
            if( supportsCommits ) {
                conn.commit();
            }

            // Evict the saved login so the next lookup (e.g. a basic-auth request) sees the
            // fresh record — critically, an updated password hash on a credential change.
            if( loginName != null ) {
                byLoginCache.invalidate( loginName );
            }
        } catch( final SQLException e ) {
            throw new WikiSecurityException( e.getMessage(), e );
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Targeted update of the {@code last_login} column only — it deliberately leaves the
     * {@code modified} timestamp and all other fields untouched, so stamping a login does not
     * masquerade as a profile edit. The cached profile for this login is invalidated so the
     * next lookup (e.g. the admin user list) reflects the fresh timestamp.
     */
    @Override
    public void recordLastLogin( final String loginName, final Date when ) throws WikiSecurityException {
        if( loginName == null || when == null ) {
            return;
        }
        try( Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement( UPDATE_LAST_LOGIN ) ) {
            ps.setTimestamp( 1, new Timestamp( when.getTime() ) );
            ps.setString( 2, loginName );
            ps.executeUpdate();
            byLoginCache.invalidate( loginName );
        } catch( final SQLException e ) {
            throw new WikiSecurityException( "Could not record last login for '" + loginName + "': " + e.getMessage(), e );
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
     * Sets the common profile parameters (1-9) on a PreparedStatement for both insert and update operations.
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
        ps.setString( 9, profile.getBio() );
    }

    private UserProfile findByPreparedStatement( final String sql, final Object index ) throws NoSuchPrincipalException
    {
        UserProfile profile = null;
        boolean found = false;
        boolean unique = true;
        try( Connection conn = ds.getConnection(); PreparedStatement ps = conn.prepareStatement( sql ) ) {
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
            try( ResultSet rs = ps.executeQuery() ) {
                while ( rs.next() ) {
                    if( profile != null ) {
                        unique = false;
                        break;
                    }
                    profile = mapProfileRow( rs );
                    found = true;
                }
            }
        } catch( final SQLException e ) {
            throw new NoSuchPrincipalException( e.getMessage(), e );
        }

        if( !found ) {
            throw new NoSuchPrincipalException( "Could not find profile in database!" );
        }
        if( !unique ) {
            throw new NoSuchPrincipalException( "More than one profile in database!" );
        }
        return profile;
    }

    /** Maps the current row of {@code rs} onto a freshly-created {@link UserProfile}. */
    private UserProfile mapProfileRow( final ResultSet rs ) throws SQLException {
        final UserProfile profile = newProfile();

        // Fetch the basic user attributes
        profile.setUid( rs.getString( "uid" ) );
        if ( profile.getUid() == null ) {
            profile.setUid( generateUid( this ) );
        }
        profile.setCreated( rs.getTimestamp( "created" ) );
        profile.setEmail( rs.getString( "email" ) );
        profile.setFullname( rs.getString( "full_name" ) );
        profile.setLastModified( rs.getTimestamp( "modified" ) );
        profile.setLastLogin( rs.getTimestamp( "last_login" ) );
        final Date lockExpiryDate = rs.getDate( "lock_expiry" );
        profile.setLockExpiry( rs.wasNull() ? null : lockExpiryDate );
        profile.setLoginName( rs.getString( "login_name" ) );
        profile.setPassword( rs.getString( "password" ) );
        profile.setBio( rs.getString( "bio" ) );
        profile.setPasswordMustChange( rs.getBoolean( "password_must_change" ) );

        // Fetch the user attributes
        final String rawAttributes = rs.getString( "attributes" );
        if ( rawAttributes != null ) {
            try {
                final Map<String,? extends Serializable> userAttributes = Serializer.deserializeFromBase64( rawAttributes );
                profile.getAttributes().putAll( userAttributes );
            } catch ( final IOException e ) {
                LOG.error( "Could not parse user profile attributes!", e );
            }
        }
        return profile;
    }

    /**
     * {@inheritDoc}
     * <p>Overrides the interface default with a single {@code SELECT * FROM users}
     * pass — the default's enumerate-then-refetch pattern costs 1+N pool
     * checkouts on every admin user-list view.
     * <p>Rows with a null/empty {@code wiki_name} are skipped, mirroring
     * {@link #getWikiNames()}'s filtering, so this stays behaviorally
     * equivalent to the enumerate-then-refetch default it replaces.
     * Duplicate {@code wiki_name} values are impossible via the V015 UNIQUE constraint.
     */
    @Override
    public Collection< UserProfile > findAllProfiles() throws WikiSecurityException {
        final List< UserProfile > profiles = new ArrayList<>();
        try ( Connection conn = ds.getConnection();
              PreparedStatement ps = conn.prepareStatement( FIND_ALL );
              ResultSet rs = ps.executeQuery() ) {
            while ( rs.next() ) {
                final String wikiNameValue = rs.getString( "wiki_name" );
                if ( StringUtils.isEmpty( wikiNameValue ) ) {
                    LOG.warn( "Detected null or empty wiki name for {} in JDBCUserDataBase. Check your user database.", rs.getString( "login_name" ) );
                    continue;
                }
                profiles.add( mapProfileRow( rs ) );
            }
        } catch ( final SQLException e ) {
            throw new WikiSecurityException( e.getMessage(), e );
        }
        return profiles;
    }

}
