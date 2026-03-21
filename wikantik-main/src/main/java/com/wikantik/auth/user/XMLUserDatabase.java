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
import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.NoRequiredPropertyException;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.WikiSecurityException;
import com.wikantik.util.Serializer;
import com.wikantik.util.TextUtil;
import com.wikantik.util.XmlDomUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.security.Principal;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.Properties;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * <p>Manages {@link DefaultUserProfile} objects using XML files for persistence. Passwords are hashed using SHA1. User entries are simple
 * <code>&lt;user&gt;</code> elements under the root. User profile properties are attributes of the element. For example:</p>
 * <blockquote><code>
 * &lt;users&gt;<br/>
 * &nbsp;&nbsp;&lt;user loginName="janne" fullName="Janne Jalkanen"<br/>
 * &nbsp;&nbsp;&nbsp;&nbsp;wikiName="JanneJalkanen" email="janne@ecyrd.com"<br/>
 * &nbsp;&nbsp;&nbsp;&nbsp;password="{SHA}457b08e825da547c3b77fbc1ff906a1d00a7daee"/&gt;<br/>
 * &lt;/users&gt;
 * </code></blockquote>
 * <p>In this example, the un-hashed password is <code>myP@5sw0rd</code>. Passwords are hashed without salt.</p>
 * @since 2.3
 */

// FIXME: If the DB is shared across multiple systems, it's possible to lose accounts
//        if two people add new accounts right after each other from different wikis.
public class XMLUserDatabase extends AbstractUserDatabase {

    /** The wikantik.properties property specifying the file system location of the user database. */
    public static final String  PROP_USERDATABASE = "wikantik.xmlUserDatabaseFile";
    private static final String DEFAULT_USERDATABASE = "userdatabase.xml";
    private static final String ATTRIBUTES_TAG    = "attributes";
    private static final String CREATED           = "created";
    private static final String EMAIL             = "email";
    private static final String FULL_NAME         = "fullName";
    private static final String LOGIN_NAME        = "loginName";
    private static final String LAST_MODIFIED     = "lastModified";
    private static final String LOCK_EXPIRY       = "lockExpiry";
    private static final String PASSWORD          = "password";
    private static final String UID               = "uid";
    private static final String USER_TAG          = "user";
    private static final String WIKI_NAME         = "wikiName";
    private static final String DATE_FORMAT       = "yyyy.MM.dd 'at' HH:mm:ss:SSS z";
    private Document            dom;
    private File                userFile;

    /** {@inheritDoc} */
    @Override
    public synchronized void deleteByLoginName( final String loginName ) throws WikiSecurityException {
        if( dom == null ) {
            throw new WikiSecurityException( "FATAL: database does not exist" );
        }

        final NodeList users = dom.getDocumentElement().getElementsByTagName( USER_TAG );
        for( int i = 0; i < users.getLength(); i++ ) {
            final Element user = ( Element )users.item( i );
            if( user.getAttribute( LOGIN_NAME ).equals( loginName ) ) {
                dom.getDocumentElement().removeChild( user );

                // Commit to disk
                saveDOM();
                return;
            }
        }
        throw new NoSuchPrincipalException( "Not in database: " + loginName );
    }

    /** {@inheritDoc} */
    @Override
    public UserProfile findByEmail( final String index ) throws NoSuchPrincipalException {
        return findBy( EMAIL, index );
    }

    /** {@inheritDoc} */
    @Override
    public UserProfile findByFullName( final String index ) throws NoSuchPrincipalException {
        return findBy( FULL_NAME, index );
    }

    /** {@inheritDoc} */
    @Override
    public UserProfile findByLoginName( final String index ) throws NoSuchPrincipalException {
        return findBy( LOGIN_NAME, index );
    }

    /** {@inheritDoc} */
    @Override
    public UserProfile findByUid( final String uid ) throws NoSuchPrincipalException {
        return findBy( UID, uid );
    }

    /** {@inheritDoc} */
    @Override
    public UserProfile findByWikiName( final String index ) throws NoSuchPrincipalException {
        return findBy( WIKI_NAME, index );
    }

