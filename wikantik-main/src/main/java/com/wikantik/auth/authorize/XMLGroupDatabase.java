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

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.NoRequiredPropertyException;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.util.TextUtil;
import com.wikantik.util.XmlDomUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.security.Principal;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;


/**
 * <p>
 * GroupDatabase implementation for loading, persisting and storing wiki groups,
 * using an XML file for persistence. Group entries are simple
 * <code>&lt;group&gt;</code> elements under the root. Each group member is
 * representated by a <code>&lt;member&gt;</code> element. For example:
 * </p>
 * <blockquote><code>
 * &lt;groups&gt;<br/>
 * &nbsp;&nbsp;&lt;group name="TV" created="Jun 20, 2006 2:50:54 PM" lastModified="Jan 21, 2006 2:50:54 PM"&gt;<br/>
 * &nbsp;&nbsp;&nbsp;&nbsp;&lt;member principal="Archie Bunker" /&gt;<br/>
 * &nbsp;&nbsp;&nbsp;&nbsp;&lt;member principal="BullwinkleMoose" /&gt;<br/>
 * &nbsp;&nbsp;&nbsp;&nbsp;&lt;member principal="Fred Friendly" /&gt;<br/>
 * &nbsp;&nbsp;&lt;/group&gt;<br/>
 * &nbsp;&nbsp;&lt;group name="Literature" created="Jun 22, 2006 2:50:54 PM" lastModified="Jan 23, 2006 2:50:54 PM"&gt;<br/>
 * &nbsp;&nbsp;&nbsp;&nbsp;&lt;member principal="Charles Dickens" /&gt;<br/>
 * &nbsp;&nbsp;&nbsp;&nbsp;&lt;member principal="Homer" /&gt;<br/>
 * &nbsp;&nbsp;&lt;/group&gt;<br/>
 * &lt;/groups&gt;
 * </code></blockquote>
 * @since 2.4.17
 */
public class XMLGroupDatabase implements GroupDatabase {

    private static final Logger LOG = LogManager.getLogger( XMLGroupDatabase.class );

    /** The wikantik.properties property specifying the file system location of the group database. */
    public static final String    PROP_DATABASE    = "wikantik.xmlGroupDatabaseFile";

    private static final String   DEFAULT_DATABASE = "groupdatabase.xml";

    private static final String   CREATED          = "created";

    private static final String   CREATOR          = "creator";

    private static final String   GROUP_TAG        = "group";

    private static final String   GROUP_NAME       = "name";

    private static final String   LAST_MODIFIED    = "lastModified";

    private static final String   MODIFIER         = "modifier";

    private static final String   MEMBER_TAG       = "member";

    private static final String   PRINCIPAL        = "principal";

    private static final String  DATE_FORMAT       = "yyyy.MM.dd 'at' HH:mm:ss:SSS z";

    private Document              dom;

    private final DateFormat            defaultFormat  = DateFormat.getDateTimeInstance();

    private File                  file;

    private Engine                engine;

    private final Map<String, Group>    groups         = new ConcurrentHashMap<>();

    /**
      * Looks up and deletes a {@link Group} from the group database. If the
     * group database does not contain the supplied Group. this method throws a
     * {@link NoSuchPrincipalException}. The method commits the results
     * of the delete to persistent storage.
     * @param group the group to remove
    * @throws WikiSecurityException if the database does not contain the
     * supplied group (thrown as {@link NoSuchPrincipalException}) or if
     * the commit did not succeed
     */
    @Override
    public void delete( final Group group ) throws WikiSecurityException {
        final String index = group.getName();
        final boolean exists = groups.containsKey( index );

        if ( !exists )
        {
            throw new NoSuchPrincipalException( "Not in database: " + group.getName() );
        }

        groups.remove( index );

        // Commit to disk
        saveDOM();
    }

