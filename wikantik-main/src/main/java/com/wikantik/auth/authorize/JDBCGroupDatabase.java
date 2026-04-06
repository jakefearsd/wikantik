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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.NoRequiredPropertyException;
import com.wikantik.auth.AbstractJDBCDatabase;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.WikiSecurityException;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import java.security.Principal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Date;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * <p>
 * Implementation of GroupDatabase that persists {@link Group} objects to a JDBC
 * DataSource, as might typically be provided by a web container. This
 * implementation looks up the JDBC DataSource using JNDI. The JNDI name of the
 * datasource, backing table and mapped columns used by this class can be
 * overridden by adding settings in <code>wikantik.properties</code>.
 * </p>
 * <p>
 * The only configurable property is the JNDI DataSource name
 * ({@code wikantik.groupdatabase.datasource}). Table and column names are
 * fixed to match the canonical schema in {@code postgresql.ddl}.
 * </p>
 * <p>
 * This class is typically used in conjunction with a web container's JNDI
 * resource factory. For example, Tomcat versions 4 and higher provide a basic
 * JNDI factory for registering DataSources. To give JSPWiki access to the JNDI
 * resource named by <code>jdbc/GroupDatabase</code>, you would declare the
 * datasource resource similar to this:
 * </p>
 * <blockquote><code>&lt;Context ...&gt;<br/>
 *  &nbsp;&nbsp;...<br/>
 *  &nbsp;&nbsp;&lt;Resource name="jdbc/GroupDatabase" auth="Container"<br/>
 *  &nbsp;&nbsp;&nbsp;&nbsp;type="javax.sql.DataSource" username="dbusername" password="dbpassword"<br/>
 *  &nbsp;&nbsp;&nbsp;&nbsp;driverClassName="org.postgresql.Driver" url="jdbc:postgresql://localhost:5432/wikantik"<br/>
 *  &nbsp;&nbsp;&nbsp;&nbsp;maxActive="8" maxIdle="4"/&gt;<br/>
 *  &nbsp;...<br/>
 * &lt;/Context&gt;</code></blockquote>
 * <p>
 * JDBC driver JARs should be added to Tomcat's <code>common/lib</code>
 * directory. For more Tomcat 5.5 JNDI configuration examples, see <a
 * href="http://tomcat.apache.org/tomcat-5.5-doc/jndi-resources-howto.html">
 * http://tomcat.apache.org/tomcat-5.5-doc/jndi-resources-howto.html</a>.
 * </p>
 * <p>
 * JDBCGroupDatabase commits changes as transactions if the back-end database
 * supports them. Changes are made
 * immediately (during the {@link #save(Group, Principal)} method).
 * </p>
 * 
 * @since 2.3
 */
public class JDBCGroupDatabase extends AbstractJDBCDatabase implements GroupDatabase {

    private static final String FIND_ALL = "SELECT DISTINCT * FROM groups";
    private static final String FIND_GROUP = "SELECT DISTINCT * FROM groups WHERE name=?";
    private static final String FIND_MEMBERS = "SELECT * FROM group_members WHERE name=?";
    private static final String INSERT_GROUP = "INSERT INTO groups (name,modified,modifier,created,creator) VALUES (?,?,?,?,?)";
    private static final String UPDATE_GROUP = "UPDATE groups SET modified=?,modifier=? WHERE name=?";
    private static final String INSERT_MEMBERS = "INSERT INTO group_members (name,member) VALUES (?,?)";
    private static final String DELETE_GROUP = "DELETE FROM groups WHERE name=?";
    private static final String DELETE_MEMBERS = "DELETE FROM group_members WHERE name=?";

    protected static final Logger LOG = LogManager.getLogger( JDBCGroupDatabase.class );

    private Engine engine;

    /**
     * Looks up and deletes a {@link Group} from the group database. If the
     * group database does not contain the supplied Group. this method throws a
     * {@link NoSuchPrincipalException}. The method commits the results of the
     * delete to persistent storage.
     * 
     * @param group the group to remove
     * @throws WikiSecurityException if the database does not contain the
     *             supplied group (thrown as {@link NoSuchPrincipalException})
     *             or if the commit did not succeed
     */
    @Override public void delete( final Group group ) throws WikiSecurityException
    {
        if ( "Admin".equals( group.getName() ) ) {
            throw new WikiSecurityException( "The Admin group cannot be deleted." );
        }

        if( !exists( group ) )
        {
            throw new NoSuchPrincipalException( "Not in database: " + group.getName() );
        }

        final String groupName = group.getName();
        try {
            runInTransaction( conn -> {
                try( final PreparedStatement ps1 = conn.prepareStatement( DELETE_GROUP ) ) {
                    ps1.setString( 1, groupName );
                    ps1.execute();
                }

                try( final PreparedStatement ps2 = conn.prepareStatement( DELETE_MEMBERS ) ) {
                    ps2.setString( 1, groupName );
                    ps2.execute();
                }
                return null;
            } );
        } catch( final WikiSecurityException e ) {
            // Preserve the original error message format for delete failures
            if( e.getCause() instanceof SQLException ) {
                throw new WikiSecurityException( "Could not delete group " + groupName + ": " + e.getCause().getMessage(), e.getCause() );
            }
            throw e;
        }
    }

    /**
     * Returns all wiki groups that are stored in the GroupDatabase as an array
     * of Group objects. If the database does not contain any groups, this
     * method will return a zero-length array. This method causes back-end
     * storage to load the entire set of group; thus, it should be called
     * infrequently (e.g., at initialization time).
     * 
     * @return the wiki groups
     * @throws WikiSecurityException if the groups cannot be returned by the
     *             back-end
     */
    @Override public Group[] groups() throws WikiSecurityException
    {
        final Set<Group> groups = new HashSet<>();
        try( final Connection conn = ds.getConnection();
             final PreparedStatement ps = conn.prepareStatement( FIND_ALL );
             final ResultSet rs = ps.executeQuery() )
        {
            while ( rs.next() )
            {
                final String groupName = rs.getString( "name" );
                if( groupName == null )
                {
                    LOG.warn( "Detected null group name in JDBCGroupDataBase. Check your group database." );
                }
                else
                {
                    final Group group = new Group( groupName, engine.getApplicationName() );
                    group.setCreated( rs.getTimestamp( "created" ) );
                    group.setCreator( rs.getString( "creator" ) );
                    group.setLastModified( rs.getTimestamp( "modified" ) );
                    group.setModifier( rs.getString( "modifier" ) );
                    populateGroup( group );
                    groups.add( group );
                }
            }
        }
        catch( final SQLException e )
        {
            throw new WikiSecurityException( e.getMessage(), e );
        }

        return groups.toArray( new Group[0] );
    }

    /**
     * Saves a Group to the group database. Note that this method <em>must</em>
     * fail, and throw an <code>IllegalArgumentException</code>, if the
     * proposed group is the same name as one of the built-in Roles: e.g.,
     * Admin, Authenticated, etc. The database is responsible for setting
     * create/modify timestamps, upon a successful save, to the Group. The
     * method commits the results of the delete to persistent storage.
     * 
     * @param group the Group to save
     * @param modifier the user who saved the Group
     * @throws WikiSecurityException if the Group could not be saved successfully
     */
    @Override public void save( final Group group, final Principal modifier ) throws WikiSecurityException
    {
        if( group == null || modifier == null )
        {
            throw new IllegalArgumentException( "Group or modifier cannot be null." );
        }

        if ( "Admin".equals( group.getName() ) && group.members().length == 0 ) {
            throw new WikiSecurityException(
                    "The Admin group must have at least one member. Cannot save with zero members." );
        }

        final boolean exists = exists( group );
        runInTransaction( conn -> {
            final Timestamp ts = new Timestamp( System.currentTimeMillis() );
            final Date modDate = new Date( ts.getTime() );
            if( !exists )
            {
                // Group is new: insert new group record
                try( final PreparedStatement ps = conn.prepareStatement( INSERT_GROUP ) ) {
                    ps.setString( 1, group.getName() );
                    ps.setTimestamp( 2, ts );
                    ps.setString( 3, modifier.getName() );
                    ps.setTimestamp( 4, ts );
                    ps.setString( 5, modifier.getName() );
                    ps.execute();
                }

                // Set the group creation time
                group.setCreated( modDate );
                group.setCreator( modifier.getName() );
            }
            else
            {
                // Modify existing group record
                try( final PreparedStatement ps = conn.prepareStatement( UPDATE_GROUP ) ) {
                    ps.setTimestamp( 1, ts );
                    ps.setString( 2, modifier.getName() );
                    ps.setString( 3, group.getName() );
                    ps.execute();
                }
            }
            // Set the group modified time
            group.setLastModified( modDate );
            group.setModifier( modifier.getName() );

            // Now, update the group member list

            // First, delete all existing member records
            try( final PreparedStatement ps = conn.prepareStatement( DELETE_MEMBERS ) ) {
                ps.setString( 1, group.getName() );
                ps.execute();
            }

            // Insert group member records
            try( final PreparedStatement ps = conn.prepareStatement( INSERT_MEMBERS ) ) {
                final Principal[] members = group.members();
                for( final Principal member : members ) {
                    ps.setString( 1, group.getName() );
                    ps.setString( 2, member.getName() );
                    ps.execute();
                }
            }
            return null;
        } );
    }

    /**
     * Initializes the group database based on values from a Properties object.
     * 
     * @param engine the wiki engine
     * @param props the properties used to initialize the group database
     * @throws WikiSecurityException if the database could not be initialized
     *             successfully
     * @throws NoRequiredPropertyException if a required property is not present
     */
    @Override public void initialize( final Engine engine, final Properties props ) throws NoRequiredPropertyException, WikiSecurityException
    {
        this.engine = engine;

        final String jndiName = props.getProperty( PROP_DATASOURCE, DEFAULT_DATASOURCE );
        try
        {
            final Context initCtx = new InitialContext();
            final Context ctx = (Context) initCtx.lookup( "java:comp/env" );
            ds = (DataSource) ctx.lookup( jndiName );
        }
        catch( final NamingException e )
        {
            LOG.error( "JDBCGroupDatabase initialization error: {}", e.toString() );
            throw new NoRequiredPropertyException( PROP_DATASOURCE, "JDBCGroupDatabase initialization error: " + e);
        }

        // Test connection
        try( final Connection conn = ds.getConnection();
             final PreparedStatement ps = conn.prepareStatement( FIND_ALL ) )
        {
        }
        catch( final SQLException e )
        {
            LOG.error( "DB connectivity error: {}", e.getMessage() );
            throw new WikiSecurityException("DB connectivity error: " + e.getMessage(), e );
        }
        LOG.info( "JDBCGroupDatabase initialized from JNDI DataSource: {}", jndiName );

        // Determine if the datasource supports commits
        try( final Connection conn = ds.getConnection() )
        {
            final DatabaseMetaData dmd = conn.getMetaData();
            if( dmd.supportsTransactions() )
            {
                supportsCommits = true;
                conn.setAutoCommit( false );
                LOG.info( "JDBCGroupDatabase supports transactions. Good; we will use them." );
            }
        }
        catch( final SQLException e )
        {
            LOG.warn( "JDBCGroupDatabase warning: group database doesn't seem to support transactions. Reason: {}", e.getMessage() );
        }
    }

    /**
     * Returns <code>true</code> if the Group exists in back-end storage.
     * 
     * @param group the Group to look for
     * @return the result of the search
     */
    private boolean exists( final Group group )
    {
        final String index = group.getName();
        try
        {
            findGroup( index );
            return true;
        }
        catch( final NoSuchPrincipalException e )
        {
            return false;
        }
    }

    /**
     * Loads and returns a Group from the back-end database matching a supplied
     * name.
     * 
     * @param index the name of the Group to find
     * @return the populated Group
     * @throws NoSuchPrincipalException if the Group cannot be found
     * @throws SQLException if the database query returns an error
     */
    private Group findGroup( final String index ) throws NoSuchPrincipalException
    {
        Group group = null;
        boolean found = false;
        boolean unique = true;
        try( final Connection conn = ds.getConnection();
             final PreparedStatement ps = conn.prepareStatement( FIND_GROUP ) )
        {
            ps.setString( 1, index );
            try( final ResultSet rs = ps.executeQuery() )
            {
                while ( rs.next() )
                {
                    if( group != null )
                    {
                        unique = false;
                        break;
                    }
                    group = new Group( index, engine.getApplicationName() );
                    group.setCreated( rs.getTimestamp( "created" ) );
                    group.setCreator( rs.getString( "creator" ) );
                    group.setLastModified( rs.getTimestamp( "modified" ) );
                    group.setModifier( rs.getString( "modifier" ) );
                    populateGroup( group );
                    found = true;
                }
            }
        }
        catch( final SQLException e )
        {
            throw new NoSuchPrincipalException( e.getMessage() );
        }

        if( !found )
        {
            throw new NoSuchPrincipalException( "Could not find group in database!" );
        }
        if( !unique )
        {
            throw new NoSuchPrincipalException( "More than one group in database!" );
        }
        return group;
    }

    /**
     * Fills a Group with members.
     * 
     * @param group the group to populate
     * @return the populated Group
     */
    private Group populateGroup( final Group group )
    {
        try( final Connection conn = ds.getConnection();
             final PreparedStatement ps = conn.prepareStatement( FIND_MEMBERS ) )
        {
            ps.setString( 1, group.getName() );
            try( final ResultSet rs = ps.executeQuery() )
            {
                while ( rs.next() )
                {
                    final String memberName = rs.getString( "member" );
                    if( memberName != null )
                    {
                        final WikiPrincipal principal = new WikiPrincipal( memberName, WikiPrincipal.UNSPECIFIED );
                        group.add( principal );
                    }
                }
            }
        }
        catch( final SQLException e )
        {
            // I guess that means there aren't any principals...
        }
        return group;
    }

}