    public UserProfile findBy( final String attr, final String value ) throws NoSuchPrincipalException {
        final UserProfile profile = findByAttribute( attr, value );
        if ( profile != null ) {
            return profile;
        }
        throw new NoSuchPrincipalException( "Not in database: " + value );
    }

    /** {@inheritDoc} */
    @Override
    public Principal[] getWikiNames() throws WikiSecurityException {
        if ( dom == null ) {
            throw new IllegalStateException( "FATAL: database does not exist" );
        }
        final SortedSet< WikiPrincipal > principals = new TreeSet<>();
        final NodeList users = dom.getElementsByTagName( USER_TAG );
        for( int i = 0; i < users.getLength(); i++ ) {
            final Element user = ( Element )users.item( i );
            final String wikiName = user.getAttribute( WIKI_NAME );
            if( StringUtils.isEmpty( wikiName ) ) {
                LOG.warn( "Detected null or empty wiki name for {} in XMLUserDataBase. Check your user database.", user.getAttribute( LOGIN_NAME ) );
            } else {
                final WikiPrincipal principal = new WikiPrincipal( wikiName, WikiPrincipal.WIKI_NAME );
                principals.add( principal );
            }
        }
        return principals.toArray( new Principal[0] );
    }

    /** {@inheritDoc} */
    @Override
    public void initialize( final Engine engine, final Properties props ) throws NoRequiredPropertyException {
        final File defaultFile;
        if( engine.getRootPath() == null ) {
            LOG.warn( "Cannot identify JSPWiki root path" );
            defaultFile = new File( "WEB-INF/" + DEFAULT_USERDATABASE ).getAbsoluteFile();
        } else {
            defaultFile = new File( engine.getRootPath() + "/WEB-INF/" + DEFAULT_USERDATABASE );
        }

        // Get database file location
        final String file = TextUtil.getStringProperty( props, PROP_USERDATABASE, defaultFile.getAbsolutePath() );
        if( file == null ) {
            LOG.warn( "XML user database property " + PROP_USERDATABASE + " not found; trying " + defaultFile );
            userFile = defaultFile;
        } else {
            userFile = new File( file );
        }

        LOG.info( "XML user database at " + userFile.getAbsolutePath() );

        buildDOM();
        sanitizeDOM();
    }

    private void buildDOM() {
        final DocumentBuilderFactory factory = XmlDomUtil.createSecureDocumentBuilderFactory();
        dom = XmlDomUtil.parseXmlFile( userFile, factory );
        if( dom != null ) {
            LOG.debug( "Database successfully initialized" );
            lastModifiedTime = userFile.lastModified();
            lastCheck = System.currentTimeMillis();
        } else {
            // Create the DOM from scratch
            dom = XmlDomUtil.createEmptyDocument( "users", factory );
        }
    }