    /**
     * Returns all wiki groups that are stored in the GroupDatabase as an array
     * of Group objects. If the database does not contain any groups, this
     * method will return a zero-length array. This method causes back-end
     * storage to load the entire set of group; thus, it should be called
     * infrequently (e.g., at initialization time).
     * @return the wiki groups
     * @throws WikiSecurityException if the groups cannot be returned by the back-end
     */
    @Override
    public Group[] groups() throws WikiSecurityException {
        buildDOM();
        final Collection<Group> allGroups = groups.values();
        return allGroups.toArray( new Group[0] );
    }

    /**
     * Initializes the group database based on values from a Properties object.
     * The properties object must contain a file path to the XML database file
     * whose key is {@link #PROP_DATABASE}.
     * @param engine the wiki engine
     * @param props the properties used to initialize the group database
     * @throws NoRequiredPropertyException if the user database cannot be located, parsed, or opened
     * @throws WikiSecurityException if the database could not be initialized successfully
     */
    @Override
    public void initialize( final Engine engine, final Properties props ) throws NoRequiredPropertyException, WikiSecurityException
    {
        this.engine = engine;

        final File defaultFile;
        if ( engine.getRootPath() == null ) {
            LOG.warn( "Cannot identify JSPWiki root path" );
            defaultFile = new File( "WEB-INF/" + DEFAULT_DATABASE ).getAbsoluteFile();
        } else {
            defaultFile = new File( engine.getRootPath() + "/WEB-INF/" + DEFAULT_DATABASE );
        }

        // Get database file location
        final String filePath = TextUtil.getStringProperty(props, PROP_DATABASE , defaultFile.getAbsolutePath());
        if ( filePath == null ) {
            LOG.warn( "XML group database property " + PROP_DATABASE + " not found; trying " + defaultFile );
            file = defaultFile;
        } else {
            file = new File( filePath );
        }

        LOG.info( "XML group database at " + file.getAbsolutePath() );

        // Read DOM
        buildDOM();
    }

    /**
     * Saves a Group to the group database. Note that this method <em>must</em>
     * fail, and throw an <code>IllegalArgumentException</code>, if the
     * proposed group is the same name as one of the built-in Roles: e.g.,
     * Admin, Authenticated, etc. The database is responsible for setting
     * create/modify timestamps, upon a successful save, to the Group.
     * The method commits the results of the delete to persistent storage.
     * @param group the Group to save
     * @param modifier the user who saved the Group
     * @throws WikiSecurityException if the Group could not be saved successfully
     */
    @Override
    public void save( final Group group, final Principal modifier ) throws WikiSecurityException {
        if ( group == null || modifier == null ) {
            throw new IllegalArgumentException( "Group or modifier cannot be null." );
        }

        checkForRefresh();

        final String index = group.getName();
        final boolean isNew = !( groups.containsKey( index ) );
        final Date modDate = new Date( System.currentTimeMillis() );
        if( isNew ) {
            // If new, set created info
            group.setCreated( modDate );
            group.setCreator( modifier.getName() );
        }
        group.setModifier( modifier.getName() );
        group.setLastModified( modDate );

        // Add the group to the 'saved' list
        groups.put( index, group );

        // Commit to disk
        saveDOM();
    }

    private void buildDOM() {
        final DocumentBuilderFactory factory = XmlDomUtil.createSecureDocumentBuilderFactory();
        dom = XmlDomUtil.parseXmlFile( file, factory );
        if( dom != null ) {
            LOG.debug( "Database successfully initialized" );
            lastModified = file.lastModified();
            lastCheck    = System.currentTimeMillis();
        } else {
            // Create the DOM from scratch
            dom = XmlDomUtil.createEmptyDocument( "groups", factory );
        }

        // Ok, now go and read this sucker in
        if( dom != null ) {
            final NodeList groupNodes = dom.getElementsByTagName( GROUP_TAG );
            for( int i = 0; i < groupNodes.getLength(); i++ ) {
                final Element groupNode = (Element) groupNodes.item( i );
                final String groupName = groupNode.getAttribute( GROUP_NAME );
                if( StringUtils.isEmpty( groupName ) ) {
                    LOG.warn( "Detected null or empty group name in XMLGroupDataBase. Check your group database." );
                } else {
                    final Group group = buildGroup( groupNode, groupName );
                    groups.put( groupName, group );
                }
            }
        }
    }

    private long lastCheck;
    private long lastModified;

    private void checkForRefresh() {
        final long time = System.currentTimeMillis();
        if( time - lastCheck > 60*1000L ) {
            lastCheck = time;
            final long fileLastModified = file.lastModified();
            if( fileLastModified > lastModified ) {
                lastModified = fileLastModified;
                buildDOM();
            }
        }
    }
    /**
     * Constructs a Group based on a DOM group node.
     * @param groupNode the node in the DOM containing the node
     * @param name the name of the group
     */
    private Group buildGroup( final Element groupNode, final String name ) {
        // It's an error if either param is null (very odd)
        if ( groupNode == null || name == null ) {
            throw new IllegalArgumentException( "DOM element or name cannot be null." );
        }

        // Construct a new group
        final Group group = new Group( name, engine.getApplicationName() );

        // Get the users for this group, and add them
        final NodeList members = groupNode.getElementsByTagName( MEMBER_TAG );
        for( int i = 0; i < members.getLength(); i++ ) {
            final Element memberNode = (Element) members.item( i );
            final String principalName = memberNode.getAttribute( PRINCIPAL );
            final Principal member = new WikiPrincipal( principalName );
            group.add( member );
        }

        // Add the created/last-modified info
        final String creator = groupNode.getAttribute( CREATOR );
        final String created = groupNode.getAttribute( CREATED );
        final String modifier = groupNode.getAttribute( MODIFIER );
        final String modified = groupNode.getAttribute( LAST_MODIFIED );
        try {
            group.setCreated( new SimpleDateFormat( DATE_FORMAT ).parse( created ) );
            group.setLastModified( new SimpleDateFormat( DATE_FORMAT ).parse( modified ) );
        } catch ( final ParseException e ) {
            // If parsing failed, use the platform default
            try {
                group.setCreated( defaultFormat.parse( created ) );
                group.setLastModified( defaultFormat.parse( modified ) );
            } catch ( final ParseException e2 ) {
                LOG.warn( "Could not parse 'created' or 'lastModified' " + "attribute for " + " group'"
                          + group.getName() + "'." + " It may have been tampered with." );
            }
        }
        group.setCreator( creator );
        group.setModifier( modifier );
        return group;
    }

    private void saveDOM() throws WikiSecurityException {
        if ( dom == null ) {
            LOG.fatal( "Group database doesn't exist in memory." );
        }

        try {
            XmlDomUtil.saveXmlFile( file, io -> {
                // Write the file header and document root
                io.write( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" );
                io.write( "<groups>\n" );

                // Write each profile as a <group> node
                for( final Group group : groups.values() ) {
                    io.write( "  <" + GROUP_TAG + " " );
                    io.write( GROUP_NAME );
                    io.write( "=\"" + StringEscapeUtils.escapeXml11( group.getName() )+ "\" " );
                    io.write( CREATOR );
                    io.write( "=\"" + StringEscapeUtils.escapeXml11( group.getCreator() ) + "\" " );
                    io.write( CREATED );
                    io.write( "=\"" + new SimpleDateFormat( DATE_FORMAT ).format( group.getCreated() ) + "\" " );
                    io.write( MODIFIER );
                    io.write( "=\"" + group.getModifier() + "\" " );
                    io.write( LAST_MODIFIED );
                    io.write( "=\"" + new SimpleDateFormat( DATE_FORMAT ).format( group.getLastModified() ) + "\"" );
                    io.write( ">\n" );

                    // Write each member as a <member> node
                    for( final Principal member : group.members() ) {
                        io.write( "    <" + MEMBER_TAG + " " );
                        io.write( PRINCIPAL );
                        io.write( "=\"" + StringEscapeUtils.escapeXml11(member.getName()) + "\" " );
                        io.write( "/>\n" );
                    }

                    // Close tag
                    io.write( "  </" + GROUP_TAG + ">\n" );
                }
                io.write( "</groups>" );
            } );
        } catch( final IOException e ) {
            throw new WikiSecurityException( e.getLocalizedMessage(), e );
        }
    }

}