    private void saveDOM() throws WikiSecurityException {
        if( dom == null ) {
            throw new IllegalStateException( "FATAL: database does not exist" );
        }

        try {
            XmlDomUtil.saveXmlFile( userFile, io -> {
                // Write the file header and document root
                io.write( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" );
                io.write( "<users>\n" );

                // Write each profile as a <user> node
                final Element root = dom.getDocumentElement();
                final NodeList nodes = root.getElementsByTagName( USER_TAG );
                for( int i = 0; i < nodes.getLength(); i++ ) {
                    final Element user = ( Element )nodes.item( i );
                    io.write( "    <" + USER_TAG + " " );
                    io.write( UID );
                    io.write( "=\"" + user.getAttribute( UID ) + "\" " );
                    io.write( LOGIN_NAME );
                    io.write( "=\"" + user.getAttribute( LOGIN_NAME ) + "\" " );
                    io.write( WIKI_NAME );
                    io.write( "=\"" + user.getAttribute( WIKI_NAME ) + "\" " );
                    io.write( FULL_NAME );
                    io.write( "=\"" + user.getAttribute( FULL_NAME ) + "\" " );
                    io.write( EMAIL );
                    io.write( "=\"" + user.getAttribute( EMAIL ) + "\" " );
                    io.write( PASSWORD );
                    io.write( "=\"" + user.getAttribute( PASSWORD ) + "\" " );
                    io.write( CREATED );
                    io.write( "=\"" + user.getAttribute( CREATED ) + "\" " );
                    io.write( LAST_MODIFIED );
                    io.write( "=\"" + user.getAttribute( LAST_MODIFIED ) + "\" " );
                    io.write( LOCK_EXPIRY );
                    io.write( "=\"" + user.getAttribute( LOCK_EXPIRY ) + "\" " );
                    io.write( ">" );
                    final NodeList attributes = user.getElementsByTagName( ATTRIBUTES_TAG );
                    for( int j = 0; j < attributes.getLength(); j++ ) {
                        final Element attribute = ( Element )attributes.item( j );
                        final String value = extractText( attribute );
                        io.write( "\n        <" + ATTRIBUTES_TAG + ">" );
                        io.write( value );
                        io.write( "</" + ATTRIBUTES_TAG + ">" );
                    }
                    io.write( "\n    </" + USER_TAG + ">\n" );
                }
                io.write( "</users>" );
            } );
        } catch( final IOException e ) {
            throw new WikiSecurityException( e.getLocalizedMessage(), e );
        }
    }

    private volatile long lastCheck;
    private volatile long lastModifiedTime;

    private synchronized void checkForRefresh() {
        final long time = System.currentTimeMillis();
        if( time - lastCheck > 60 * 1000L ) {
            final long lastModified = userFile.lastModified();

            if( lastModified > lastModifiedTime ) {
                buildDOM();
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * @see com.wikantik.auth.user.UserDatabase#rename(String, String)
     */
    @Override
    public synchronized void rename( final String loginName, final String newName) throws DuplicateUserException, WikiSecurityException {
        if( dom == null ) {
            LOG.fatal( "Could not rename profile '" + loginName + "'; database does not exist" );
            throw new IllegalStateException( "FATAL: database does not exist" );
        }
        checkForRefresh();

        // Get the existing user; if not found, throws NoSuchPrincipalException
        final UserProfile profile = findByLoginName( loginName );

        // Get user with the proposed name; if found, it's a collision
        try {
            final UserProfile otherProfile = findByLoginName( newName );
            if( otherProfile != null ) {
                throw new DuplicateUserException( "security.error.cannot.rename", newName );
            }
        } catch( final NoSuchPrincipalException e ) {
            // Good! That means it's safe to save using the new name
        }

        // Find the user with the old login id attribute, and change it
        final NodeList users = dom.getElementsByTagName( USER_TAG );
        for( int i = 0; i < users.getLength(); i++ ) {
            final Element user = ( Element )users.item( i );
            if( user.getAttribute( LOGIN_NAME ).equals( loginName ) ) {
                final DateFormat dateFormat = new SimpleDateFormat( DATE_FORMAT );
                final Date modDate = new Date( System.currentTimeMillis() );
                setAttribute( user, LOGIN_NAME, newName );
                setAttribute( user, LAST_MODIFIED, dateFormat.format( modDate ) );
                profile.setLoginName( newName );
                profile.setLastModified( modDate );
                break;
            }
        }

        // Commit to disk
        saveDOM();
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void save( final UserProfile profile ) throws WikiSecurityException {
        if ( dom == null ) {
            LOG.fatal( "Could not save profile " + profile + " database does not exist" );
            throw new IllegalStateException( "FATAL: database does not exist" );
        }

        checkForRefresh();

        final DateFormat dateFormat = new SimpleDateFormat( DATE_FORMAT );
        final String index = profile.getLoginName();
        final NodeList users = dom.getElementsByTagName( USER_TAG );
        Element user = IntStream.range(0, users.getLength()).mapToObj(i -> (Element) users.item(i)).filter(currentUser -> currentUser.getAttribute(LOGIN_NAME).equals(index)).findFirst().orElse(null);

        boolean isNew = false;

        final Date modDate = new Date( System.currentTimeMillis() );
        if( user == null ) {
            // Create new user node
            profile.setCreated( modDate );
            LOG.info( "Creating new user " + index );
            user = dom.createElement( USER_TAG );
            dom.getDocumentElement().appendChild( user );
            setAttribute( user, CREATED, dateFormat.format( profile.getCreated() ) );
            isNew = true;
        } else {
            // To update existing user node, delete old attributes first...
            final NodeList attributes = user.getElementsByTagName( ATTRIBUTES_TAG );
            for( int i = 0; i < attributes.getLength(); i++ ) {
                user.removeChild( attributes.item( i ) );
            }
        }

        setAttribute( user, UID, profile.getUid() );
        setAttribute( user, LAST_MODIFIED, dateFormat.format( modDate ) );
        setAttribute( user, LOGIN_NAME, profile.getLoginName() );
        setAttribute( user, FULL_NAME, profile.getFullname() );
        setAttribute( user, WIKI_NAME, profile.getWikiName() );
        setAttribute( user, EMAIL, profile.getEmail() );
        final Date lockExpiry = profile.getLockExpiry();
        setAttribute( user, LOCK_EXPIRY, lockExpiry == null ? "" : dateFormat.format( lockExpiry ) );

        // Hash and save the new password if it's different from old one
        final String newPassword = profile.getPassword();
        if( newPassword != null && !newPassword.equals( "" ) ) {
            final String oldPassword = user.getAttribute( PASSWORD );
            if( !oldPassword.equals( newPassword ) ) {
                setAttribute( user, PASSWORD, getHash( newPassword ) );
            }
        }

        // Save the attributes as Base64 string
        if(!profile.getAttributes().isEmpty()) {
            try {
                final String encodedAttributes = Serializer.serializeToBase64( profile.getAttributes() );
                final Element attributes = dom.createElement( ATTRIBUTES_TAG );
                user.appendChild( attributes );
                final Text value = dom.createTextNode( encodedAttributes );
                attributes.appendChild( value );
            } catch( final IOException e ) {
                throw new WikiSecurityException( "Could not save user profile attribute. Reason: " + e.getMessage(), e );
            }
        }

        // Set the profile timestamps
        if( isNew ) {
            profile.setCreated( modDate );
        }
        profile.setLastModified( modDate );

        // Commit to disk
        saveDOM();
    }

    /**
     * Private method that returns the first {@link UserProfile}matching a &lt;user&gt; element's supplied attribute. This method will also
     * set the UID if it has not yet been set.
     *
     * @param matchAttribute matching attribute
     * @param index value to match
     * @return the profile, or <code>null</code> if not found
     */
    private UserProfile findByAttribute( final String matchAttribute, String index ) {
        if ( dom == null ) {
            throw new IllegalStateException( "FATAL: database does not exist" );
        }

        checkForRefresh();
        final NodeList users = dom.getElementsByTagName( USER_TAG );
        if( users == null ) {
            return null;
        }

        // check if we have to do a case-insensitive compare
        final boolean caseSensitiveCompare = !matchAttribute.equals( EMAIL );

        for( int i = 0; i < users.getLength(); i++ ) {
            final Element user = (Element) users.item( i );
            String userAttribute = user.getAttribute( matchAttribute );
            if( !caseSensitiveCompare ) {
                userAttribute = StringUtils.lowerCase(userAttribute);
                index = StringUtils.lowerCase(index);
            }
            if( userAttribute.equals( index ) ) {
                final UserProfile profile = newProfile();

                // Parse basic attributes
                profile.setUid( user.getAttribute( UID ) );
                if( profile.getUid() == null || profile.getUid().isEmpty() ) {
                    profile.setUid( generateUid( this ) );
                }
                profile.setLoginName( user.getAttribute( LOGIN_NAME ) );
                profile.setFullname( user.getAttribute( FULL_NAME ) );
                profile.setPassword( user.getAttribute( PASSWORD ) );
                profile.setEmail( user.getAttribute( EMAIL ) );

                // Get created/modified timestamps
                final String created = user.getAttribute( CREATED );
                final String modified = user.getAttribute( LAST_MODIFIED );
                profile.setCreated( parseDate( profile, created ) );
                profile.setLastModified( parseDate( profile, modified ) );

                // Is the profile locked?
                final String lockExpiry = user.getAttribute( LOCK_EXPIRY );
                if( StringUtils.isEmpty( lockExpiry ) || lockExpiry.isEmpty() ) {
                    profile.setLockExpiry( null );
                } else {
                    profile.setLockExpiry( new Date( Long.parseLong( lockExpiry ) ) );
                }

                // Extract all the user's attributes (should only be one attributes tag, but you never know!)
                final NodeList attributes = user.getElementsByTagName( ATTRIBUTES_TAG );
                for( int j = 0; j < attributes.getLength(); j++ ) {
                    final Element attribute = ( Element )attributes.item( j );
                    final String serializedMap = extractText( attribute );
                    try {
                        final Map< String, ? extends Serializable > map = Serializer.deserializeFromBase64( serializedMap );
                        profile.getAttributes().putAll( map );
                    } catch( final IOException e ) {
                        LOG.error( "Could not parse user profile attributes!", e );
                    }
                }

                return profile;
            }
        }
        return null;
    }

    /**
     * Extracts all the text nodes that are immediate children of an Element.
     *
     * @param element the base element
     * @return the text nodes that are immediate children of the base element, concatenated together
     */
    private String extractText( final Element element ) {
        String text = "";
        if( element.getChildNodes().getLength() > 0 ) {
            final NodeList children = element.getChildNodes();
            text = IntStream.range(0, children.getLength()).mapToObj(children::item).filter(child -> child.getNodeType() == Node.TEXT_NODE).map(child -> ((Text) child).getData()).collect(Collectors.joining());
        }
        return text;
    }

    /**
     *  Tries to parse a date using the default format - then, for backwards compatibility reasons, tries the platform default.
     *
     *  @param profile profile associated to the date.
     *  @param date date to be parsed.
     *  @return A parsed date, or null, if both parse attempts fail.
     */
    private Date parseDate( final UserProfile profile, final String date ) {
        try {
            final DateFormat dateFormat = new SimpleDateFormat( DATE_FORMAT );
            return dateFormat.parse( date );
        } catch( final ParseException e ) {
            try {
                return DateFormat.getDateTimeInstance().parse( date );
            } catch( final ParseException e2 ) {
                LOG.warn( "Could not parse 'created' or 'lastModified' attribute for profile '" + profile.getLoginName() + "'." +
                          " It may have been tampered with.", e2 );
            }
        }
        return null;
    }

    /**
     * After loading the DOM, this method sanity-checks the dates in the DOM and makes sure they are formatted properly. This is sort-of
     * hacky, but it should work.
     */
    private void sanitizeDOM() {
        if( dom == null ) {
            throw new IllegalStateException( "FATAL: database does not exist" );
        }

        final NodeList users = dom.getElementsByTagName( USER_TAG );
        for( int i = 0; i < users.getLength(); i++ ) {
            final Element user = ( Element )users.item( i );

            // Sanitize UID (and generate a new one if one does not exist)
            String uid = user.getAttribute( UID ).trim();
            if( StringUtils.isEmpty( uid ) || "-1".equals( uid ) ) {
                uid = String.valueOf( generateUid( this ) );
                user.setAttribute( UID, uid );
            }

            // Sanitize dates
            final String loginName = user.getAttribute( LOGIN_NAME );
            String created = user.getAttribute( CREATED );
            String modified = user.getAttribute( LAST_MODIFIED );
            final DateFormat dateFormat = new SimpleDateFormat( DATE_FORMAT );
            try {
                created = dateFormat.format( dateFormat.parse( created ) );
                modified = dateFormat.format( dateFormat.parse( modified ) );
                user.setAttribute( CREATED, created );
                user.setAttribute( LAST_MODIFIED, modified );
            } catch( final ParseException e ) {
                try {
                    created = dateFormat.format( DateFormat.getDateTimeInstance().parse( created ) );
                    modified = dateFormat.format( DateFormat.getDateTimeInstance().parse( modified ) );
                    user.setAttribute( CREATED, created );
                    user.setAttribute( LAST_MODIFIED, modified );
                } catch( final ParseException e2 ) {
                    LOG.warn( "Could not parse 'created' or 'lastModified' attribute for profile '" + loginName + "'."
                            + " It may have been tampered with." );
                }
            }
        }
    }

    /**
     * Private method that sets an attribute value for a supplied DOM element.
     *
     * @param element the element whose attribute is to be set
     * @param attribute the name of the attribute to set
     * @param value the desired attribute value
     */
    private void setAttribute( final Element element, final String attribute, final String value ) {
        if( value != null ) {
            element.setAttribute( attribute, value );
        }
    }

}